package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.archive.Archiver.Outcome;
import dev.dinesh.argus.archive.Archiver.Pass;
import dev.dinesh.argus.config.ArchiveProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.wal.WalFixture;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiverTest {

  private static final Instant T = Instant.parse("2025-10-09T13:45:00Z");
  private static final String ACME_APP_13 = "tenant=acme/dt=2025-10-09/hour=13/source=app/gen=0/";

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
        null,
        null);
  }

  private Archiver archiver(ObjectStore store, ArchiveProperties props) throws IOException {
    Files.createDirectories(props.stagingDir());
    return new Archiver(
        store,
        new WalSegments(walRoot, Set.of(WalSink.ARCHIVE), props.deleteAfterUpload()),
        props,
        WalFixture.MAPPER);
  }

  private Archiver archiver(ObjectStore store) throws IOException {
    return archiver(store, props(null, true));
  }

  private Path archived(String key) {
    return dir.resolve("archive").resolve(key);
  }

  private List<Path> walSegments() throws IOException {
    try (Stream<Path> files = Files.walk(walRoot)) {
      return files.filter(p -> p.toString().matches(".*\\.ndjson(\\.zst)?$")).toList();
    }
  }

  private Set<String> keys() throws IOException {
    return Set.copyOf(archive.list("tenant=acme/"));
  }

  @Test
  void archivesEachSegmentUnderItsTenantAndHourThenDeletesItFromTheWal() throws Exception {
    wal.segment("acme", "app", 0, 0, 3, T);
    wal.segment("globex", "app", 1, 500, 2, T.plusSeconds(3600));

    Pass pass = archiver(archive).runOnce();

    assertEquals(new Pass(2, 0, 0, 0), pass);
    List<Group> acme = ParquetTestFiles.rows(archived(ACME_APP_13 + "0-30.parquet"));
    assertEquals(
        List.of(0L, 10L, 20L),
        acme.stream().map(r -> r.getLong(EnvelopeSchema.SOURCE_OFFSET, 0)).toList());
    assertEquals(
        2,
        ParquetTestFiles.rows(
                archived("tenant=globex/dt=2025-10-09/hour=14/source=app/gen=1/500-520.parquet"))
            .size());
    assertEquals(List.of(), walSegments());
    assertEquals(List.of(), Files.list(dir.resolve("staging")).toList());
  }

  @Test
  void archivesZstdSegmentsLikePlainOnes() throws Exception {
    Path segment =
        new WalFixture(walRoot, LocalSinkProperties.Compression.ZSTD)
            .segment("acme", "app", 0, 0, 3, T);
    assertTrue(segment.toString().endsWith(".ndjson.zst"));

    assertEquals(new Pass(1, 0, 0, 0), archiver(archive).runOnce());

    assertEquals(3, ParquetTestFiles.rows(archived(ACME_APP_13 + "0-30.parquet")).size());
    assertFalse(Files.exists(segment));
  }

  @Test
  void aCorruptZstdSegmentIsSetAside() throws Exception {
    Path bad = walRoot.resolve("tenant=acme/dt=2025-10-09/source=app/gen=0/0-100.ndjson.zst");
    Files.createDirectories(bad.getParent());
    Files.write(bad, new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 1, 2, 3, 4, 5, 6, 7});

    assertEquals(new Pass(0, 0, 1, 0), archiver(archive).runOnce());
    assertTrue(Files.exists(Path.of(bad + WalSegments.UNREADABLE_SUFFIX)));
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
  void splitsASegmentWhereverTheHourChangesAndKeepsUntimedLinesInPlace() throws Exception {
    Instant h13 = Instant.parse("2025-10-09T13:59:58Z");
    wal.segment(
        "acme",
        "app",
        0,
        0,
        Arrays.asList(
            null, h13, h13.plusSeconds(1), h13.plusSeconds(2), null, h13.plusSeconds(1800)));

    assertEquals(new Pass(1, 0, 0, 0), archiver(archive).runOnce());

    String h14 = "tenant=acme/dt=2025-10-09/hour=14/source=app/gen=0/";
    assertEquals(Set.of(ACME_APP_13 + "0-30.parquet", h14 + "30-60.parquet"), keys());
    assertEquals(
        List.of(0L, 10L, 20L),
        ParquetTestFiles.rows(archived(ACME_APP_13 + "0-30.parquet")).stream()
            .map(r -> r.getLong(EnvelopeSchema.SOURCE_OFFSET, 0))
            .toList());
    assertEquals(3, ParquetTestFiles.rows(archived(h14 + "30-60.parquet")).size());
  }

  @Test
  void outOfOrderDataIsSplitIntoABoundedNumberOfObjects() throws Exception {
    List<Instant> alternating = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      alternating.add(T.plusSeconds(i % 2 == 0 ? 0 : 3600));
    }
    wal.segment("acme", "app", 0, 0, alternating);

    archiver(archive).runOnce();

    List<String> keys = archive.list("tenant=acme/");
    assertEquals(Archiver.MAX_OBJECTS_PER_SEGMENT, keys.size());
    long rows = 0;
    for (String key : keys) {
      rows += ParquetTestFiles.rows(archived(key)).size();
    }
    assertEquals(40, rows);
    // The ranges tile the segment: 0-10, 10-20, …, and the last runs to the end.
    List<long[]> ranges =
        keys.stream()
            .map(k -> k.substring(k.lastIndexOf('/') + 1, k.length() - ".parquet".length()))
            .map(r -> Arrays.stream(r.split("-")).mapToLong(Long::parseLong).toArray())
            .sorted(Comparator.comparingLong(r -> r[0]))
            .toList();
    for (int i = 1; i < ranges.size(); i++) {
      assertEquals(ranges.get(i - 1)[1], ranges.get(i)[0]);
    }
    assertEquals(400, ranges.get(ranges.size() - 1)[1]);
  }

  @Test
  void keepsAndMarksSegmentsWhenDeleteAfterUploadIsOff() throws Exception {
    Path segment = wal.segment("acme", "app", 0, 0, 2, T);
    Archiver archiver = archiver(archive, props(null, false));

    assertEquals(new Pass(1, 0, 0, 0), archiver.runOnce());
    assertEquals(new Pass(0, 0, 0, 0), archiver.runOnce());

    assertTrue(Files.exists(segment));
    assertTrue(Files.exists(Path.of(segment + WalSink.ARCHIVE.markerSuffix())));
    assertEquals(1, keys().size());
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

    assertEquals(new Pass(0, 0, 0, 1), archiver.runOnce());
    assertEquals(new Pass(0, 0, 0, 1), archiver.runOnce());
    assertTrue(Files.exists(segment));
    assertEquals(List.of(), Files.list(dir.resolve("staging")).toList());

    assertEquals(new Pass(1, 0, 0, 0), archiver.runOnce());
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

    assertEquals(new Pass(0, 0, 0, 1), archiver(down).runOnce());
    assertTrue(Files.exists(older) && Files.exists(newer));
  }

  @Test
  void aStagingDiskFailureBacksOffInsteadOfBlamingTheData() throws Exception {
    Path segment = wal.segment("acme", "app", 0, 0, 1, T);
    Archiver archiver = archiver(archive);
    Files.delete(dir.resolve("staging")); // e.g. an unmounted or full volume

    assertEquals(new Pass(0, 0, 0, 1), archiver.runOnce());
    assertTrue(Files.exists(segment));
  }

  @Test
  void aReplayedSegmentReplacesItsEarlierCopyEvenInAnotherPartition() throws Exception {
    // A crashed run archived 0-10, whose only line had no event time; the replay read further
    // (the file grew) and committed 0-30, whose first timed line puts it in hour 13.
    wal.segment("acme", "app", 0, 0, 1, null);
    Archiver archiver = archiver(archive);
    archiver.runOnce();
    assertEquals(
        Set.of("tenant=acme/dt=unknown/hour=unknown/source=app/gen=0/0-10.parquet"), keys());
    wal.segment("acme", "app", 0, 0, Arrays.asList(null, T, T));
    Path later = wal.segment("acme", "app", 0, 30, 1, T);
    Files.setLastModifiedTime(later, FileTime.from(Instant.now().plusSeconds(60)));

    archiver.runOnce();

    assertEquals(Set.of(ACME_APP_13 + "0-30.parquet", ACME_APP_13 + "30-40.parquet"), keys());
  }

  @Test
  void aSegmentReplacedDuringItsUploadIsCleanedUpByItsReplacement() throws Exception {
    Path segment = wal.segment("acme", "app", 0, 0, 2, null);
    AtomicBoolean replayed = new AtomicBoolean();
    ObjectStore racing =
        new DelegatingStore(archive) {
          @Override
          public void put(String key, Path file) throws IOException {
            super.put(key, file);
            if (replayed.compareAndSet(false, true)) {
              Files.delete(segment); // ingest's replay replaced it meanwhile
            }
          }
        };
    Archiver archiver = archiver(racing);

    assertEquals(Outcome.SUPERSEDED, archiver.archive(new WalSegments(walRoot).pending().get(0)));
    wal.segment("acme", "app", 0, 0, 3, T);
    assertEquals(new Pass(1, 0, 0, 0), archiver.runOnce());

    assertEquals(Set.of(ACME_APP_13 + "0-30.parquet"), keys());
  }

  @Test
  void aSegmentThatIsNotValidNdjsonIsSetAsideAndTheRestAreArchived() throws Exception {
    Path good = wal.segment("acme", "app", 0, 100, 1, T);
    Path bad = walRoot.resolve("tenant=acme/dt=2025-10-09/source=app/gen=0/0-100.ndjson");
    Files.writeString(bad, "{not json\n");
    Files.setLastModifiedTime(bad, FileTime.from(T));
    Archiver archiver = archiver(archive);

    assertEquals(new Pass(1, 0, 1, 0), archiver.runOnce());
    assertFalse(Files.exists(good));
    assertFalse(Files.exists(bad));
    assertEquals("{not json\n", Files.readString(Path.of(bad + WalSegments.UNREADABLE_SUFFIX)));
    assertEquals(new Pass(0, 0, 0, 0), archiver.runOnce());
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
      awaitGone(segment);
      assertEquals(1, keys().size());
    } finally {
      archiver.stop();
    }
    assertFalse(archiver.isRunning());
  }

  @Test
  void anErrorInAPassDoesNotStopTheBackgroundLoop() throws Exception {
    Path segment = wal.segment("acme", "app", 0, 0, 1, T);
    AtomicInteger errorsLeft = new AtomicInteger(2);
    ObjectStore broken =
        new DelegatingStore(archive) {
          @Override
          public void put(String key, Path file) throws IOException {
            if (errorsLeft.getAndDecrement() > 0) {
              throw new UnsatisfiedLinkError("no zstd-jni in java.library.path");
            }
            super.put(key, file);
          }
        };
    Archiver archiver = archiver(broken, props(null, true));
    archiver.start();
    try {
      awaitGone(segment);
      assertTrue(archiver.isRunning());
      assertEquals(1, keys().size());
    } finally {
      archiver.stop();
    }
  }

  private static void awaitGone(Path file) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (Files.exists(file) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertFalse(Files.exists(file), file + " was not archived");
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
