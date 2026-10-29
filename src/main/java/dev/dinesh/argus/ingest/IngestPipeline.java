package dev.dinesh.argus.ingest;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.checkpoint.ResumePoint;
import dev.dinesh.argus.checkpoint.ResumePoint.Reason;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.parser.FormatDetector;
import dev.dinesh.argus.parser.LogParser;
import dev.dinesh.argus.parser.ParserChain;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.pipeline.EnvelopeFactory;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.sink.LogSink.SegmentWriter;
import dev.dinesh.argus.sink.WalCapacity;
import dev.dinesh.argus.source.FileSource;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.source.RawBatch;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.tenant.TenantIds;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Streams one file through read → parse → write stages connected by bounded queues. A full queue
 * blocks the stage before it, so memory stays bounded however large the file is.
 *
 * <p>Commit protocol: a segment's output is made durable first, and only then is its end offset
 * checkpointed. A crash at any point therefore replays at most the open segment, whose output path
 * is derived from its offsets and so is replaced rather than duplicated.
 */
@Slf4j
@Component
public class IngestPipeline {

  public enum Outcome {
    COMPLETED,
    CANCELLED
  }

  /**
   * One run's input.
   *
   * @param tenantId tenant the source belongs to; prefixes its checkpoints, output and event ids
   * @param path file to ingest
   * @param sourceId stable id under which offsets are checkpointed
   * @param fromBeginning ignore any checkpoint and start at byte 0
   * @param parser the source's primary parser, or null to detect it from the file's first lines
   * @param endOffset read exactly to this offset (a stream spool's acknowledged length), or null to
   *     read to the file's last newline
   * @param pathIsIdentity a checkpoint for a different path starts the next generation (a stream's
   *     numbered spools), rather than resuming when the inode and head match
   * @param readLimit the reader waits for each batch's bytes (the replay lane's budget), or null
   * @param lowPriority run the reader and parser threads at minimum priority (the replay lane)
   */
  public record Request(
      String tenantId,
      Path path,
      String sourceId,
      boolean fromBeginning,
      LogParser parser,
      Long endOffset,
      boolean pathIsIdentity,
      ByteRateLimiter readLimit,
      boolean lowPriority) {

    /** A file: read to its last newline, identified by inode and head. */
    public Request(
        String tenantId, Path path, String sourceId, boolean fromBeginning, LogParser parser) {
      this(tenantId, path, sourceId, fromBeginning, parser, null, false);
    }

    /** At full speed and normal priority (the live lane). */
    public Request(
        String tenantId,
        Path path,
        String sourceId,
        boolean fromBeginning,
        LogParser parser,
        Long endOffset,
        boolean pathIsIdentity) {
      this(tenantId, path, sourceId, fromBeginning, parser, endOffset, pathIsIdentity, null, false);
    }

    /** The same run on the replay lane: paced by {@code limit}, at minimum thread priority. */
    public Request onReplayLane(ByteRateLimiter limit) {
      return new Request(
          tenantId, path, sourceId, fromBeginning, parser, endOffset, pathIsIdentity, limit, true);
    }
  }

  static final String THREAD_PREFIX = "ingest-";
  private static final long HANDOFF_POLL_MS = 100;
  private static final int UNPARSED_SAMPLES_LOGGED = 10;
  private static final int SAMPLE_CHARS = 200;
  private static final Set<Reason> FILE_CHANGED =
      EnumSet.of(Reason.FILE_REPLACED, Reason.FILE_TRUNCATED, Reason.FILE_REWRITTEN);
  private static final RawBatch END_OF_RAW =
      new RawBatch(null, -1, -1, List.of(), new long[0], -1, -1, false, 0);
  private static final LogBatch END_OF_PARSED =
      new LogBatch(null, -1, -1, List.of(), -1, -1, false, 0, 0, 0);

  private final IngestProperties props;
  private final ParserRegistry parsers;
  private final LogSink sink;
  private final CheckpointStore checkpoints;
  private final WalCapacity walCapacity;

  /** Without an archive store nothing drains the WAL, so there is no WAL limit to wait for. */
  @Autowired
  public IngestPipeline(
      IngestProperties props,
      ParserRegistry parsers,
      LogSink sink,
      CheckpointStore checkpoints,
      ObjectProvider<WalCapacity> walCapacity) {
    this(
        props, parsers, sink, checkpoints, walCapacity.getIfAvailable(() -> WalCapacity.UNBOUNDED));
  }

