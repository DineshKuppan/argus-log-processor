package dev.dinesh.argus.wal;

import dev.dinesh.argus.sink.EventTimePartition;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.utils.DurableFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * The committed segments {@link LocalVolumeSink} wrote, read as the WAL its routed sinks drain
 * ({@code P5_PLAN.md} D1, ADR-7). Only committed {@code .ndjson} and {@code .ndjson.zst} files are
 * listed: they appear by atomic rename, so a listed segment is always complete. Uncommitted output
 * under {@code _staging} never matches.
 *
 * <p>Each {@link WalSink} acknowledges a segment with its marker; the last acknowledgement deletes
 * the segment and its markers, unless segments are kept ({@code delete-after-upload=false}, or no
 * archive store: the segments are then the cold output). One instance per WAL volume, shared by its
 * sinks: deletion is decided under its lock.
 */
@Slf4j
public final class WalSegments {

  /**
   * A segment that can't be parsed is renamed with this suffix: kept for inspection, not retried.
   */
  public static final String UNREADABLE_SUFFIX = ".unreadable";

  /** {@code tenant=T/dt=D/source=S/gen=G/START-END.ndjson}, as LocalVolumeSink names segments. */
  private static final Pattern SEGMENT =
      Pattern.compile(
          "tenant=([^/]+)/dt=[^/]+/source=([^/]+)/gen=(\\d+)/(\\d+)-(\\d+)\\.ndjson(\\.zst)?");

  private static final int SEGMENT_DEPTH = 5;

  /** Walks of the WAL tried before {@link #pending} settles for one that raced a delete. */
  static final int SCAN_ATTEMPTS = 3;

  private final Path root;
  private final Set<WalSink> routed;
  private final boolean deleteAcknowledged;
  private final String instance;

  /** The archive as the only sink, deleting archived segments: the P2 setup. */
  public WalSegments(Path root) {
    this(root, Set.of(WalSink.ARCHIVE), true);
  }

  /**
   * @param routed the sinks that consume this WAL; at least one
   * @param deleteAcknowledged delete a segment once every routed sink has acknowledged it
   */
  public WalSegments(Path root, Set<WalSink> routed, boolean deleteAcknowledged) {
    this(root, routed, deleteAcknowledged, null);
  }

  /**
   * @param instance {@code argus.instance.id} of the instance that wrote this WAL, or null (P6 D8):
   *     a WAL volume belongs to one instance, so its segments carry that instance
   */
  public WalSegments(Path root, Set<WalSink> routed, boolean deleteAcknowledged, String instance) {
    this.instance = instance;
    if (routed.isEmpty()) {
      throw new IllegalArgumentException("A WAL needs at least one sink");
    }
    this.root = root.toAbsolutePath().normalize();
    this.routed = Set.copyOf(routed);
    this.deleteAcknowledged = deleteAcknowledged;
  }

  public Path root() {
    return root;
  }

  public Set<WalSink> routed() {
    return routed;
  }

  /**
   * A committed segment of one tenant's source; zstd-compressed if its name ends in {@code .zst}.
   */
  public record Segment(
      Path file,
      String tenantId,
      String sourceId,
      int generation,
      long startOffset,
      long endOffset,
      FileTime modified,
      String instance) {

    public Segment(
        Path file,
        String tenantId,
        String sourceId,
        int generation,
        long startOffset,
        long endOffset,
        FileTime modified) {
      this(file, tenantId, sourceId, generation, startOffset, endOffset, modified, null);
    }

    public boolean compressed() {
      return file.getFileName().toString().endsWith(LocalVolumeSink.ZSTD_SUFFIX);
    }

    /**
     * Key of the archived object holding this segment's lines from byte {@code from} up to {@code
     * to}: {@code tenant=T/dt=D/hour=H/source=S/gen=G/FROM-TO.parquet}, with {@code instance=I/}
     * before {@code gen} when the segment has an instance (P6 D8). A segment whose lines span
     * several hours becomes several objects whose ranges tile {@code [startOffset, endOffset)}, so
     * names stay offset-derived and a re-upload replaces, never duplicates.
     *
     * @param eventTime decides {@code dt}/{@code hour} ({@link EventTimePartition}); null for
     *     {@code unknown}
     */
    public String archiveKey(Instant eventTime, long from, long to) {
      return TenantIds.pathSegment(tenantId)
          + "/"
          + EventTimePartition.dayAndHour(eventTime)
          + "/source="
          + sourceId
          + (instance == null ? "" : "/instance=" + instance)
          + "/gen="
          + generation
          + "/"
          + from
          + "-"
          + to
          + ".parquet";
    }
  }

