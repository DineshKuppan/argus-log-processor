package dev.dinesh.argus.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.archive.WalSegments.Segment;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.utils.DurableFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Remembers the object keys of the latest archived segment of each tenant's source and generation,
 * so that when a replay replaces that segment its earlier objects can be deleted wherever they
 * landed: a replay can change the segment's end and so its partitions (e.g. {@code dt=unknown}
 * before, a real day after).
 *
 * <p>Only the latest segment needs remembering: ingest commits segments in order and checkpoints
 * each before writing the next, so only the last committed segment can still be replayed.
 *
 * <p>Stored next to the WAL, at {@code root/tenant=T/_archive/source=S/gen=G.json}, written with
 * temp file + fsync + atomic rename. Used by the single archiver thread only.
 */
final class ArchiveIndex {

  /** Keys of the objects archived for the segment starting at {@code startOffset}. */
  record Entry(long startOffset, List<String> keys) {}

  private final Path walRoot;
  private final ObjectMapper mapper;

  ArchiveIndex(Path walRoot, ObjectMapper mapper) {
    this.walRoot = walRoot;
    this.mapper = mapper;
  }

  /** The latest entry for the segment's source and generation, or null if there is none. */
  Entry load(Segment segment) throws IOException {
    Path file = file(segment);
    return Files.exists(file) ? mapper.readValue(file.toFile(), Entry.class) : null;
  }

  void save(Segment segment, Entry entry) throws IOException {
    Path file = file(segment);
    DurableFiles.createDirectories(file.getParent());
    Path temp =
        Files.write(
            file.resolveSibling(file.getFileName() + ".tmp"), mapper.writeValueAsBytes(entry));
    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    DurableFiles.moveAtomically(temp, file);
  }

  private Path file(Segment segment) {
    return walRoot
        .resolve(TenantIds.pathSegment(segment.tenantId()))
        .resolve("_archive")
        .resolve("source=" + segment.sourceId())
        .resolve("gen=" + segment.generation() + ".json");
  }
}
