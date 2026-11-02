package dev.dinesh.argus.ingest;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.checkpoint.ResumePoint;
import dev.dinesh.argus.checkpoint.ResumePoint.Reason;
import dev.dinesh.argus.config.FramingProperties;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.InstanceProperties;
import dev.dinesh.argus.framing.CriPartialJoiner;
import dev.dinesh.argus.framing.FramedRecord;
import dev.dinesh.argus.framing.FramingSpec;
import dev.dinesh.argus.framing.RecordFramer;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.parser.CriLogParser;
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
import dev.dinesh.argus.tail.TailEvent;
import dev.dinesh.argus.tail.TailHub;
import dev.dinesh.argus.tenant.TenantIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
   * @param framing how lines join into records, or null for {@code argus.framing.mode}
   * @param finalRun no more lines can follow the source's last line in this generation (a drain, a
   *     sealed spool file), so a multiline record open at its end is ingested rather than held back
   *     ({@code P7_PLAN.md} D3)
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
      boolean lowPriority,
      FramingSpec framing,
      boolean finalRun) {

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
      this(
          tenantId,
          path,
          sourceId,
          fromBeginning,
          parser,
          endOffset,
          pathIsIdentity,
          null,
          false,
          null,
          false);
    }

    /** The same run on the replay lane: paced by {@code limit}, at minimum thread priority. */
    public Request onReplayLane(ByteRateLimiter limit) {
      return new Request(
          tenantId,
          path,
          sourceId,
          fromBeginning,
          parser,
          endOffset,
          pathIsIdentity,
          limit,
          true,
          framing,
          finalRun);
    }

    /** The same run with {@code framing} (null: {@code argus.framing.mode}). */
    public Request withFraming(FramingSpec framing) {
      return new Request(
          tenantId,
          path,
          sourceId,
          fromBeginning,
          parser,
          endOffset,
          pathIsIdentity,
          readLimit,
          lowPriority,
          framing,
          finalRun);
    }

    /** The same run, the last for its source's lines in this generation. */
    public Request asFinalRun() {
      return new Request(
          tenantId,
          path,
          sourceId,
          fromBeginning,
          parser,
          endOffset,
          pathIsIdentity,
          readLimit,
          lowPriority,
          framing,
          true);
    }

    /**
     * Whether the run's lines go to the live tail ({@code P4_PLAN.md} D1): a live file job's do. A
     * stream run's lines were tailed when they were pushed, and the replay lane's are history.
     */
    public boolean tailed() {
      return !pathIsIdentity && !lowPriority;
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
  private final ArgusMetrics metrics;
  private final TailHub tail;
  private final String instanceId;
  private final FramingProperties framing;

  /** Without an archive store nothing drains the WAL, so there is no WAL limit to wait for. */
  @Autowired
  public IngestPipeline(
      IngestProperties props,
      ParserRegistry parsers,
      LogSink sink,
      CheckpointStore checkpoints,
      ObjectProvider<WalCapacity> walCapacity,
      ObjectProvider<ArgusMetrics> metrics,
      ObjectProvider<TailHub> tail,
      ObjectProvider<InstanceProperties> instance,
      ObjectProvider<FramingProperties> framing) {
    this(
        props,
        parsers,
        sink,
        checkpoints,
        walCapacity.getIfAvailable(() -> WalCapacity.UNBOUNDED),
        metrics.getIfAvailable(ArgusMetrics::noop),
        tail.getIfAvailable(),
        instance.getIfAvailable(() -> InstanceProperties.NONE).id(),
        framing.getIfAvailable(() -> FramingProperties.DEFAULTS));
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
    this(props, parsers, sink, checkpoints, walCapacity, ArgusMetrics.noop());
  }

  public IngestPipeline(
      IngestProperties props,
      ParserRegistry parsers,
      LogSink sink,
      CheckpointStore checkpoints,
      WalCapacity walCapacity,
      ArgusMetrics metrics) {
    this(props, parsers, sink, checkpoints, walCapacity, metrics, null);
  }

  /**
   * @param tail where live file jobs publish their lines, or null for none
   */
  public IngestPipeline(
      IngestProperties props,
      ParserRegistry parsers,
      LogSink sink,
      CheckpointStore checkpoints,
      WalCapacity walCapacity,
      ArgusMetrics metrics,
      TailHub tail) {
    this(props, parsers, sink, checkpoints, walCapacity, metrics, tail, null);
  }

  /**
   * @param instanceId {@code argus.instance.id}, joined to every line's identity; null for a single
   *     instance (P6 D8)
   */
  public IngestPipeline(
      IngestProperties props,
      ParserRegistry parsers,
      LogSink sink,
      CheckpointStore checkpoints,
      WalCapacity walCapacity,
      ArgusMetrics metrics,
      TailHub tail,
      String instanceId) {
    this(
        props,
        parsers,
        sink,
        checkpoints,
        walCapacity,
        metrics,
        tail,
        instanceId,
        FramingProperties.DEFAULTS);
  }

  /**
   * @param framing the default framing and its limits ({@code P7_PLAN.md} D2–D4)
   */
  public IngestPipeline(
      IngestProperties props,
      ParserRegistry parsers,
      LogSink sink,
      CheckpointStore checkpoints,
      WalCapacity walCapacity,
      ArgusMetrics metrics,
      TailHub tail,
      String instanceId,
      FramingProperties framing) {
    this.framing = framing;
    this.instanceId = instanceId;
    this.props = props;
    this.parsers = parsers;
    this.sink = sink;
    this.checkpoints = checkpoints;
    this.walCapacity = walCapacity;
    this.metrics = metrics;
    this.tail = tail;
  }

  /** {@code argus.instance.id}, or null for a single instance. */
  public String instanceId() {
    return instanceId;
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
              + " lines={} fallbackParsed={} unparsed={} rejected={} truncated={} heldBackBytes={}"
              + " pausedMs={}"
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
          progress.heldBackBytes(),
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
      FramingSpec spec = request.framing() != null ? request.framing() : framing.defaultSpec();
      CriPartialJoiner joiner =
          primary instanceof CriLogParser && spec.mode() != FramingSpec.Mode.NONE
              ? new CriPartialJoiner(framing.limits().maxBytes())
              : null;
      stages =
          new Stages(
              source,
              template,
              parsers.chain(primary, props.keepUnparsed()),
              request.tenantId(),
              new EnvelopeFactory(request.tenantId(), sourceId, resume.generation(), instanceId),
              progress,
              cancel,
              request.readLimit(),
              request.lowPriority(),
              tail != null && request.tailed(),
              spec.framer(framing.limits()),
              joiner,
              request.finalRun());
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
    // still leaves a fully ingested file. A record held back at the end counts as ingested: the
    // next run reads it again (P7 D3).
    long heldBackFrom = stages.heldBackFrom;
    if (heldBackFrom >= 0) {
      progress.heldBack(source.limit() - heldBackFrom);
    }
    long end = heldBackFrom >= 0 ? heldBackFrom : source.limit();
    return stages.committedOffset >= end ? Outcome.COMPLETED : Outcome.CANCELLED;
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
    private final String lane;
    private final boolean tailed;
    private final BlockingQueue<RawBatch> raw;
    private final BlockingQueue<LogBatch> parsed;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Path path;
    private final AtomicInteger unparsedSamples = new AtomicInteger();
    private final List<Thread> threads = new ArrayList<>();
    private final RecordFramer framer;
    private final CriPartialJoiner joiner;
    private final boolean finalRun;

    /** Written and read only by the writer (caller) thread. */
    private long committedOffset;

    // Parser-thread state (P7 D3). Batch and segment ends fall only at record starts: a batch ends
    // where the open record starts, and a segment end the reader chose waits for the next line,
    // which tells whether the record open there continues past it.
    private long emitStart = -1;
    private long segmentStart;
    private boolean closePending;
    private long closeAt;
    private RawBatch lastBatch;
    private Records current;
    private boolean stopped;

    /** Where a record held back at the end of the source starts, or -1; read after the join. */
    private volatile long heldBackFrom = -1;

    Stages(
        FileSource source,
        SourceOffset checkpointTemplate,
        ParserChain chain,
        String tenantId,
        EnvelopeFactory envelopes,
        IngestProgress progress,
        AtomicBoolean cancel,
        ByteRateLimiter readLimit,
        boolean lowPriority,
        boolean tailed,
        RecordFramer framer,
        CriPartialJoiner joiner,
        boolean finalRun) {
      this.tailed = tailed;
      this.framer = framer;
      this.joiner = joiner;
      this.finalRun = finalRun;
      this.source = source;
      this.path = Path.of(checkpointTemplate.path());
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
      this.lane = (lowPriority ? Lane.REPLAY : Lane.LIVE).name().toLowerCase(Locale.ROOT);
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
          if (readLimit != null) {
            long waitStart = System.nanoTime();
            boolean passed =
                readLimit.acquire(batch.endOffset() - batch.startOffset(), this::stopping);
            metrics.replayWait(System.nanoTime() - waitStart);
            if (!passed) {
              return;
            }
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
          if (closePending && !endOfSource()) {
            return;
          }
          handOff(parsed, END_OF_PARSED);
          return;
        }
        if (emitStart < 0) {
          emitStart = batch.startOffset();
          segmentStart = emitStart;
        }
        lastBatch = batch;
        if (!parse(batch)) {
          return;
        }
      }
    }

    /** Returns false if the run is stopping. */
    private boolean parse(RawBatch batch) {
      current = new Records(batch);
      for (int i = 0; i < batch.lines().size() && !stopped; i++) {
        String line = batch.lines().get(i);
        long offset = batch.lineOffsets()[i];
        if (joiner == null) {
          offer(line, offset, 1, 0);
        } else {
          joiner.offer(line, offset, this::offer);
        }
      }
      if (stopped) {
        return false;
      }
      long held = heldOffset();
      boolean closes = batch.lastInSegment();
      if (closes && held >= 0) {
        closePending = true;
        closeAt = batch.endOffset();
        closes = false;
      } else if (closePending && batch.lastInSegment()) {
        // A batch with no lines (blank ones only) moves the pending segment end past itself.
        closeAt = batch.endOffset();
      }
      return emit(current, held >= 0 ? held : batch.endOffset(), closes);
    }

    private void offer(CriPartialJoiner.Line line) {
      offer(line.text(), line.offset(), line.fragments(), line.droppedFragments());
    }

    /** Parses and frames one line; sets {@link #stopped} if the run is stopping. */
    private void offer(String line, long offset, int fragments, int droppedFragments) {
      if (stopped) {
        return;
      }
      ParserChain.Parsed result = chain.parse(line);
      if (result != null && fragments > 1) {
        result.record().getMeta().put(CriPartialJoiner.FRAGMENTS_ATTRIBUTE, fragments);
      }
      current.dropped += droppedFragments;
      FramedRecord done = framer.offer(line, offset, result);
      boolean joined = framer.lastJoined();
      if (!joined) {
        if (result == null) {
          current.rejected++;
          sampleUnparsed(line, current.batch);
        } else if (result.outcome() == ParserChain.Outcome.UNPARSED) {
          sampleUnparsed(line, current.batch);
        }
      }
      if (closePending) {
        closePending = false;
        if (!joined) {
          // The record open at the segment end was complete: the segment ends where this line
          // starts (where the reader put the end, unless a split CRI line straddled it).
          if (done != null) {
            current.add(done);
            done = null;
          }
          if (offset > segmentStart && !closeSegment(offset)) {
            return;
          }
        } else if (emitStart > segmentStart && !closeSegment(emitStart)) {
          // It continues past the segment end: the segment ends at its start instead.
          return;
        }
        // Otherwise the segment holds nothing else, and the reader's next end closes it.
      }
      if (done != null) {
        current.add(done);
      }
    }

    private boolean closeSegment(long end) {
      Records closing = current;
      current = new Records(closing.batch);
      stopped = !emit(closing, end, true);
      return !stopped;
    }

    /** Where the first line not yet handed on starts: the open record's, else a split line's. */
    private long heldOffset() {
      long open = framer.openOffset();
      return open >= 0 || joiner == null ? open : joiner.pendingOffset();
    }

    /**
     * The run read its source to the end with a record (or a split CRI line) open there: it is
     * ingested if no more lines can follow it, and otherwise held back for the next run (P7 D3,
     * D6). A single-line record never waits, so a source of single-line records commits exactly as
     * before framing.
     */
    private boolean endOfSource() {
      closePending = false;
      boolean split = joiner != null && joiner.pendingOffset() >= 0;
      if (finalRun || (!split && framer.openLines() <= 1) || sourceIsQuiet()) {
        current = new Records(lastBatch);
        if (split) {
          joiner.flush(this::offer);
        }
        FramedRecord last = framer.flush();
        if (last != null) {
          current.add(last);
        }
        return !stopped && emit(current, closeAt, true);
      }
      if (split && framer.openOffset() >= 0 && framer.openLines() <= 1) {
        // A single-line record before the split line doesn't wait with it.
        current = new Records(lastBatch);
        current.add(framer.flush());
      } else {
        current = new Records(lastBatch);
      }
      heldBackFrom = heldOffset();
      // Commit what precedes it; a segment holding nothing else stays open and is discarded.
      return heldBackFrom <= segmentStart || emit(current, heldBackFrom, true);
    }

    private boolean sourceIsQuiet() {
      try {
        Instant modified = Files.getLastModifiedTime(path).toInstant();
        return !modified.isAfter(Instant.now().minus(framing.flushAfter()));
      } catch (IOException e) {
        return true;
      }
    }

    /** Hands {@code [emitStart, end)} to the writer; returns false if the run is stopping. */
    private boolean emit(Records records, long end, boolean lastInSegment) {
      if (!lastInSegment && end == emitStart && records.list.isEmpty()) {
        return true;
      }
      if (records.tailEvents != null && !records.tailEvents.isEmpty()) {
        tail.publish(tenantId, records.tailEvents);
      }
      int count = records.list.size();
      progress.parsed(count, records.rejected, records.unparsed, records.fallback);
      progress.dropped(records.dropped);
      metrics.ingestBatch(
          tenantId,
          lane,
          count - records.unparsed - records.fallback,
          records.fallback,
          records.unparsed,
          records.rejected,
          end - emitStart);
      RawBatch batch = records.batch;
      LogBatch out =
          new LogBatch(
              batch.sourceId(),
              batch.segmentIndex(),
              batch.sequence(),
              records.list,
              emitStart,
              end,
              lastInSegment,
              records.rejected,
              records.unparsed,
              records.fallback);
      emitStart = end;
      if (lastInSegment) {
        segmentStart = end;
      }
      return handOff(parsed, out);
    }

    /** The records of one batch handed to the writer, and their counts. */
    private final class Records {

      final RawBatch batch;
      final List<LogEnvelope> list;
      final Instant observedAt = Instant.now();
      // Checked once per batch: while nobody tails the tenant, nothing is built.
      final List<TailEvent> tailEvents;
      int rejected;
      int unparsed;
      int fallback;
      int dropped;

      Records(RawBatch batch) {
        this.batch = batch;
        this.list = new ArrayList<>(batch.lines().size());
        this.tailEvents =
            tailed && tail.isWatched(tenantId) ? new ArrayList<>(batch.lines().size()) : null;
      }

      void add(FramedRecord record) {
        switch (record.parsed().outcome()) {
          case UNPARSED -> unparsed++;
          case FALLBACK -> fallback++;
          case PRIMARY -> {}
        }
        dropped += record.droppedLines();
        LogEnvelope envelope = envelopes.create(record.parsed(), record.offset(), observedAt);
        list.add(envelope);
        if (tailEvents != null) {
          tailEvents.add(TailEvent.ofFile(envelope));
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
            long commitStart = System.nanoTime();
            String location = segment.commit(batch.endOffset());
            segment = null;
            checkpoints.save(tenantId, checkpointAt(batch.endOffset()));
            metrics.segmentCommitted(tenantId, System.nanoTime() - commitStart);
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
