package dev.dinesh.argus.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.utils.DurableFiles;
import dev.dinesh.argus.wal.WalSegments.Segment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Remembers the object keys of the latest archived segment of each tenant's source and generation,
 * so that when a replay replaces that segment its earlier objects can be deleted wherever they
 * landed: a replay can change the segment's end and so its partitions (e.g. {@code dt=unknown}
 * before, a real day after).
 *
 * <p>Only the latest segment needs remembering: ingest commits segments in order and checkpoints
 * each before writing the next, so only the last committed segment can still be replayed.
 *
 * <p>Entries are at {@code tenant=T/_archive/source=S/[instance=I/]gen=G.json}, either next to the
 * local WAL (temp file + fsync + atomic rename; one archiver per WAL volume) or, with Kafka as the
 * WAL, in the object store under the archive prefix (P6 D8): a source's segments are archived by
 * whichever instance owns its partition at the time, so every instance must read the same index.
 * One writer per source at a time either way (a partition has one owner).
 */
final class ArchiveIndex {

  /** Keys of the objects archived for the segment starting at {@code startOffset}. */
  record Entry(long startOffset, List<String> keys) {}

  private final Path walRoot;
  private final ObjectStore store;
  private final String prefix;
  private final Path stagingDir;
  private final ObjectMapper mapper;

  /** Next to the local WAL. */
  ArchiveIndex(Path walRoot, ObjectMapper mapper) {
    this(walRoot, null, null, null, mapper);
  }

  /** In {@code store}, under {@code prefix}; {@code stagingDir} holds the upload's temp file. */
  ArchiveIndex(ObjectStore store, String prefix, Path stagingDir, ObjectMapper mapper) {
    this(null, store, prefix, stagingDir, mapper);
  }

  private ArchiveIndex(
      Path walRoot, ObjectStore store, String prefix, Path stagingDir, ObjectMapper mapper) {
    this.walRoot = walRoot;
    this.store = store;
    this.prefix = prefix;
    this.stagingDir = stagingDir;
    this.mapper = mapper;
  }

  boolean inStore() {
    return store != null;
  }

  /** The latest entry for the segment's source and generation, or null if there is none. */
  Entry load(Segment segment) throws IOException {
    if (store != null) {
      byte[] json = store.get(prefix + relative(segment));
      return json == null ? null : mapper.readValue(json, Entry.class);
    }
    Path file = walRoot.resolve(relative(segment));
    return Files.exists(file) ? mapper.readValue(file.toFile(), Entry.class) : null;
  }

  void save(Segment segment, Entry entry) throws IOException {
    if (store != null) {
      Path temp = stagingDir.resolve(UUID.randomUUID() + ".index.json");
      try {
        Files.write(temp, mapper.writeValueAsBytes(entry));
        store.put(prefix + relative(segment), temp);
      } finally {
        Files.deleteIfExists(temp);
      }
      return;
    }
    Path file = walRoot.resolve(relative(segment));
    DurableFiles.createDirectories(file.getParent());
    Path temp =
        Files.write(
            file.resolveSibling(file.getFileName() + ".tmp"), mapper.writeValueAsBytes(entry));
    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    DurableFiles.moveAtomically(temp, file);
  }

  private static String relative(Segment segment) {
    return TenantIds.pathSegment(segment.tenantId())
        + "/_archive/source="
        + segment.sourceId()
        + (segment.instance() == null ? "" : "/instance=" + segment.instance())
        + "/gen="
        + segment.generation()
        + ".json";
  }
}
