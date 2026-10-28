package dev.dinesh.argus.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

  /** {@code tenant=T/dt=D/source=S/gen=G/START-END.ndjson}, as LocalVolumeSink names segments. */
  private static final Pattern SEGMENT =
      Pattern.compile("tenant=([^/]+)/dt=[^/]+/source=([^/]+)/gen=(\\d+)/(\\d+)-(\\d+)\\.ndjson");

  private static final int SEGMENT_DEPTH = 5;
  private static final String UNKNOWN = "unknown";

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
     * Where the segment is archived, relative to the store's prefix: {@code
     * tenant=T/dt=D/hour=H/source=S/gen=G/START-END.parquet}. {@code dt} and {@code hour} come from
     * the first event time in the segment, as the WAL's {@code dt} does, so a replay lands on the
     * same key.
     */
    public String archiveKey(Instant firstTimestamp) {
      return archiveDirectory(firstTimestamp) + startOffset + "-" + endOffset + ".parquet";
    }

    /**
     * Prefix of every archived object starting at this segment's offset: earlier copies a replay
     * superseded (same start, a different end). The trailing {@code -} keeps {@code 1-} from
     * matching {@code 10-}.
     */
    public String supersededPrefix(Instant firstTimestamp) {
      return archiveDirectory(firstTimestamp) + startOffset + "-";
    }

    private String archiveDirectory(Instant firstTimestamp) {
      String day = UNKNOWN;
      String hour = UNKNOWN;
      if (firstTimestamp != null) {
        ZonedDateTime utc = firstTimestamp.atZone(ZoneOffset.UTC);
        day = utc.toLocalDate().toString();
        hour = String.format("%02d", utc.getHour());
      }
      return "tenant="
          + tenantId
          + "/dt="
          + day
          + "/hour="
          + hour
          + "/source="
          + sourceId
          + "/gen="
          + generation
          + "/";
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
