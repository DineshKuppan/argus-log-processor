package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.utils.DurableFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Each stream's export cursor, at {@code checkpoint-dir/tenant=T/_export/<stream>.json}: written to
 * a temp file, fsynced and renamed into place, so a crash leaves the previous cursor or the new
 * one.
 */
@Slf4j
@Component
public class ExportCursorStore {

  private static final String DIR = "_export";

  /**
   * An export's progress on one stream.
   *
   * @param identity the export (source, query, range); another export on the stream starts over
   * @param runId part of every page's key; a new run ({@code fromBeginning}) re-exports
   * @param cursor where the next page starts, or null before the first page
   * @param done every page is in the spool
   */
  public record Saved(
      String identity,
      String runId,
      String source,
      ExportSource.Cursor cursor,
      boolean done,
      long pages,
      long lines,
      long bytes,
      Instant updatedAt) {}

  private final Path root;
  private final ObjectMapper mapper;

  @Autowired
  public ExportCursorStore(IngestProperties props, ObjectMapper mapper) {
    this(props.checkpointDir(), mapper);
  }

  public ExportCursorStore(Path root, ObjectMapper mapper) {
    this.root = root;
    this.mapper = mapper;
  }

  public Optional<Saved> load(String tenantId, String streamId) throws IOException {
    Path file = fileFor(tenantId, streamId);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(file.toFile(), Saved.class));
    } catch (IOException e) {
      log.warn("Ignoring unreadable export cursor {}: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  public void save(String tenantId, String streamId, Saved saved) throws IOException {
    Path file = fileFor(tenantId, streamId);
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    DurableFiles.createDirectories(file.getParent());
    try (FileChannel channel =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer json = ByteBuffer.wrap(mapper.writeValueAsBytes(saved));
      while (json.hasRemaining()) {
        channel.write(json);
      }
      channel.force(true);
    }
    DurableFiles.moveAtomically(tmp, file);
  }

  private Path fileFor(String tenantId, String streamId) {
    return root.resolve(TenantIds.pathSegment(tenantId))
        .resolve(DIR)
        .resolve(SourceOffset.requireValidSourceId(streamId) + ".json");
  }
}
