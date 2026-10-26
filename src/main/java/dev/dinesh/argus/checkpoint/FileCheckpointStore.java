package dev.dinesh.argus.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.source.SourceOffset;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One JSON file per source. Writes go to a temp file that is fsynced and then atomically renamed
 * over the previous checkpoint, so a crash never leaves a half-written checkpoint behind.
 */
@Slf4j
@Component
public class FileCheckpointStore implements CheckpointStore {

  private final Path dir;
  private final ObjectMapper mapper;

  @Autowired
  public FileCheckpointStore(IngestProperties props, ObjectMapper mapper) {
    this(props.checkpointDir(), mapper);
  }

  FileCheckpointStore(Path dir, ObjectMapper mapper) {
    this.dir = dir;
    this.mapper = mapper;
  }

  @Override
  public Optional<SourceOffset> load(String sourceId) throws IOException {
    Path file = fileFor(sourceId);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(file.toFile(), SourceOffset.class));
    } catch (IOException e) {
      log.warn("Ignoring unreadable checkpoint {}: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public void save(SourceOffset offset) throws IOException {
    Path file = fileFor(offset.sourceId());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.createDirectories(dir);
    try (FileChannel channel =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer json = ByteBuffer.wrap(mapper.writeValueAsBytes(offset));
      while (json.hasRemaining()) {
        channel.write(json);
      }
      channel.force(true);
    }
    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private Path fileFor(String sourceId) {
    return dir.resolve(SourceOffset.requireValidSourceId(sourceId) + ".json");
  }
}
