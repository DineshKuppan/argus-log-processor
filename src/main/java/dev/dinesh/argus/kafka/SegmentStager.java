package dev.dinesh.argus.kafka;

import com.github.luben.zstd.ZstdOutputStream;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.utils.DurableFiles;
import dev.dinesh.argus.wal.WalSegments.Segment;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Headers;

/**
 * Reassembles WAL segments from the topic ({@code P6_PLAN.md} D3). A partition interleaves the
 * segments of many sources; each line goes to its segment's staging file, and the commit marker
 * turns the file into a {@link Segment} laid out as {@code LocalVolumeSink} lays out the local WAL
 * ({@code root/tenant=T/dt=kafka/source=S/gen=G/START-END.ndjson.zst}), so the P2 archiver takes it
 * unchanged.
 *
 * <p>A line whose source offset isn't past its staged segment's last one is a replay of the segment
 * (its producer crashed before the checkpoint): the staged copy is discarded and staging restarts.
 * A copy whose first record comes after the segment's first line (the marker's {@code argus-first})
 * was staged from part-way through: reading resumed at a committed offset inside a segment that was
 * handled before, so it is dropped rather than archived over the complete copy. Owned by the
 * archive consumer's thread.
 */
@Slf4j
final class SegmentStager {

  /** One segment of a source, as its records name it. */
  record Key(String tenantId, String sourceId, int generation, long startOffset) {}

  /** A segment whose commit marker arrived: ready to archive. */
  record Ready(int partition, long firstOffset, Segment segment) {}

  /** What a record did. */
  enum Result {
    STAGED,
    /** Its segment's commit marker: {@link #takeReady} has the segment. */
    COMMITTED,
    ABORTED,
    /** A commit marker for a copy staged from part-way through its segment: dropped. */
    PARTIAL,
    /** A marker for a segment not staged here (empty, or dropped as an orphan). */
    UNKNOWN,
    /** Not a WAL record (bad key or headers): skipped. */
    INVALID
  }

  private static final String STAGING_SUFFIX = ".staging";
  private static final int ZSTD_LEVEL = 1;
  private static final int WRITE_BUFFER = 64 * 1024;

  private static final class Staged {
    final Key key;
    final int partition;
    final long firstOffset;
    final Path file;
    final OutputStream out;
    long lastSourceOffset = Long.MIN_VALUE;
    long lines;
    Instant lastRecordAt;

    Staged(Key key, int partition, long firstOffset, Path file) throws IOException {
      this.key = key;
      this.partition = partition;
      this.firstOffset = firstOffset;
      this.file = file;
      this.out =
          new BufferedOutputStream(
              new ZstdOutputStream(Files.newOutputStream(file), ZSTD_LEVEL).setChecksum(true),
              WRITE_BUFFER);
    }

    void close() {
      try {
        out.close();
      } catch (IOException e) {
        log.debug("Ignoring close failure of {}", file, e);
      }
    }
  }

  private final Path root;
  private final Clock clock;
  private final Map<Key, Staged> staged = new HashMap<>();
  private Ready ready;

  SegmentStager(Path root, Clock clock) {
    this.root = root.toAbsolutePath().normalize();
    this.clock = clock;
  }

  Path root() {
    return root;
  }

