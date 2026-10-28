package dev.dinesh.argus.archive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.github.luben.zstd.ZstdIOException;
import com.github.luben.zstd.ZstdInputStream;
import dev.dinesh.argus.archive.ArchiveIndex.Entry;
import dev.dinesh.argus.archive.WalSegments.Segment;
import dev.dinesh.argus.config.ArchiveProperties;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.sink.EventTimePartition;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Drains the WAL into the archive (P2 D1): converts each committed NDJSON segment to Parquet,
 * uploads it to the {@link ObjectStore} under offset-derived keys, and only then deletes it from
 * the WAL (or marks it archived). A crash at any point leaves the segment in the WAL, and the next
 * upload goes to the same keys, so the archive gets every segment at least once and never two
 * copies. When a replay replaces an archived segment, the {@link ArchiveIndex} names the earlier
 * copy's objects so they can be deleted.
 *
 * <p>A segment becomes one object per {@code dt}/{@code hour} run of lines: a new object starts
 * when a line's hour differs from the current object's, so a query filtering on {@code hour} finds
 * every row of that hour. Lines without an event time stay in the current object. Data that jumps
 * between hours is split into at most {@value #MAX_OBJECTS_PER_SEGMENT} objects; the rest of the
 * segment then stays in the last one.
 *
 * <p>One worker thread, one segment at a time, oldest first: memory is one Parquet row group, disk
 * one segment's Parquet files. When the store or the local disk fails, the segment stays in the WAL
 * (the retry queue) and the next pass waits with exponential backoff (P2 D4). A segment that isn't
 * valid NDJSON is renamed {@code .unreadable} and not retried.
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
    /** Not valid NDJSON (or zstd); renamed {@code .unreadable}, kept on disk and not retried. */
    UNREADABLE,
    /** The store, the local disk or the code failed; the pass stops and backs off. */
    FAILED
  }

  /** Counts for one pass over the WAL. */
  public record Pass(int archived, int skipped, int unreadable, int failed) {
    boolean clean() {
      return failed == 0;
    }
  }

  /** Bounds the objects a segment of out-of-order data is split into. */
  static final int MAX_OBJECTS_PER_SEGMENT = 24;

  private static final int MAX_BACKOFF_DOUBLINGS = 30;
  private static final int READ_BUFFER = 256 * 1024;
  private static final long STOP_TIMEOUT_SECONDS = 30;

  private final ObjectStore store;
  private final WalSegments wal;
  private final ArchiveIndex index;
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
    this.index = new ArchiveIndex(wal.root(), mapper);
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

  /**
   * One pass, then schedules the next. Catches {@link Throwable}: an {@link Error} (a native
   * library that won't load, an OOM) must not end the loop silently while {@link #isRunning} still
   * says true; it backs off and retries like any other failure.
   */
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
    } catch (Throwable t) {
      log.error("Archive pass failed", t);
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

  /** Archives every pending segment, oldest first; stops at the first failure. */
  public Pass runOnce() throws IOException {
    int archived = 0;
    int skipped = 0;
    int unreadable = 0;
    for (Segment segment : wal.pending()) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      switch (archive(segment)) {
        case ARCHIVED -> archived++;
        case EMPTY, SUPERSEDED -> skipped++;
        case UNREADABLE -> unreadable++;
        case FAILED -> {
          return new Pass(archived, skipped, unreadable, 1);
        }
      }
    }
    return new Pass(archived, skipped, unreadable, 0);
  }

  /**
   * Backoff with jitter: {@code retry-initial × 2^(n-1)}, capped at {@code retry-max}, then a
   * random wait between 50% and 100% of that.
   */
  Duration backoff(int failures) {
    long initial = props.retryInitial().toMillis();
    long max = props.retryMax().toMillis();
    int doublings = Math.min(Math.max(failures - 1, 0), MAX_BACKOFF_DOUBLINGS);
    long base = initial > (max >> doublings) ? max : Math.min(max, initial << doublings);
    long half = Math.max(base / 2, 1);
    return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
  }

  /** One object of a segment, staged locally; named once its byte range is known. */
  private static final class Part {
    final Path file;
    String key;
    long rows;

    Part(Path file) {
      this.file = file;
    }
  }

  Outcome archive(Segment segment) {
    long started = System.nanoTime();
    List<Part> parts = new ArrayList<>();
    Outcome outcome = null;
    Throwable error = null;
    long walBytes = 0;
    long convertNanos = 0;
    try {
      try {
        walBytes = Files.size(segment.file());
        convert(segment, parts);
        convertNanos = System.nanoTime() - started;
      } catch (JsonProcessingException | ZstdIOException e) {
        // The data itself: not valid NDJSON, or not a valid zstd stream.
        error = e;
        outcome = wal.exists(segment) ? quarantine(segment) : Outcome.SUPERSEDED;
        return outcome;
      } catch (IOException | RuntimeException e) {
        // Not the data's syntax: the staging disk (full, missing), the WAL file vanishing, or a
        // bug. Retried after a backoff rather than set aside.
        error = e;
        outcome = wal.exists(segment) ? Outcome.FAILED : Outcome.SUPERSEDED;
        return outcome;
      }
      try {
        outcome = parts.isEmpty() ? finishEmpty(segment) : upload(segment, parts);
        return outcome;
      } catch (IOException | RuntimeException e) {
        error = e;
        outcome = Outcome.FAILED;
        return outcome;
      }
    } catch (Error e) {
      error = e;
      outcome = Outcome.FAILED;
      throw e;
    } finally {
      long parquetBytes = 0;
      long rows = 0;
      for (Part part : parts) {
        rows += part.rows;
        try {
          if (Files.exists(part.file)) {
            parquetBytes += Files.size(part.file);
            Files.delete(part.file);
          }
        } catch (IOException e) {
          log.warn("Could not delete staging file {}; it is removed at next start", part.file, e);
        }
      }
      long totalNanos = System.nanoTime() - started;
      // Canonical line: one per segment, whatever the outcome. compression is against the source
      // bytes the segment covers, so it doesn't depend on whether the WAL is compressed.
      long sourceBytes = segment.endOffset() - segment.startOffset();
      log.info(
          "Archive segment finished tenant={} source={} generation={} startOffset={}"
              + " endOffset={} outcome={} objects={} keys={} rows={} sourceBytes={} walBytes={}"
              + " parquetBytes={} compression={} convertMs={} uploadMs={} error={}",
          segment.tenantId(),
          segment.sourceId(),
          segment.generation(),
          segment.startOffset(),
          segment.endOffset(),
          outcome,
          parts.size(),
          parts.stream().map(p -> p.key).toList(),
          rows,
          sourceBytes,
          walBytes,
          parquetBytes,
          parquetBytes > 0 ? String.format("%.1f", (double) sourceBytes / parquetBytes) : null,
          TimeUnit.NANOSECONDS.toMillis(convertNanos),
          convertNanos > 0 ? TimeUnit.NANOSECONDS.toMillis(totalNanos - convertNanos) : 0,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage());
    }
  }

  /**
   * Streams the segment into staged Parquet files, one per {@code dt}/{@code hour} run of lines.
   * Each part covers the bytes from its first line to the next part's first line, so the parts'
   * ranges tile {@code [startOffset, endOffset)}.
   */
  private void convert(Segment segment, List<Part> parts) throws IOException {
    EnvelopeParquetWriter writer = null;
    Part part = null;
    long partStart = segment.startOffset();
    // The part's dt/hour, decided by its first line with an event time.
    String partition = null;
    Instant partitionTime = null;
    try (InputStream in = open(segment);
        MappingIterator<LogEnvelope> records = envelopes.readValues(in)) {
      while (records.hasNextValue()) {
        LogEnvelope record = records.nextValue();
        Instant time = EnvelopeParquetWriter.archivedTimestamp(record);
        String recordPartition = time == null ? null : EventTimePartition.dayAndHour(time);
        if (part != null
            && partition != null
            && recordPartition != null
            && !recordPartition.equals(partition)
            && parts.size() < MAX_OBJECTS_PER_SEGMENT) {
          long next = record.source().offset();
          writer.close();
          writer = null;
          part.key = segment.archiveKey(partitionTime, partStart, next);
          part = null;
          partStart = next;
          partition = null;
          partitionTime = null;
        }
        if (part == null) {
          part = new Part(props.stagingDir().resolve(UUID.randomUUID() + ".parquet"));
          parts.add(part);
          writer = EnvelopeParquetWriter.open(part.file, props.rowGroupSize().toBytes(), mapper);
        }
        if (partition == null && recordPartition != null) {
          partition = recordPartition;
          partitionTime = time;
        }
        writer.write(record);
        part.rows++;
      }
      if (part != null) {
        writer.close();
        writer = null;
        part.key = segment.archiveKey(partitionTime, partStart, segment.endOffset());
      }
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException | RuntimeException e) {
          log.debug("Ignoring close failure of an abandoned staging file", e);
        }
      }
    }
  }

  private static InputStream open(Segment segment) throws IOException {
    InputStream file = new BufferedInputStream(Files.newInputStream(segment.file()), READ_BUFFER);
    return segment.compressed() ? new ZstdInputStream(file) : file;
  }

  private Outcome upload(Segment segment, List<Part> parts) throws IOException {
    List<String> keys = parts.stream().map(p -> props.prefix() + p.key).toList();
    Entry previous = index.load(segment);
    // Only the latest segment of a source can still be replayed; an older one (a later segment
    // is already archived) needs no index entry.
    boolean latest = previous == null || previous.startOffset() <= segment.startOffset();
    List<String> earlierKeys =
        previous != null && previous.startOffset() == segment.startOffset()
            ? previous.keys()
            : List.of();
    if (latest) {
      // Recorded before the upload: after a crash during it, the index still names every key
      // that may exist, for the replacement to clean up.
      Set<String> mayExist = new LinkedHashSet<>(keys);
      mayExist.addAll(earlierKeys);
      index.save(segment, new Entry(segment.startOffset(), List.copyOf(mayExist)));
    }
    for (Part part : parts) {
      store.put(props.prefix() + part.key, part.file);
    }
    for (String earlier : earlierKeys) {
      if (!keys.contains(earlier)) {
        store.delete(earlier);
      }
    }
    if (!wal.exists(segment)) {
      // A replay replaced the segment during the upload. Its keys stay in the index, so the
      // replacement's upload deletes those it doesn't overwrite.
      return Outcome.SUPERSEDED;
    }
    if (latest) {
      index.save(segment, new Entry(segment.startOffset(), keys));
    }
    finish(segment);
    return Outcome.ARCHIVED;
  }

  private Outcome finishEmpty(Segment segment) throws IOException {
    finish(segment);
    return Outcome.EMPTY;
  }

  private Outcome quarantine(Segment segment) {
    try {
      Path kept = wal.quarantine(segment);
      log.error(
          "WAL segment {} is not valid NDJSON; kept as {} and not archived. Inspect it, and"
              + " rename it back to retry.",
          segment.file(),
          kept);
      return Outcome.UNREADABLE;
    } catch (IOException e) {
      log.error("Could not set aside unreadable segment {}", segment.file(), e);
      return Outcome.FAILED;
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
