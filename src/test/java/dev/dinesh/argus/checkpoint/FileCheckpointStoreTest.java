package dev.dinesh.argus.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.checkpoint.ResumePoint.Reason;
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

  @Test
  void savesAndLoadsTheLatestOffset() throws IOException {
    FileCheckpointStore store = store();
    store.save(new SourceOffset("app", "/logs/app.log", "inode-1", 100));
    store.save(new SourceOffset("app", "/logs/app.log", "inode-1", 250));

    assertEquals(
        Optional.of(new SourceOffset("app", "/logs/app.log", "inode-1", 250)), store.load("app"));
    assertFalse(Files.exists(dir.resolve("checkpoints/app.json.tmp")));
  }

  @Test
  void missingCheckpointIsEmpty() throws IOException {
    assertTrue(store().load("never-saved").isEmpty());
  }

  @Test
  void leftoverTempFileFromACrashIsIgnored() throws IOException {
    FileCheckpointStore store = store();
    store.save(new SourceOffset("app", "/logs/app.log", "inode-1", 100));
    Files.writeString(dir.resolve("checkpoints/app.json.tmp"), "{\"half\":");

    assertEquals(100, store.load("app").orElseThrow().byteOffset());
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
        IllegalArgumentException.class, () -> store.save(new SourceOffset("a/b", "/x", null, 0)));
  }

  @Test
  void resumesFromCheckpointWhenFileIsUnchanged() {
    Optional<SourceOffset> cp = Optional.of(new SourceOffset("app", "/x", "inode-1", 500));
    assertEquals(
        new ResumePoint(500, Reason.CHECKPOINT), ResumePoint.resolve(cp, "inode-1", 900, false));
  }

  @Test
  void restartsFromZeroWhenFileWasReplacedOrTruncated() {
    Optional<SourceOffset> cp = Optional.of(new SourceOffset("app", "/x", "inode-1", 500));

    assertEquals(
        new ResumePoint(0, Reason.FILE_REPLACED), ResumePoint.resolve(cp, "inode-2", 900, false));
    assertEquals(
        new ResumePoint(0, Reason.FILE_TRUNCATED), ResumePoint.resolve(cp, "inode-1", 100, false));
  }

  @Test
  void fromBeginningAndMissingCheckpointStartAtZero() {
    Optional<SourceOffset> cp = Optional.of(new SourceOffset("app", "/x", "inode-1", 500));

    assertEquals(
        Reason.FROM_BEGINNING_REQUESTED, ResumePoint.resolve(cp, "inode-1", 900, true).reason());
    assertEquals(
        Reason.NO_CHECKPOINT, ResumePoint.resolve(Optional.empty(), "k", 900, false).reason());
  }
}
