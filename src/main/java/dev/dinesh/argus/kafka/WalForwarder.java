package dev.dinesh.argus.kafka;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.luben.zstd.ZstdInputStream;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.utils.Backoff;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSegments.Segment;
import dev.dinesh.argus.wal.WalSink;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Switching from the local WAL to Kafka ({@code P6_PLAN.md} D6): at startup, sends every local
 * segment some routed sink hasn't acknowledged to the topic as a segment (its lines, then a commit
 * marker), then acknowledges it locally for every sink, which deletes it (or marks it, when
 * segments are kept). A crash midway sends a segment again whole; the sinks' idempotency absorbs
 * that. Retries with backoff until the cluster answers; one canonical line per segment.
 */
@Slf4j
public final class WalForwarder implements SmartLifecycle {

  private static final int BATCH_LINES = 1000;
  private static final int READ_BUFFER = 256 * 1024;
  private static final long STOP_TIMEOUT_SECONDS = 30;

  private final WalSegments local;
  private final LogSink kafka;
  private final ObjectReader envelopes;
  private final Duration retryInitial;
  private final Duration retryMax;

  private volatile boolean running;
  private volatile boolean done;
  private Thread worker;

  public WalForwarder(
      WalSegments local,
      LogSink kafka,
      ObjectMapper mapper,
      Duration retryInitial,
      Duration retryMax) {
    this.local = local;
    this.kafka = kafka;
    this.envelopes = mapper.readerFor(LogEnvelope.class);
    this.retryInitial = retryInitial;
    this.retryMax = retryMax;
  }

  @Override
  public synchronized void start() {
    if (running || done) {
      return;
    }
    running = true;
    worker = new Thread(this::run, "wal-forwarder");
    worker.setDaemon(true);
    worker.start();
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (worker != null) {
      worker.interrupt();
      try {
        worker.join(TimeUnit.SECONDS.toMillis(STOP_TIMEOUT_SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** True once every pending local segment is in Kafka. */
  public boolean done() {
    return done;
  }

  private void run() {
    int failures = 0;
    while (running && !done) {
      try {
        forwardAll();
        done = true;
      } catch (InterruptedException e) {
        return;
      } catch (Exception e) {
        Duration wait = Backoff.jittered(retryInitial, retryMax, ++failures);
        log.warn(
            "Forwarding the local WAL to Kafka failed ({} in a row); retrying in {}ms: {}",
            failures,
            wait.toMillis(),
            e.toString());
        try {
          Thread.sleep(wait.toMillis());
        } catch (InterruptedException ie) {
          return;
        }
      }
    }
    running = false;
  }

  /** Forwards every pending local segment, oldest first. */
  public int forwardAll() throws IOException, InterruptedException {
    List<Segment> pending = local.pending();
    if (pending.isEmpty()) {
      return 0;
    }
    log.info(
        "Forwarding {} local WAL segments ({} bytes) to Kafka",
        pending.size(),
        WalSegments.bytes(pending));
    int forwarded = 0;
    for (Segment segment : pending) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException();
      }
      if (local.exists(segment)) {
        forward(segment);
        forwarded++;
      }
    }
    return forwarded;
  }

  private void forward(Segment segment) throws IOException {
    long start = System.nanoTime();
    long lines = 0;
    String location = null;
    String outcome = "failed";
    LogSink.SegmentWriter writer =
        kafka.open(
            segment.tenantId(), segment.sourceId(), segment.generation(), segment.startOffset());
    try (InputStream in = open(segment);
        MappingIterator<LogEnvelope> records = envelopes.readValues(in)) {
      List<LogEnvelope> batch = new ArrayList<>(BATCH_LINES);
      long sequence = 0;
      while (records.hasNextValue()) {
        batch.add(records.nextValue());
        lines++;
        if (batch.size() == BATCH_LINES) {
          writer.write(batch(segment, sequence++, batch));
          batch = new ArrayList<>(BATCH_LINES);
        }
      }
      if (!batch.isEmpty()) {
        writer.write(batch(segment, sequence, batch));
      }
      location = writer.commit(segment.endOffset());
      for (WalSink sink : local.routed()) {
        local.acknowledge(segment, sink);
      }
      outcome = "forwarded";
    } catch (IOException | RuntimeException e) {
      writer.abort();
      throw e;
    } finally {
      log.info(
          "WAL segment forwarded tenant={} source={} generation={} startOffset={} endOffset={}"
              + " outcome={} lines={} location={} durationMs={}",
          segment.tenantId(),
          segment.sourceId(),
          segment.generation(),
          segment.startOffset(),
          segment.endOffset(),
          outcome,
          lines,
          location,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
  }

  private static LogBatch batch(Segment segment, long sequence, List<LogEnvelope> records) {
    return new LogBatch(
        segment.sourceId(),
        0,
        sequence,
        records,
        segment.startOffset(),
        segment.endOffset(),
        false,
        0,
        0,
        0);
  }

  private static InputStream open(Segment segment) throws IOException {
    InputStream file = new BufferedInputStream(Files.newInputStream(segment.file()), READ_BUFFER);
    return segment.compressed() ? new ZstdInputStream(file) : file;
  }
}