  /**
   * Committed segments some routed sink hasn't acknowledged, oldest first: what the watermarks
   * measure.
   */
  public List<Segment> pending() throws IOException {
    return list(segment -> routed.stream().anyMatch(sink -> !acknowledged(segment, sink)));
  }

  /** Committed segments {@code sink} hasn't acknowledged, oldest first. */
  public List<Segment> pending(WalSink sink) throws IOException {
    return list(segment -> !acknowledged(segment, sink));
  }

  /**
   * A walk that races a sink's deletes is retried, so the list is complete (the WAL watermarks size
   * it); only after {@value #SCAN_ATTEMPTS} such races is a partial list returned.
   */
  private List<Segment> list(Predicate<Segment> wanted) throws IOException {
    for (int attempt = 1; ; attempt++) {
      List<Segment> segments = new ArrayList<>();
      try (Stream<Path> files =
          Files.find(
              root,
              SEGMENT_DEPTH,
              (path, attrs) -> attrs.isRegularFile() && isSegmentName(path.toString()),
              new FileVisitOption[0])) {
        files.forEach(
            file -> {
              Segment segment = parse(file);
              if (segment != null && wanted.test(segment)) {
                segments.add(segment);
              }
            });
      } catch (NoSuchFileException e) {
        return List.of();
      } catch (UncheckedIOException e) {
        if (!(e.getCause() instanceof NoSuchFileException)) {
          throw e.getCause();
        }
        // A file or directory deleted while the walk ran.
        log.debug("WAL changed during the scan (attempt {})", attempt, e);
        if (attempt < SCAN_ATTEMPTS) {
          continue;
        }
      }
      segments.sort(
          Comparator.comparing(Segment::modified).thenComparing(s -> s.file().toString()));
      return segments;
    }
  }

  public boolean exists(Segment segment) {
    return Files.exists(segment.file());
  }

  /** Bytes on disk of {@code segments}; one deleted since it was listed counts 0. */
  public static long bytes(List<Segment> segments) {
    long total = 0;
    for (Segment segment : segments) {
      try {
        total += Files.size(segment.file());
      } catch (IOException e) {
        // acknowledged (deleted) since the listing
      }
    }
    return total;
  }

  public boolean acknowledged(Segment segment, WalSink sink) {
    return Files.exists(marker(segment, sink));
  }

  /**
   * Records that {@code sink} is done with the segment; the last routed sink to do so deletes it
   * (when segments aren't kept). The segment goes before its markers: a crash in between leaves
   * markers without a segment, which nothing lists.
   */
  public synchronized void acknowledge(Segment segment, WalSink sink) throws IOException {
    if (!routed.contains(sink)) {
      throw new IllegalArgumentException(sink + " is not routed on this WAL");
    }
    Path marker = marker(segment, sink);
    if (!Files.exists(marker)) {
      try {
        Files.createFile(marker);
      } catch (FileAlreadyExistsException e) {
        // acknowledged twice: same outcome
      }
    }
    if (deleteAcknowledged && routed.stream().allMatch(s -> acknowledged(segment, s))) {
      Files.deleteIfExists(segment.file());
      for (WalSink s : routed) {
        Files.deleteIfExists(marker(segment, s));
      }
    }
  }

  /** Takes a segment that can't be parsed out of the WAL's queue, keeping its data on disk. */
  public Path quarantine(Segment segment) throws IOException {
    Path target = segment.file().resolveSibling(segment.file().getFileName() + UNREADABLE_SUFFIX);
    DurableFiles.moveAtomically(segment.file(), target);
    return target;
  }

  private static boolean isSegmentName(String name) {
    return name.endsWith(".ndjson") || name.endsWith(".ndjson" + LocalVolumeSink.ZSTD_SUFFIX);
  }

  private static Path marker(Segment segment, WalSink sink) {
    return segment.file().resolveSibling(segment.file().getFileName() + sink.markerSuffix());
  }

  private Segment parse(Path file) {
    String relative =
        root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    Matcher m = SEGMENT.matcher(relative);
    if (!m.matches()) {
      return null;
    }
    try {
      return new Segment(
          file,
          m.group(1),
          m.group(2),
          Integer.parseInt(m.group(3)),
          Long.parseLong(m.group(4)),
          Long.parseLong(m.group(5)),
          Files.getLastModifiedTime(file),
          instance);
    } catch (NumberFormatException | IOException e) {
      log.debug("Skipping {}: not a readable WAL segment", file, e);
      return null;
    }
  }
}
