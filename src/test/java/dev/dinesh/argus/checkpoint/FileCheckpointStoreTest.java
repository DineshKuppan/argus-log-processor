package dev.dinesh.argus.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.source.SourceOffset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCheckpointStoreTest {

  @TempDir Path dir;

  private FileCheckpointStore store() {
    return new FileCheckpointStore(dir.resolve("checkpoints"), new ObjectMapper());
  }

  private static SourceOffset offset(long byteOffset) {
    return new SourceOffset("app", "/logs/app.log", "inode-1", 2, byteOffset, 64, "ab12");
  }

  @Test
  void savesAndLoadsTheLatestOffset() throws IOException {
    FileCheckpointStore store = store();
    store.save(offset(100));
    store.save(offset(250));

    assertEquals(Optional.of(offset(250)), store.load("app"));
    assertFalse(Files.exists(dir.resolve("checkpoints/app.json.tmp")));
  }

  @Test
  void missingCheckpointIsEmpty() throws IOException {
    assertTrue(store().load("never-saved").isEmpty());
  }

  @Test
  void leftoverTempFileFromACrashIsIgnored() throws IOException {
    FileCheckpointStore store = store();
    store.save(offset(100));
    Files.writeString(dir.resolve("checkpoints/app.json.tmp"), "{\"half\":");

    assertEquals(100, store.load("app").orElseThrow().byteOffset());
  }

  @Test
  void checkpointWrittenBeforeGenerationsExistedLoadsAsGenerationZero() throws IOException {
    Files.createDirectories(dir.resolve("checkpoints"));
    Files.writeString(
        dir.resolve("checkpoints/app.json"),
        "{\"sourceId\":\"app\",\"path\":\"/x\",\"fileKey\":\"k\",\"byteOffset\":7}");

    SourceOffset cp = store().load("app").orElseThrow();

    assertEquals(new SourceOffset("app", "/x", "k", 0, 7, 0, null), cp);
  }

  @Test
  void corruptCheckpointIsTreatedAsMissing() throws IOException {
    Files.createDirectories(dir.resolve("checkpoints"));
    Files.writeString(dir.resolve("checkpoints/app.json"), "not json");

    assertTrue(store().load("app").isEmpty());
  }

  @Test
  void rejectsSourceIdsThatCouldEscapeTheDirectory() {
    FileCheckpointStore store = store();
    assertThrows(IllegalArgumentException.class, () -> store.load("../etc/passwd"));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.save(new SourceOffset("a/b", "/x", null, 0, 0, 0, null)));
  }
}
