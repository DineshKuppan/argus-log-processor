package dev.dinesh.argus.hot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.luben.zstd.ZstdIOException;
import com.github.luben.zstd.ZstdInputStream;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.utils.Backoff;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSegments.Segment;
import dev.dinesh.argus.wal.WalSink;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Indexes committed WAL segments into the hot tier ({@code P5_PLAN.md} D2): one thread, oldest
 * segment first, each line a {@code _bulk} {@code index} action with {@code _id = eventId} ({@link
 * HotBulkWriter}), so a retry, a re-run after a crash or a superseded segment's replacement
 * overwrites rather than duplicates. A segment is acknowledged ({@link WalSink#HOT}) once each of
 * its lines was indexed, rejected by the engine (permanent; the cold tier keeps it) or older than
 * the retention. Any other failure stops the pass and backs off, like the {@code Archiver}; within
 * the same process a failed segment resumes at its last acknowledged bulk.
 */
@Slf4j
public class HotIndexer implements SmartLifecycle {

  /** What happened to one segment. */
  public enum Outcome {
    INDEXED,
    /** Replaced in the WAL by a replay while it was indexed; the replacement goes next. */
    SUPERSEDED,
    /** Not valid NDJSON (or zstd); set aside, as the archiver does. */
    UNREADABLE,
    FAILED
  }

  /** Item retries within a segment for 429 / 5xx items, waiting 1s, 2s, 4s. */
  static final int ITEM_ATTEMPTS = HotBulkWriter.ITEM_ATTEMPTS;

  private static final int READ_BUFFER = 64 * 1024;
  private static final long STOP_TIMEOUT_SECONDS = 30;

  private final HotBulkWriter writer;
  private final WalSegments wal;
  private final HotProperties props;
  private final ObjectReader envelopes;
  private final ArgusMetrics metrics;
  private final Clock clock;

  private volatile boolean running;
  private ScheduledExecutorService worker;

  // Read and written only by the worker thread.
  private int consecutiveFailures;
  private Path resumeFile;
  private long resumeLines;

  public HotIndexer(
      SearchEngineClient client,
      WalSegments wal,
      HotIndices indices,
      HotProperties props,
      ArgusMetrics metrics) {
    this(client, wal, indices, props, metrics, Clock.systemUTC(), Duration.ofSeconds(1));
  }

  HotIndexer(
      SearchEngineClient client,
      WalSegments wal,
      HotIndices indices,
      HotProperties props,
      ArgusMetrics metrics,
      Clock clock,
      Duration itemRetryBase) {
    this.writer = new HotBulkWriter(client, indices, props, metrics, clock, itemRetryBase);
    this.wal = wal;
    this.props = props;
    this.envelopes = client.mapper().readerFor(LogEnvelope.class);
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    worker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "hot-indexer");
              t.setDaemon(true);
              return t;
            });
    running = true;
    worker.execute(this::loop);
    log.info(
        "Hot indexer started: {} → {} ({})",
        wal.root(),
        writer.client().base(),
        writer.indices().pattern());
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    worker.shutdownNow();
    try {
      if (!worker.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("Hot indexer did not stop within {}s", STOP_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** One pass, then schedules the next. Catches {@link Throwable}, as the archiver does. */
  private void loop() {
    Duration next;
    try {
      if (runOnce()) {
        consecutiveFailures = 0;
        next = props.scanInterval();
      } else {
        next = backoff(++consecutiveFailures);
      }
    } catch (InterruptedException e) {
      return;
    } catch (Throwable t) {
      log.error("Hot index pass failed", t);
      next = backoff(++consecutiveFailures);
    }
    if (consecutiveFailures > 0) {
      log.warn(
          "Hot index pass incomplete ({} in a row); retrying in {}ms",
          consecutiveFailures,
          next.toMillis());
    }
    if (running) {
      worker.schedule(this::loop, next.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Prepares the cluster if needed, runs retention hourly, then indexes every pending segment,
   * oldest first, stopping at the first failure. True if the pass completed.
   */
  public boolean runOnce() throws IOException, InterruptedException {
    writer.maintain();
    for (Segment segment : wal.pending(WalSink.HOT)) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException();
      }
      if (index(segment) == Outcome.FAILED) {
        return false;
      }
    }
    return true;
  }

  /** Age of the oldest segment not yet indexed, in seconds; 0 when none waits. */
  public double lagSeconds() {
    try {
      List<Segment> pending = wal.pending(WalSink.HOT);
      return pending.isEmpty()
          ? 0
          : Math.max(0, clock.millis() - pending.get(0).modified().toMillis()) / 1e3;
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  Outcome index(Segment segment) throws InterruptedException {
    long start = System.nanoTime();
    HotBulkWriter.Tally tally = new HotBulkWriter.Tally();
    Outcome outcome = Outcome.FAILED;
    Throwable error = null;
    long resumeAt = segment.file().equals(resumeFile) ? resumeLines : 0;
    try {
      outcome = indexLines(segment, resumeAt, tally);
      if (outcome == Outcome.INDEXED) {
        resumeFile = null;
        if (!wal.exists(segment)) {
          outcome = Outcome.SUPERSEDED;
        } else {
          wal.acknowledge(segment, WalSink.HOT);
        }
      }
    } catch (InterruptedException e) {
      throw e;
    } catch (JsonProcessingException | ZstdIOException e) {
      error = e;
      outcome = wal.exists(segment) ? setAside(segment) : Outcome.SUPERSEDED;
    } catch (Exception | Error e) {
      error = e;
      outcome = Outcome.FAILED;
    } finally {
      // Canonical line: one per segment (guardrail 9).
      log.info(
          "Hot segment finished tenant={} source={} generation={} startOffset={} endOffset={}"
              + " outcome={} lines={} resumedAt={} indexed={} rejected={} outsideRetention={}"
              + " bulks={} itemRetries={} firstRejection={} error={} durationMs={}",
          segment.tenantId(),
          segment.sourceId(),
          segment.generation(),
          segment.startOffset(),
          segment.endOffset(),
          outcome,
          tally.lines + resumeAt,
          resumeAt,
          tally.indexed,
          tally.rejected,
          tally.skipped,
          tally.bulks,
          tally.retries,
          tally.firstRejection,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      metrics.hotSegment(
          segment.tenantId(), outcome.name(), tally.indexed, tally.rejected, tally.skipped);
    }
    return outcome;
  }

  private Outcome indexLines(Segment segment, long resumeAt, HotBulkWriter.Tally tally)
      throws IOException, InterruptedException {
    Instant now = clock.instant();
    HotBulkWriter.Bulk bulk = writer.newBulk();
    long lines = 0;
    try (BufferedReader reader = open(segment)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        lines++;
        if (lines <= resumeAt) {
          continue;
        }
        bulk.add(envelopes.readValue(line), now, tally);
        if (bulk.full()) {
          writer.send(bulk, tally);
          remember(segment, lines);
          bulk = writer.newBulk();
        }
      }
    }
    if (!bulk.isEmpty()) {
      writer.send(bulk, tally);
    }
    return Outcome.INDEXED;
  }

  private void remember(Segment segment, long lines) {
    resumeFile = segment.file();
    resumeLines = lines;
  }

  private Outcome setAside(Segment segment) {
    try {
      Path kept = wal.quarantine(segment);
      log.error(
          "WAL segment {} is not valid NDJSON; kept as {} and not indexed or archived. Inspect it,"
              + " and rename it back to retry.",
          segment.file(),
          kept);
      return Outcome.UNREADABLE;
    } catch (IOException e) {
      log.error("Could not set aside unreadable segment {}", segment.file(), e);
      return Outcome.FAILED;
    }
  }

  private static BufferedReader open(Segment segment) throws IOException {
    InputStream file = new BufferedInputStream(Files.newInputStream(segment.file()), READ_BUFFER);
    InputStream in = segment.compressed() ? new ZstdInputStream(file) : file;
    return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), READ_BUFFER);
  }

  /** As the archiver's: {@code retry-initial × 2^(n-1)} capped at {@code retry-max}, jittered. */
  Duration backoff(int failures) {
    return Backoff.jittered(props.retryInitial(), props.retryMax(), failures);
  }
}
