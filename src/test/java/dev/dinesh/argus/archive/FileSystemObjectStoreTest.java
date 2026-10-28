package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FileSystemObjectStoreTest extends ObjectStoreContractTest {

  private Path root() {
    return dir.resolve("archive");
  }

  @Override
  protected ObjectStore store() {
    return new FileSystemObjectStore(root());
  }

  @Override
  protected byte[] read(String key) throws Exception {
    return Files.readAllBytes(root().resolve(key));
  }

  @Test
  void leavesNoTemporaryFilesAndIgnoresOnesACrashLeftBehind() throws Exception {
    ObjectStore store = store();
    store.put("a/k.parquet", Files.writeString(dir.resolve("in"), "x"));
    Files.writeString(
        root().resolve("a/k2.parquet.1234" + FileSystemObjectStore.TEMP_SUFFIX), "half");

    assertEquals(List.of("a/k.parquet"), store.list("a/"));
    try (Stream<Path> files = Files.list(root().resolve("a"))) {
      assertEquals(2, files.count());
    }
    assertTrue(store.describe().startsWith("file://"));
  }
}
