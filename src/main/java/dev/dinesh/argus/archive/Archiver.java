package dev.dinesh.argus.archive;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.dinesh.argus.archive.WalSegments.Segment;
import dev.dinesh.argus.config.ArchiveProperties;
import dev.dinesh.argus.model.LogEnvelope;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Drains the WAL into the archive (P2 D1): converts each committed NDJSON segment to Parquet,
 * uploads it to the {@link ObjectStore} under an offset-derived key, and only then deletes it from
 * the WAL (or marks it archived). A crash at any point leaves the segment in the WAL, and the next
 * upload goes to the same key, so the archive gets every segment at least once and never two
 * copies.
 *
 * <p>One worker thread, one segment at a time, oldest first: memory is one Parquet row group, disk
 * one staging file. When the store fails, the segment stays in the WAL (which is the retry queue)
 * and the next pass waits with exponential backoff (P2 D4).
 */
@Slf4j
public class Archiver implements SmartLifecycle {

  /** What happened to one segment. */
  public enum Outcome {
    /** Uploaded, then deleted from the WAL or marked. */
    ARCHIVED,
    /** Held no records; removed from the WAL without an upload. */
    EMPTY,
    /** Replaced in the WAL by a replay while it was being archived; the replacement goes next. */
    SUPERSEDED,
    /** Couldn't be read or converted; skipped for this pass, other segments go ahead. */
    UNREADABLE,
    /** The store (or the WAL volume) failed; the pass stops and backs off. */
    FAILED
  }

  /** Counts for one pass over the WAL. */
  public record Pass(int archived, int skipped, int failed) {
    boolean clean() {
      return failed == 0;
    }
  }

  private static final int MAX_BACKOFF_DOUBLINGS = 30;
  private static final long STOP_TIMEOUT_SECONDS = 30;

  private final ObjectStore store;
  private final WalSegments wal;
  private final ArchiveProperties props;
  private final ObjectMapper mapper;
  private final ObjectReader envelopes;

  private volatile boolean running;
  private ScheduledExecutorService worker;

  /** Failed passes in a row; read and written only by the worker thread. */
  private int consecutiveFailures;