  public IngestPipeline(
      IngestProperties props, ParserRegistry parsers, LogSink sink, CheckpointStore checkpoints) {
    this(props, parsers, sink, checkpoints, WalCapacity.UNBOUNDED);
  }

  public IngestPipeline(
      IngestProperties props,
      ParserRegistry parsers,
      LogSink sink,
      CheckpointStore checkpoints,
      WalCapacity walCapacity) {
    this.props = props;
    this.parsers = parsers;
    this.sink = sink;
    this.checkpoints = checkpoints;
    this.walCapacity = walCapacity;
  }

  /** True while the WAL is above its high watermark: new jobs would only wait. */
  public boolean walFull() {
    return walCapacity.isFull();
  }

  /**
   * Runs the ingest on the calling thread (which acts as the writer) and returns once the file is
   * fully committed or the run is cancelled. Any stage failure stops all stages and is rethrown.
   */
  public Outcome run(Request request, IngestProgress progress, AtomicBoolean cancel)
      throws Exception {
    String tenantId = TenantIds.requireValid(request.tenantId());
    String sourceId = SourceOffset.requireValidSourceId(request.sourceId());
    long startNanos = System.nanoTime();
    Outcome outcome = null;
    Throwable error = null;
    try {
      outcome = ingest(request, sourceId, progress, cancel);
      return outcome;
    } catch (Throwable t) {
      error = t;
      throw t;
    } finally {
      // Canonical line: one per run, success or not.
      log.info(
          "Ingest finished tenant={} source={} outcome={} error={} resumeReason={} format={}"
              + " formatSource={} startOffset={} committedOffset={} totalBytes={} segments={}"
              + " lines={} fallbackParsed={} unparsed={} rejected={} truncated={} pausedMs={}"
              + " durationMs={}",
          tenantId,
          sourceId,
          outcome == null ? "FAILED" : outcome,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage(),
          progress.resumeReason(),
          progress.format(),
          progress.formatSource(),
          progress.startOffset(),
          progress.committedOffset(),
          progress.totalBytes(),
          progress.segmentsCommitted(),
          progress.lines(),
          progress.fallbackParsed(),
          progress.unparsed(),
          progress.rejected(),
          progress.truncated(),
          progress.pausedMs(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
  }

  private Outcome ingest(
      Request request, String sourceId, IngestProgress progress, AtomicBoolean cancel)
      throws Exception {
    Path path = request.path();
    sink.discardUncommitted(request.tenantId(), sourceId);
    // Open first, then compare the opened file with the checkpoint, so a rotation between the two
    // steps cannot pair this file's offsets with another file's identity.
    FileSource source = FileSource.open(path, sourceId, props);
    Stages stages;
    try {
      ResumePoint resume =
          ResumePoint.resolve(
              checkpoints.load(request.tenantId(), sourceId),
              source,
              request.pathIsIdentity() ? path.toString() : null,
              request.fromBeginning());
      if (FILE_CHANGED.contains(resume.reason())) {
        log.warn(
            "Source {} ({}): {}; restarting from offset 0 as generation {}",
            sourceId,
            path,
            resume.reason(),
            resume.generation());
      }
      if (request.endOffset() == null) {
        source.startAt(resume.offset());
      } else {
        source.startBetween(resume.offset(), request.endOffset());
      }
      int headBytes = (int) Math.min(FileSource.FINGERPRINT_BYTES, source.limit());
      SourceOffset template =
          new SourceOffset(
              sourceId,
              path.toString(),
              source.fileKey(),
              resume.generation(),
              resume.offset(),
              headBytes,
              source.sha256OfFirst(headBytes));
      LogParser primary = request.parser();
      if (primary == null) {
        primary =
            parsers
                .detect(source.headLines(FormatDetector.SAMPLE_BYTES, FormatDetector.SAMPLE_LINES))
                .parser();
      }
      progress.started(resume.offset(), source.limit(), resume.reason().name());
      progress.format(primary.name(), request.parser() == null ? "detected" : "requested");
      log.info(
          "Ingest start source={} path={} offset={} limit={} size={} generation={} reason={}"
              + " format={}",
          sourceId,
          path,
          resume.offset(),
          source.limit(),
          source.size(),
          resume.generation(),
          resume.reason(),
          primary.name());
      stages =
          new Stages(
              source,
              template,
              parsers.chain(primary, props.keepUnparsed()),
              request.tenantId(),
              new EnvelopeFactory(request.tenantId(), sourceId, resume.generation()),
              progress,
              cancel,
              request.readLimit(),
              request.lowPriority());
    } catch (Exception e) {
      source.close();
      throw e;
    }

    try {
      stages.start();
      stages.write();
    } catch (Throwable t) {
      stages.fail(t);
    } finally {
      stages.stopAndJoin();
    }
    if (stages.failure.get() != null) {
      Throwable failure = stages.failure.get();
      throw failure instanceof Exception e ? e : new IllegalStateException(failure);
    }
    // Decided by what was committed, not by the flag: a cancel that arrives after the last commit
    // still leaves a fully ingested file.
    return stages.committedOffset >= source.limit() ? Outcome.COMPLETED : Outcome.CANCELLED;
  }

  /** State shared by the three stages of one run. */
  private final class Stages {

    private final FileSource source;
    private final String tenantId;
    private final String sourceId;
    private final SourceOffset checkpointTemplate;
    private final ParserChain chain;
    private final EnvelopeFactory envelopes;
    private final IngestProgress progress;
    private final AtomicBoolean cancel;
    private final ByteRateLimiter readLimit;
    private final boolean lowPriority;
    private final BlockingQueue<RawBatch> raw;
    private final BlockingQueue<LogBatch> parsed;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger unparsedSamples = new AtomicInteger();
    private final List<Thread> threads = new ArrayList<>();

    /** Written and read only by the writer (caller) thread. */
    private long committedOffset;

    Stages(
        FileSource source,
        SourceOffset checkpointTemplate,
        ParserChain chain,
        String tenantId,
        EnvelopeFactory envelopes,
        IngestProgress progress,
        AtomicBoolean cancel,
        ByteRateLimiter readLimit,
        boolean lowPriority) {
      this.source = source;
      this.tenantId = tenantId;
      this.chain = chain;
      this.envelopes = envelopes;
      this.sourceId = checkpointTemplate.sourceId();
      this.checkpointTemplate = checkpointTemplate;
      this.committedOffset = checkpointTemplate.byteOffset();
      this.progress = progress;
      this.cancel = cancel;
      this.readLimit = readLimit;
      this.lowPriority = lowPriority;
      this.raw = new ArrayBlockingQueue<>(props.rawQueueCapacity());
      this.parsed = new ArrayBlockingQueue<>(props.parsedQueueCapacity());
    }

    void start() {
      threads.add(startThread("reader", this::read));
      threads.add(startThread("parser", this::parse));
    }

    private Thread startThread(String stage, Runnable body) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  body.run();
                } catch (Throwable t) {
                  fail(t);
                }
              },
              THREAD_PREFIX + sourceId + "-" + stage);
      thread.setDaemon(true);
      if (lowPriority) {
        thread.setPriority(Thread.MIN_PRIORITY);
      }
      thread.start();
      return thread;
    }

