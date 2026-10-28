package dev.dinesh.argus.archive;

import dev.dinesh.argus.sink.EventTimePartition;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.utils.DurableFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * The committed segments {@link dev.dinesh.argus.sink.LocalVolumeSink} wrote, read as the WAL the
 * archiver drains. Only committed {@code .ndjson} files are listed: they appear by atomic rename,
 * so a listed segment is always complete. Uncommitted output under {@code _staging} never matches.
 */
@Slf4j
public final class WalSegments {

  static final String ARCHIVED_SUFFIX = ".archived";

  /**
   * A segment that can't be parsed is renamed with this suffix: kept for inspection, not retried.
   */
  static final String UNREADABLE_SUFFIX = ".unreadable";

  /** {@code tenant=T/dt=D/source=S/gen=G/START-END.ndjson}, as LocalVolumeSink names segments. */
  private static final Pattern SEGMENT =
      Pattern.compile("tenant=([^/]+)/dt=[^/]+/source=([^/]+)/gen=(\\d+)/(\\d+)-(\\d+)\\.ndjson");

  private static final int SEGMENT_DEPTH = 5;

  private final Path root;

  public WalSegments(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public Path root() {
    return root;
  }

  /** A committed segment of one tenant's source. */
  public record Segment(
      Path file,
      String tenantId,
      String sourceId,
      int generation,
      long startOffset,
      long endOffset,
      FileTime modified) {

    /**
     * Key of the archived object holding this segment's lines from byte {@code from} up to {@code
     * to}: {@code tenant=T/dt=D/hour=H/source=S/gen=G/FROM-TO.parquet}. A segment whose lines span
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
          + "/gen="
          + generation
          + "/"
          + from
          + "-"
          + to
          + ".parquet";
    }
  }

  /** Committed segments not yet archived, oldest first. */
  public List<Segment> pending() throws IOException {
    List<Segment> segments = new ArrayList<>();
    try (Stream<Path> files =
        Files.find(
            root,
            SEGMENT_DEPTH,
            (path, attrs) -> attrs.isRegularFile() && path.toString().endsWith(".ndjson"),
            new FileVisitOption[0])) {
      files.forEach(
          file -> {
            Segment segment = parse(file);
            if (segment != null && !Files.exists(marker(segment))) {
              segments.add(segment);
            }
          });
    } catch (NoSuchFileException e) {
      return List.of();
    } catch (UncheckedIOException e) {
      // A segment deleted while the walk ran; the next scan sees a consistent tree.
      if (e.getCause() instanceof NoSuchFileException) {
        log.debug("WAL changed during the scan", e);
      } else {
        throw e.getCause();
      }
    }
    segments.sort(Comparator.comparing(Segment::modified).thenComparing(s -> s.file().toString()));
    return segments;
  }

  public boolean exists(Segment segment) {
    return Files.exists(segment.file());
  }

  /** Removes an archived segment from the WAL. */
  public void delete(Segment segment) throws IOException {
    Files.deleteIfExists(segment.file());
  }

  /** Keeps an archived segment in the WAL, marked so it isn't archived again. */
  public void markArchived(Segment segment) throws IOException {
    Path marker = marker(segment);
    if (!Files.exists(marker)) {
      Files.createFile(marker);
    }
  }

  /** Takes a segment that can't be parsed out of the WAL's queue, keeping its data on disk. */
  public Path quarantine(Segment segment) throws IOException {
    Path target = segment.file().resolveSibling(segment.file().getFileName() + UNREADABLE_SUFFIX);
    DurableFiles.moveAtomically(segment.file(), target);
    return target;
  }

  private static Path marker(Segment segment) {
    return segment.file().resolveSibling(segment.file().getFileName() + ARCHIVED_SUFFIX);
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
          Files.getLastModifiedTime(file));
    } catch (NumberFormatException | IOException e) {
      log.debug("Skipping {}: not a readable WAL segment", file, e);
      return null;
    }
  }
}
