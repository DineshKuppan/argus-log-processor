package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.archive.Archiver.Outcome;
import dev.dinesh.argus.archive.Archiver.Pass;
import dev.dinesh.argus.config.ArchiveProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiverTest {

  private static final Instant T = Instant.parse("2025-10-09T13:45:00Z");

  @TempDir Path dir;

  private Path walRoot;
  private WalFixture wal;
  private FileSystemObjectStore archive;

  @BeforeEach
  void setUp() {
    walRoot = dir.resolve("wal");
    wal = new WalFixture(walRoot);
    archive = new FileSystemObjectStore(dir.resolve("archive"));
  }

  private ArchiveProperties props(String prefix, boolean deleteAfterUpload) {
    return new ArchiveProperties(
        ArchiveProperties.Store.FILESYSTEM,
        prefix,
        deleteAfterUpload,
        Duration.ofMillis(50),
        dir.resolve("staging"),
        null,
        Duration.ofMillis(10),
        Duration.ofMillis(80),
        new ArchiveProperties.FileSystem(dir.resolve("archive")),
        null);
  }

  private Archiver archiver(ObjectStore store, ArchiveProperties props) throws IOException {
    Files.createDirectories(props.stagingDir());
    return new Archiver(store, new WalSegments(walRoot), props, WalFixture.MAPPER);
  }

  private Archiver archiver(ObjectStore store) throws IOException {
    return archiver(store, props(null, true));
  }

  private Path archived(String key) {
    return dir.resolve("archive").resolve(key);
  }

  private List<Path> walFiles() throws IOException {
    try (Stream<Path> files = Files.walk(walRoot)) {
      return files.filter(Files::isRegularFile).toList();
    }
  }

  @Test
  void archivesEachSegmentUnderItsTenantAndHourThenDeletesItFromTheWal() throws Exception {
    wal.segment("acme", "app", 0, 0, 3, T);
    wal.segment("globex", "app", 1, 500, 2, T.plusSeconds(3600));

    Pass pass = archiver(archive).runOnce();

    assertEquals(new Pass(2, 0, 0), pass);
    List<Group> acme =
        ParquetTestFiles.rows(
            archived("tenant=acme/dt=2025-10-09/hour=13/source=app/gen=0/0-30.parquet"));
    assertEquals(
        List.of("acme-app-0-0", "acme-app-0-10", "acme-app-0-20"),
        acme.stream().map(r -> r.getString(EnvelopeSchema.EVENT_ID, 0)).toList());
    assertEquals(
        2,
        ParquetTestFiles.rows(
                archived("tenant=globex/dt=2025-10-09/hour=14/source=app/gen=1/500-520.parquet"))
            .size());
    assertEquals(List.of(), walFiles());
    assertEquals(List.of(), Files.list(dir.resolve("staging")).toList());
  }

  @Test
  void appliesThePrefixAndFilesSegmentsWithoutEventTimesUnderUnknown() throws Exception {
    wal.segment("acme", "app", 0, 0, 1, null);

    archiver(archive, props("/argus/prod/", true)).runOnce();

    assertEquals(
        List.of("argus/prod/tenant=acme/dt=unknown/hour=unknown/source=app/gen=0/0-10.parquet"),
        archive.list("argus/"));
  }

  @Test
  void keepsAndMarksSegmentsWhenDeleteAfterUploadIsOff() throws Exception {
    Path segment = wal.segment("acme", "app", 0, 0, 2, T);
    Archiver archiver = archiver(archive, props(null, false));

    assertEquals(new Pass(1, 0, 0), archiver.runOnce());
    assertEquals(new Pass(0, 0, 0), archiver.runOnce());

    assertTrue(Files.exists(segment));
    assertTrue(Files.exists(Path.of(segment + WalSegments.ARCHIVED_SUFFIX)));
    assertEquals(1, archive.list("tenant=acme/").size());
  }

  @Test
  void aFailingStoreKeepsTheSegmentAndTheNextPassUploadsIt() throws Exception {
    Path segment = wal.segment("acme", "app", 0, 0, 2, T);
    AtomicInteger failuresLeft = new AtomicInteger(2);
    ObjectStore flaky =
        new DelegatingStore(archive) {
          @Override
          public void put(String key, Path file) throws IOException {
            if (failuresLeft.getAndDecrement() > 0) {
              throw new IOException("503 Slow Down");
            }
            super.put(key, file);
          }
        };
    Archiver archiver = archiver(flaky);

    assertEquals(new Pass(0, 0, 1), archiver.runOnce());
    assertEquals(new Pass(0, 0, 1), archiver.runOnce());
    assertTrue(Files.exists(segment));
    assertEquals(List.of(), Files.list(dir.resolve("staging")).toList());

    assertEquals(new Pass(1, 0, 0), archiver.runOnce());
    assertFalse(Files.exists(segment));
  }

  @Test
  void aStoreFailureStopsThePassSoLaterSegmentsWait() throws Exception {
    Path older = wal.segment("acme", "a", 0, 0, 1, T);
    Path newer = wal.segment("acme", "b", 0, 0, 1, T);
    Files.setLastModifiedTime(older, FileTime.from(T));
    ObjectStore down =
        new DelegatingStore(archive) {
          @Override
          public void put(String key, Path file) throws IOException {
            throw new IOException("connection refused");
          }
        };

    assertEquals(new Pass(0, 0, 1), archiver(down).runOnce());
    assertTrue(Files.exists(older) && Files.exists(newer));
  }

  @Test
  void replacesCopiesOfTheSameSegmentThatEndedAtADifferentOffset() throws Exception {
    // A crashed run archived 0-10; the replay committed 0-20 in its place.
    String dirKey = "tenant=acme/dt=2025-10-09/hour=13/source=app/gen=0/";
    Path stale = Files.writeString(dir.resolve("stale"), "x");
    archive.put(dirKey + "0-10.parquet", stale);
    archive.put(dirKey + "10-30.parquet", stale);
    archive.put("tenant=acme/dt=2025-10-09/hour=13/source=app/gen=1/0-10.parquet", stale);
    wal.segment("acme", "app", 0, 0, 2, T);

    archiver(archive).runOnce();

    assertEquals(
        Set.of(
            dirKey + "0-20.parquet",
            dirKey + "10-30.parquet",
            "tenant=acme/dt=2025-10-09/hour=13/source=app/gen=1/0-10.parquet"),
        Set.copyOf(archive.list("tenant=acme/")));
  }

  @Test
  void aSegmentReplacedDuringItsUploadIsNotKept() throws Exception {
    Path segment = wal.segment("acme", "app", 0, 0, 2, T);
    ObjectStore racing =
        new DelegatingStore(archive) {
          @Override
          public void put(String key, Path file) throws IOException {
            super.put(key, file);
            Files.delete(segment); // ingest's replay replaced it meanwhile
          }
        };
    Archiver archiver = archiver(racing);

    assertEquals(Outcome.SUPERSEDED, archiver.archive(new WalSegments(walRoot).pending().get(0)));
    assertEquals(List.of(), archive.list("tenant=acme/"));
  }

  @Test
  void anUnreadableSegmentIsSkippedAndTheRestAreArchived() throws Exception {
    Path good = wal.segment("acme", "app", 0, 100, 1, T);
    Path bad = walRoot.resolve("tenant=acme/dt=2025-10-09/source=app/gen=0/0-100.ndjson");
    Files.writeString(bad, "{not json\n");
    Files.setLastModifiedTime(bad, FileTime.from(T));

    assertEquals(new Pass(1, 0, 1), archiver(archive).runOnce());
    assertTrue(Files.exists(bad));
    assertFalse(Files.exists(good));
  }

  @Test
  void backoffDoublesWithJitterAndIsCapped() throws Exception {
    Archiver archiver = archiver(archive); // retry-initial 10ms, retry-max 80ms

    for (int i = 0; i < 50; i++) {
      long first = archiver.backoff(1).toMillis();
      long third = archiver.backoff(3).toMillis();
      long capped = archiver.backoff(40).toMillis();
      assertTrue(first >= 5 && first <= 10, "first " + first);
      assertTrue(third >= 20 && third <= 40, "third " + third);
      assertTrue(capped >= 40 && capped <= 80, "capped " + capped);
    }
  }

  @Test
  void runsInTheBackgroundOnceStartedAndCleansStagingLeftovers() throws Exception {
    ArchiveProperties props = props(null, true);
    Files.createDirectories(props.stagingDir());
    Path leftover = Files.writeString(props.stagingDir().resolve("crashed.parquet"), "x");
    Archiver archiver = archiver(archive, props);
    archiver.start();
    try {
      assertFalse(Files.exists(leftover));
      Path segment = wal.segment("acme", "app", 0, 0, 1, T);
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (Files.exists(segment) && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertFalse(Files.exists(segment));
      assertEquals(1, archive.list("tenant=acme/").size());
    } finally {
      archiver.stop();
    }
    assertFalse(archiver.isRunning());
  }

  /** Forwards to another store; tests override single calls to inject failures. */
  private static class DelegatingStore implements ObjectStore {
    private final ObjectStore delegate;

    DelegatingStore(ObjectStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public String describe() {
      return delegate.describe();
    }

    @Override
    public void put(String key, Path file) throws IOException {
      delegate.put(key, file);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
      return delegate.list(prefix);
    }

    @Override
    public void delete(String key) throws IOException {
      delegate.delete(key);
    }
  }
}