    private void read() {
      try {
        for (RawBatch batch = source.nextBatch(); batch != null; batch = source.nextBatch()) {
          if (readLimit != null
              && !readLimit.acquire(batch.endOffset() - batch.startOffset(), this::stopping)) {
            return;
          }
          if (!handOff(raw, batch)) {
            return;
          }
          progress.read(batch.endOffset(), batch.truncatedLines());
        }
        handOff(raw, END_OF_RAW);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      } catch (Exception e) {
        fail(e);
      }
    }

    private void parse() {
      while (true) {
        RawBatch batch = takeNext(raw);
        if (batch == null) {
          return;
        }
        if (batch == END_OF_RAW) {
          handOff(parsed, END_OF_PARSED);
          return;
        }
        List<LogEnvelope> records = new ArrayList<>(batch.lines().size());
        int rejected = 0;
        int unparsed = 0;
        int fallback = 0;
        Instant observedAt = Instant.now();
        for (int i = 0; i < batch.lines().size(); i++) {
          String line = batch.lines().get(i);
          ParserChain.Parsed result = chain.parse(line);
          if (result == null) {
            rejected++;
            sampleUnparsed(line, batch);
            continue;
          }
          switch (result.outcome()) {
            case UNPARSED -> {
              unparsed++;
              sampleUnparsed(line, batch);
            }
            case FALLBACK -> fallback++;
            case PRIMARY -> {}
          }
          records.add(envelopes.create(result, batch.lineOffsets()[i], observedAt));
        }
        progress.parsed(records.size(), rejected, unparsed, fallback);
        LogBatch out =
            new LogBatch(
                batch.sourceId(),
                batch.segmentIndex(),
                batch.sequence(),
                records,
                batch.startOffset(),
                batch.endOffset(),
                batch.lastInSegment(),
                rejected,
                unparsed,
                fallback);
        if (!handOff(parsed, out)) {
          return;
        }
      }
    }