  /**
   * Deletes staging and ready files a previous run left (their offsets were never committed, so
   * Kafka delivers them again); keeps the archive index ({@code _archive}) and set-aside segments.
   */
  void clear() throws IOException {
    Files.createDirectories(root);
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        String name = file.getFileName().toString();
        if (Files.isRegularFile(file)
            && (name.endsWith(STAGING_SUFFIX)
                || name.endsWith(".ndjson" + LocalVolumeSink.ZSTD_SUFFIX))) {
          Files.deleteIfExists(file);
        }
      }
    }
  }

  /**
   * Takes one record of {@code partition} at {@code offset}.
   *
   * @throws IOException the staging disk failed
   */
  Result accept(int partition, long offset, byte[] keyBytes, Headers headers, byte[] value)
      throws IOException {
    Key key = key(keyBytes, headers);
    if (key == null) {
      return Result.INVALID;
    }
    String type = KafkaWal.header(headers, KafkaWal.TYPE);
    if (type != null) {
      Staged s = staged.remove(key);
      if (s == null) {
        return Result.UNKNOWN;
      }
      if (KafkaWal.ABORT.equals(type)) {
        discard(s);
        return Result.ABORTED;
      }
      return commit(s, headers);
    }
    Long sourceOffset = number(KafkaWal.header(headers, KafkaWal.OFFSET));
    if (value == null || sourceOffset == null) {
      return Result.INVALID;
    }
    Staged s = staged.get(key);
    if (s != null && sourceOffset <= s.lastSourceOffset) {
      log.info(
          "Segment {} is being sent again (a replay); restarting its staging at offset {}",
          key,
          offset);
      discard(s);
      staged.remove(key);
      s = null;
    }
    if (s == null) {
      s = new Staged(key, partition, offset, file(key, STAGING_SUFFIX));
      staged.put(key, s);
    }
    s.out.write(value);
    s.out.write('\n');
    s.lastSourceOffset = sourceOffset;
    s.lines++;
    s.lastRecordAt = clock.instant();
    return Result.STAGED;
  }

  /** The segment the last {@link Result#COMMITTED} record completed. */
  Ready takeReady() {
    Ready r = ready;
    ready = null;
    return r;
  }

  private Result commit(Staged s, Headers headers) throws IOException {
    Long end = number(KafkaWal.header(headers, KafkaWal.END));
    Long lines = number(KafkaWal.header(headers, KafkaWal.LINES));
    Long first = number(KafkaWal.header(headers, KafkaWal.FIRST));
    s.out.close(); // ends the zstd frame
    if (end == null) {
      Files.deleteIfExists(s.file);
      return Result.INVALID;
    }
    if (first != null && first >= 0 && s.firstOffset > first) {
      // Its first line was read before this copy began, and so archived (or dropped) then.
      log.info(
          "Segment {} was staged from Kafka offset {} but starts at {}; dropping the partial copy",
          s.key,
          s.firstOffset,
          first);
      Files.deleteIfExists(s.file);
      return Result.PARTIAL;
    }
    if (lines == null || lines != s.lines) {
      // Archived anyway: the lines are real, and dropping them would lose them from the cold tier.
      log.error(
          "Segment {} committed with {} lines but {} were staged; archiving what arrived",
          s.key,
          lines,
          s.lines);
    }
    Path target = file(s.key, "-" + end + ".ndjson" + LocalVolumeSink.ZSTD_SUFFIX);
    DurableFiles.moveAtomically(s.file, target);
    Segment segment =
        new Segment(
            target,
            s.key.tenantId(),
            s.key.sourceId(),
            s.key.generation(),
            s.key.startOffset(),
            end,
            Files.getLastModifiedTime(target));
    ready = new Ready(s.partition, s.firstOffset, segment);
    return Result.COMMITTED;
  }

  /** The smallest Kafka offset of a segment still staged in {@code partition}. */
  OptionalLong earliest(int partition) {
    return staged.values().stream()
        .filter(s -> s.partition == partition)
        .mapToLong(s -> s.firstOffset)
        .min();
  }

  int open() {
    return staged.size();
  }

  /**
   * Restarts every staged segment's orphan clock: after the consumer held its partitions (archive
   * failing or slow), the silence was its own, not the producers'.
   */
  void touch() {
    Instant now = clock.instant();
    staged.values().forEach(s -> s.lastRecordAt = now);
  }

  /** Discards segments with no record for {@code timeout}; returns them (D3's orphans). */
  List<Key> expire(Duration timeout) {
    Instant cutoff = clock.instant().minus(timeout);
    List<Key> expired = new ArrayList<>();
    for (Iterator<Staged> it = staged.values().iterator(); it.hasNext(); ) {
      Staged s = it.next();
      if (s.lastRecordAt.isBefore(cutoff)) {
        it.remove();
        discard(s);
        expired.add(s.key);
      }
    }
    return expired;
  }

  /** Discards {@code partition}'s staged segments (revoked: its new owner re-reads them). */
  void drop(int partition) {
    for (Iterator<Staged> it = staged.values().iterator(); it.hasNext(); ) {
      Staged s = it.next();
      if (s.partition == partition) {
        it.remove();
        discard(s);
      }
    }
  }

  void dropAll() {
    staged.values().forEach(this::discard);
    staged.clear();
  }

  private void discard(Staged s) {
    s.close();
    try {
      Files.deleteIfExists(s.file);
    } catch (IOException e) {
      log.warn("Could not delete staging file {}; it is removed at next start", s.file, e);
    }
  }

  /**
   * {@code <tenant>/<source>} and {@code <generation>:<start>}, validated as ingest validates them:
   * they become directory names, and the topic may hold records Argus didn't write.
   */
  private static Key key(byte[] keyBytes, Headers headers) {
    String segment = KafkaWal.header(headers, KafkaWal.SEGMENT);
    if (keyBytes == null || segment == null) {
      return null;
    }
    String key = new String(keyBytes, StandardCharsets.UTF_8);
    int slash = key.indexOf('/');
    int colon = segment.indexOf(':');
    if (slash < 0 || colon < 0) {
      return null;
    }
    try {
      String tenant = TenantIds.requireValid(key.substring(0, slash));
      String source = SourceOffset.requireValidSourceId(key.substring(slash + 1));
      int generation = Integer.parseInt(segment.substring(0, colon));
      long start = Long.parseLong(segment.substring(colon + 1));
      return generation < 0 || start < 0 ? null : new Key(tenant, source, generation, start);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Long number(String text) {
    if (text == null) {
      return null;
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Path file(Key key, String suffix) throws IOException {
    Path dir =
        root.resolve(TenantIds.pathSegment(key.tenantId()))
            .resolve("dt=kafka")
            .resolve("source=" + key.sourceId())
            .resolve("gen=" + key.generation());
    Files.createDirectories(dir);
    return dir.resolve(key.startOffset() + suffix);
  }
}
