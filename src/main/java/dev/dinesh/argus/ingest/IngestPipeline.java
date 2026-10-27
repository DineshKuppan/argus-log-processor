package dev.dinesh.argus.ingest;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.checkpoint.ResumePoint;
import dev.dinesh.argus.checkpoint.ResumePoint.Reason;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.LineParser;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.sink.LogSink.SegmentWriter;
import dev.dinesh.argus.source.FileSource;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.source.RawBatch;
import dev.dinesh.argus.source.SourceOffset;
import java.nio.file.Path;
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
   * @param path file to ingest
   * @param sourceId stable id under which offsets are checkpointed
   * @param fromBeginning ignore any checkpoint and start at byte 0
   */
  public record Request(Path path, String sourceId, boolean fromBeginning) {}

  static final String THREAD_PREFIX = "ingest-";
  private static final long HANDOFF_POLL_MS = 100;
  private static final int REJECTED_SAMPLES_LOGGED = 10;
  private static final int SAMPLE_CHARS = 200;
  private static final Set<Reason> FILE_CHANGED =
      EnumSet.of(Reason.FILE_REPLACED, Reason.FILE_TRUNCATED, Reason.FILE_REWRITTEN);
  private static final RawBatch END_OF_RAW =
      new RawBatch(null, -1, -1, List.of(), -1, -1, false, 0);
  private static final LogBatch END_OF_PARSED =
      new LogBatch(null, -1, -1, List.of(), -1, -1, false, 0);

  private final IngestProperties props;
  private final LineParser parser;
  private final LogSink sink;
  private final CheckpointStore checkpoints;

  public IngestPipeline(
      IngestProperties props, LineParser parser, LogSink sink, CheckpointStore checkpoints) {
    this.props = props;
    this.parser = parser;
    this.sink = sink;
    this.checkpoints = checkpoints;
  }

  /**
   * Runs the ingest on the calling thread (which acts as the writer) and returns once the file is
   * fully committed or the run is cancelled. Any stage failure stops all stages and is rethrown.
   */
  public Outcome run(Request request, IngestProgress progress, AtomicBoolean cancel)
      throws Exception {
    String sourceId = SourceOffset.requireValidSourceId(request.sourceId());
    long startNanos = System.nanoTime();
    Outcome outcome = null;
    Throwable error = null;
    try {
      outcome = ingest(request.path(), sourceId, request.fromBeginning(), progress, cancel);
      return outcome;
    } catch (Throwable t) {
      error = t;
      throw t;
    } finally {
      // Canonical line: one per run, success or not.
      log.info(
          "Ingest finished source={} outcome={} error={} resumeReason={} startOffset={}"
              + " committedOffset={} totalBytes={} segments={} lines={} rejected={} truncated={}"
              + " durationMs={}",
          sourceId,
          outcome == null ? "FAILED" : outcome,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage(),
          progress.resumeReason(),
          progress.startOffset(),
          progress.committedOffset(),
          progress.totalBytes(),
          progress.segmentsCommitted(),
          progress.lines(),
          progress.rejected(),
          progress.truncated(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
  }

  private Outcome ingest(
      Path path,
      String sourceId,
      boolean fromBeginning,
      IngestProgress progress,
      AtomicBoolean cancel)
      throws Exception {
    sink.discardUncommitted(sourceId);
    // Open first, then compare the opened file with the checkpoint, so a rotation between the two
    // steps cannot pair this file's offsets with another file's identity.
    FileSource source = FileSource.open(path, sourceId, props);
    Stages stages;
    try {
      ResumePoint resume = ResumePoint.resolve(checkpoints.load(sourceId), source, fromBeginning);
      if (FILE_CHANGED.contains(resume.reason())) {
        log.warn(
            "Source {} ({}): {}; restarting from offset 0 as generation {}",
            sourceId,
            path,
            resume.reason(),
            resume.generation());
      }
      source.startAt(resume.offset());
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
      progress.started(resume.offset(), source.limit(), resume.reason().name());
      log.info(
          "Ingest start source={} path={} offset={} limit={} size={} generation={} reason={}",
          sourceId,
          path,
          resume.offset(),
          source.limit(),
          source.size(),
          resume.generation(),
          resume.reason());
      stages = new Stages(source, template, progress, cancel);
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
    private final String sourceId;
    private final SourceOffset checkpointTemplate;
    private final IngestProgress progress;
    private final AtomicBoolean cancel;
    private final BlockingQueue<RawBatch> raw;
    private final BlockingQueue<LogBatch> parsed;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger rejectedSamples = new AtomicInteger();
    private final List<Thread> threads = new ArrayList<>();

    /** Written and read only by the writer (caller) thread. */
    private long committedOffset;

    Stages(
        FileSource source,
        SourceOffset checkpointTemplate,
        IngestProgress progress,
        AtomicBoolean cancel) {
      this.source = source;
      this.sourceId = checkpointTemplate.sourceId();
      this.checkpointTemplate = checkpointTemplate;
      this.committedOffset = checkpointTemplate.byteOffset();
      this.progress = progress;
      this.cancel = cancel;
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
      thread.start();
      return thread;
    }

    private void read() {
      try {
        for (RawBatch batch = source.nextBatch(); batch != null; batch = source.nextBatch()) {
          if (!handOff(raw, batch)) {
            return;
          }
          progress.read(batch.endOffset(), batch.truncatedLines());
        }
        handOff(raw, END_OF_RAW);
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
        List<LogRecord> records = new ArrayList<>(batch.lines().size());
        int rejected = 0;
        for (String line : batch.lines()) {
          LogRecord record = parser.parse(line);
          if (record != null) {
            records.add(record);
          } else {
            rejected++;
            sampleRejected(line, batch);
          }
        }
        progress.parsed(records.size(), rejected);
        LogBatch out =
            new LogBatch(
                batch.sourceId(),
                batch.segmentIndex(),
                batch.sequence(),
                records,
                batch.startOffset(),
                batch.endOffset(),
                batch.lastInSegment(),
                rejected);
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
            segment = sink.open(sourceId, checkpointTemplate.generation(), batch.startOffset());
          }
          segment.write(batch);
          if (batch.lastInSegment()) {
            String location = segment.commit(batch.endOffset());
            segment = null;
            checkpoints.save(checkpointAt(batch.endOffset()));
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
      for (Thread thread : threads) {
        thread.join();
      }
      try {
        source.close();
      } catch (Exception e) {
        log.debug("Ignoring close failure for source {}", sourceId, e);
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

    private void sampleRejected(String line, RawBatch batch) {
      if (rejectedSamples.incrementAndGet() <= REJECTED_SAMPLES_LOGGED) {
        log.warn(
            "Unparseable line source={} batchStartOffset={}: {}",
            sourceId,
            batch.startOffset(),
            line.length() > SAMPLE_CHARS ? line.substring(0, SAMPLE_CHARS) + "…" : line);
      }
    }
  }
}