    /** Runs on the caller thread; the only stage that touches the sink and checkpoints. */
    void write() throws Exception {
      SegmentWriter segment = null;
      try {
        while (true) {
          LogBatch batch = takeNext(parsed);
          if (batch == null || batch == END_OF_PARSED) {
            return;
          }
          if (segment == null) {
            // Segment boundary: wait here while the WAL is full. The queues behind this thread
            // then fill and stop the parser and reader, so memory stays bounded (guardrail 2).
            if (!walCapacity.awaitRoom(this::stopping, progress::paused)) {
              if (Thread.currentThread().isInterrupted()) {
                // Like takeNext / handOff: an interrupt fails the run, it isn't a cancel.
                fail(new InterruptedException("Interrupted while waiting for WAL room"));
              }
              return;
            }
            segment =
                sink.open(tenantId, sourceId, checkpointTemplate.generation(), batch.startOffset());
          }
          segment.write(batch);
          if (batch.lastInSegment()) {
            String location = segment.commit(batch.endOffset());
            segment = null;
            checkpoints.save(tenantId, checkpointAt(batch.endOffset()));
            committedOffset = batch.endOffset();
            progress.committed(batch.endOffset(), location);
            log.info(
                "Segment committed source={} endOffset={} location={}",
                sourceId,
                batch.endOffset(),
                location);
          }
        }
      } finally {
        if (segment != null) {
          segment.abort();
        }
      }
    }

    private SourceOffset checkpointAt(long offset) {
      SourceOffset t = checkpointTemplate;
      return new SourceOffset(
          t.sourceId(),
          t.path(),
          t.fileKey(),
          t.generation(),
          offset,
          t.headBytes(),
          t.headSha256());
    }

    void fail(Throwable t) {
      if (failure.compareAndSet(null, t)) {
        log.error("Ingest failed source={}", sourceId, t);
      }
      stop.set(true);
    }

    void stopAndJoin() throws InterruptedException {
      stop.set(true);
      // An interrupted writer still waits for the stages (each stops at its next poll) and closes
      // the source; the interrupt is restored afterwards.
      boolean interrupted = Thread.interrupted();
      try {
        for (Thread thread : threads) {
          thread.join();
        }
      } finally {
        try {
          source.close();
        } catch (Exception e) {
          log.debug("Ignoring close failure for source {}", sourceId, e);
        }
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private boolean stopping() {
      return stop.get() || cancel.get();
    }

    /** Blocks while {@code queue} is full (backpressure); returns false if the run is stopping. */
    private <T> boolean handOff(BlockingQueue<T> queue, T item) {
      try {
        while (!stopping()) {
          if (queue.offer(item, HANDOFF_POLL_MS, TimeUnit.MILLISECONDS)) {
            return true;
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
      return false;
    }

    /** Waits for the next item; returns null if the run is stopping. */
    private <T> T takeNext(BlockingQueue<T> queue) {
      try {
        while (!stopping()) {
          T item = queue.poll(HANDOFF_POLL_MS, TimeUnit.MILLISECONDS);
          if (item != null) {
            return item;
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail(e);
      }
      return null;
    }

    private void sampleUnparsed(String line, RawBatch batch) {
      if (unparsedSamples.incrementAndGet() <= UNPARSED_SAMPLES_LOGGED) {
        log.warn(
            "No parser understood line source={} batchStartOffset={}: {}",
            sourceId,
            batch.startOffset(),
            line.length() > SAMPLE_CHARS ? line.substring(0, SAMPLE_CHARS) + "…" : line);
      }
    }
  }
}
