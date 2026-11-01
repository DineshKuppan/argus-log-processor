package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What every {@link ObjectStore} must do, so the archiver can use any of them (LSP). */
abstract class ObjectStoreContractTest {

  @TempDir Path dir;

  private ObjectStore store;

  /** Each test gets its own key space, so stores shared across tests stay independent. */
  private String base;

  protected abstract ObjectStore store() throws Exception;

  protected abstract byte[] read(String key) throws Exception;

  @BeforeEach
  void setUp() throws Exception {
    store = store();
    base = "t" + UUID.randomUUID().toString().substring(0, 8) + "/";
  }

  private Path file(String content) throws IOException {
    return Files.writeString(
        dir.resolve(UUID.randomUUID() + ".bin"), content, StandardCharsets.UTF_8);
  }

  @Test
  void putThenReadAndList() throws Exception {
    store.put(base + "tenant=a/dt=2025-10-09/x.parquet", file("one"));

    assertArrayEquals(
        "one".getBytes(StandardCharsets.UTF_8), read(base + "tenant=a/dt=2025-10-09/x.parquet"));
    assertEquals(List.of(base + "tenant=a/dt=2025-10-09/x.parquet"), store.list(base));
  }

  @Test
  void putReplacesAnExistingObject() throws Exception {
    store.put(base + "k.parquet", file("first"));
    store.put(base + "k.parquet", file("second"));

    assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), read(base + "k.parquet"));
    assertEquals(1, store.list(base).size());
  }

  @Test
  void listReturnsOnlyKeysWithThePrefix() throws Exception {
    for (String key :
        List.of(
            "s/gen=0/1-10.parquet",
            "s/gen=0/1-20.parquet",
            "s/gen=0/10-30.parquet",
            "s/gen=1/1-5.parquet",
            "t/1-1.parquet")) {
      store.put(base + key, file(key));
    }

    assertEquals(
        Set.of(base + "s/gen=0/1-10.parquet", base + "s/gen=0/1-20.parquet"),
        Set.copyOf(store.list(base + "s/gen=0/1-")));
    assertEquals(4, store.list(base + "s/").size());
    assertTrue(store.list(base + "missing/").isEmpty());
  }

  @Test
  void getReturnsTheObjectOrNullWhenMissing() throws Exception {
    store.put(base + "tenant=a/_archive/source=s/gen=0.json", file("{\"startOffset\":0}"));

    assertArrayEquals(
        "{\"startOffset\":0}".getBytes(StandardCharsets.UTF_8),
        store.get(base + "tenant=a/_archive/source=s/gen=0.json"));
    assertNull(store.get(base + "tenant=a/_archive/source=s/gen=1.json"));
  }

  @Test
  void deleteRemovesTheObjectAndIgnoresMissingKeys() throws Exception {
    store.put(base + "k.parquet", file("x"));

    store.delete(base + "k.parquet");
    store.delete(base + "k.parquet");
    store.delete(base + "never-written.parquet");

    assertTrue(store.list(base).isEmpty());
  }

  @Test
  void largeFilesArriveIntact() throws Exception {
    // Above S3ObjectStore's multipart threshold, so S3 uploads it in parts.
    byte[] content = new byte[(int) (S3ObjectStore.PART_BYTES + S3ObjectStore.PART_BYTES / 2)];
    new Random(7).nextBytes(content);
    Path big = Files.write(dir.resolve("big.bin"), content);

    store.put(base + "big.parquet", big);

    assertArrayEquals(content, read(base + "big.parquet"));
  }

  @Test
  void rejectsKeysThatAreNotPlainRelativePaths() {
    for (String key :
        List.of("/abs.parquet", "../escape.parquet", "a/../b", "a//b", "a/./b", "dir/", "")) {
      assertThrows(IllegalArgumentException.class, () -> store.put(key, file("x")), key);
      assertThrows(IllegalArgumentException.class, () -> store.delete(key), key);
    }
  }
}
