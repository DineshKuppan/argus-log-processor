package dev.dinesh.argus.ingest;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.checkpoint.ResumePoint;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.LineParser;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.sink.LogSink.SegmentWriter;
import dev.dinesh.argus.source.FileSource;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.source.RawBatch;
import dev.dinesh.argus.source.SourceOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    Path path = request.path();
    sink.discardUncommitted(sourceId);
    String fileKey = FileSource.fileKey(path);
    ResumePoint resume =
        ResumePoint.resolve(
            checkpoints.load(sourceId), fileKey, Files.size(path), request.fromBeginning());
    if (resume.reason() == ResumePoint.Reason.FILE_REPLACED
        || resume.reason() == ResumePoint.Reason.FILE_TRUNCATED) {
      log.warn("Source {} ({}): {}; restarting from offset 0", sourceId, path, resume.reason());
    }

    FileSource source = FileSource.open(path, sourceId, resume.offset(), props);
    progress.started(resume.offset(), source.limit(), resume.reason().name());
    log.info(
        "Ingest start source={} path={} offset={} size={} reason={}",
        sourceId,
        path,
        resume.offset(),
        source.limit(),
        resume.reason());

    Stages stages = new Stages(source, sourceId, path.toString(), fileKey, progress, cancel);
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
    Outcome outcome = cancel.get() ? Outcome.CANCELLED : Outcome.COMPLETED;
    log.info(
        "Ingest {} source={} committedOffset={} segments={} lines={} rejected={}",
        outcome,
        sourceId,
        progress.committedOffset(),
        progress.segmentsCommitted(),
        progress.lines(),
        progress.rejected());
    return outcome;
  }

  /** State shared by the three stages of one run. */
  private final class Stages {

    private final FileSource source;
    private final String sourceId;
    private final String path;
    private final String fileKey;
    private final IngestProgress progress;
    private final AtomicBoolean cancel;
    private final BlockingQueue<RawBatch> raw;
    private final BlockingQueue<LogBatch> parsed;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger rejectedSamples = new AtomicInteger();
    private final List<Thread> threads = new ArrayList<>();

    Stages(
        FileSource source,
        String sourceId,
        String path,
        String fileKey,
        IngestProgress progress,
        AtomicBoolean cancel) {
      this.source = source;
      this.sourceId = sourceId;
      this.path = path;
      this.fileKey = fileKey;
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
            segment = sink.open(sourceId, batch.startOffset());
          }
          segment.write(batch);
          if (batch.lastInSegment()) {
            String location = segment.commit(batch.endOffset());
            segment = null;
            checkpoints.save(new SourceOffset(sourceId, path, fileKey, batch.endOffset()));
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