  public Archiver(
      ObjectStore store, WalSegments wal, ArchiveProperties props, ObjectMapper mapper) {
    this.store = store;
    this.wal = wal;
    this.props = props;
    this.mapper = mapper;
    this.envelopes = mapper.readerFor(LogEnvelope.class);
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    try {
      cleanStaging();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot prepare " + props.stagingDir(), e);
    }
    worker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "archiver");
              thread.setDaemon(true);
              return thread;
            });
    running = true;
    log.info(
        "Archiver started: {} → {}{} (deleteAfterUpload={})",
        wal.root(),
        store.describe(),
        props.prefix().isEmpty() ? "" : "/" + props.prefix(),
        props.deleteAfterUpload());
    worker.execute(this::loop);
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    // Interrupts an upload in flight: the segment stays in the WAL and is uploaded after restart.
    worker.shutdownNow();
    try {
      if (!worker.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("Archiver did not stop within {}s", STOP_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void loop() {
    Duration next;
    try {
      Pass pass = runOnce();
      if (pass.clean()) {
        consecutiveFailures = 0;
        next = props.scanInterval();
      } else {
        next = backoff(++consecutiveFailures);
      }
    } catch (Exception e) {
      log.error("Archive pass failed", e);
      next = backoff(++consecutiveFailures);
    }
    if (consecutiveFailures > 0) {
      log.warn(
          "Archive pass incomplete ({} in a row); retrying in {}ms",
          consecutiveFailures,
          next.toMillis());
    }
    if (running) {
      worker.schedule(this::loop, next.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /** Archives every pending segment, oldest first; stops at the first store failure. */
  public Pass runOnce() throws IOException {
    int archived = 0;
    int skipped = 0;
    int failed = 0;
    for (Segment segment : wal.pending()) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      Outcome outcome = archive(segment);
      switch (outcome) {
        case ARCHIVED -> archived++;
        case EMPTY, SUPERSEDED -> skipped++;
        case UNREADABLE -> failed++;
        case FAILED -> {
          return new Pass(archived, skipped, failed + 1);
        }
      }
    }
    return new Pass(archived, skipped, failed);
  }

  /** Backoff with jitter: {@code retry-initial × 2^(n-1)}, capped at {@code retry-max}, ±50%. */
  Duration backoff(int failures) {
    long initial = props.retryInitial().toMillis();
    long max = props.retryMax().toMillis();
    int doublings = Math.min(Math.max(failures - 1, 0), MAX_BACKOFF_DOUBLINGS);
    long base = initial > (max >> doublings) ? max : Math.min(max, initial << doublings);
    long half = Math.max(base / 2, 1);
    return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
  }

  Outcome archive(Segment segment) {
    long started = System.nanoTime();
    Path staged = props.stagingDir().resolve(UUID.randomUUID() + ".parquet");
    Outcome outcome = null;
    Throwable error = null;
    String key = null;
    long rows = 0;
    long walBytes = 0;
    long parquetBytes = 0;
    long convertNanos = 0;
    try {
      Instant firstTimestamp;
      try {
        walBytes = Files.size(segment.file());
        try (EnvelopeParquetWriter writer =
                EnvelopeParquetWriter.open(staged, props.rowGroupSize().toBytes(), mapper);
            MappingIterator<LogEnvelope> records = envelopes.readValues(segment.file().toFile())) {
          while (records.hasNextValue()) {
            writer.write(records.nextValue());
          }
          rows = writer.rows();
          firstTimestamp = writer.firstTimestamp();
        }
        parquetBytes = Files.size(staged);
        convertNanos = System.nanoTime() - started;
      } catch (IOException | RuntimeException e) {
        error = e;
        outcome = wal.exists(segment) ? Outcome.UNREADABLE : Outcome.SUPERSEDED;
        return outcome;
      }
      try {
        if (rows == 0) {
          finish(segment);
          outcome = Outcome.EMPTY;
          return outcome;
        }
        key = props.prefix() + segment.archiveKey(firstTimestamp);
        store.put(key, staged);
        for (String earlier :
            store.list(props.prefix() + segment.supersededPrefix(firstTimestamp))) {
          if (!earlier.equals(key)) {
            store.delete(earlier);
          }
        }
        if (!wal.exists(segment)) {
          // A replay replaced the segment during the upload: this copy is stale.
          store.delete(key);
          outcome = Outcome.SUPERSEDED;
          return outcome;
        }
        finish(segment);
        outcome = Outcome.ARCHIVED;
        return outcome;
      } catch (IOException | RuntimeException e) {
        error = e;
        outcome = Outcome.FAILED;
        return outcome;
      }
    } finally {
      try {
        Files.deleteIfExists(staged);
      } catch (IOException e) {
        log.warn("Could not delete staging file {}; it is removed at the next start", staged, e);
      }
      long totalNanos = System.nanoTime() - started;
      // Canonical line: one per segment, whatever the outcome.
      log.info(
          "Archive segment finished tenant={} source={} generation={} startOffset={}"
              + " endOffset={} outcome={} key={} rows={} walBytes={} parquetBytes={}"
              + " compression={} convertMs={} uploadMs={} error={}",
          segment.tenantId(),
          segment.sourceId(),
          segment.generation(),
          segment.startOffset(),
          segment.endOffset(),
          outcome,
          key,
          rows,
          walBytes,
          parquetBytes,
          parquetBytes > 0 ? String.format("%.1f", (double) walBytes / parquetBytes) : null,
          TimeUnit.NANOSECONDS.toMillis(convertNanos),
          convertNanos > 0 ? TimeUnit.NANOSECONDS.toMillis(totalNanos - convertNanos) : 0,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage());
    }
  }

  private void finish(Segment segment) throws IOException {
    if (props.deleteAfterUpload()) {
      wal.delete(segment);
    } else {
      wal.markArchived(segment);
    }
  }

  /** A crash can leave staging files behind; nothing else uses them. */
  private void cleanStaging() throws IOException {
    Path staging = Files.createDirectories(props.stagingDir());
    try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(staging, "*.parquet")) {
      for (Path leftover : leftovers) {
        Files.deleteIfExists(leftover);
      }
    }
  }
}
