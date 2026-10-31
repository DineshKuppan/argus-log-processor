package dev.dinesh.argus.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.wal.WalSegments.Segment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalSegmentsTest {

  private static final Instant T = Instant.parse("2025-10-09T03:05:00Z");

  @TempDir Path root;

  @Test
  void listsCommittedSegmentsOldestFirstAndSkipsEverythingElse() throws Exception {
    WalFixture wal = new WalFixture(root);
    Path newer = wal.segment("acme", "app", 0, 30, 1, T);
    Path older = wal.segment("acme", "web", 2, 0, 3, T);
    Files.setLastModifiedTime(older, FileTime.from(T));
    Path marked = wal.segment("acme", "app", 0, 0, 1, T);
    Files.createFile(Path.of(marked + WalSink.ARCHIVE.markerSuffix()));
    Files.createDirectories(root.resolve("tenant=acme/_staging/source=app"));
    Files.writeString(root.resolve("tenant=acme/_staging/source=app/40.ndjson.part"), "{}");
    Files.writeString(root.resolve("tenant=acme/stray.ndjson"), "{}");

    List<Segment> pending = new WalSegments(root).pending();

    assertEquals(
        List.of(older.toAbsolutePath(), newer.toAbsolutePath()),
        pending.stream().map(Segment::file).toList());
    Segment first = pending.get(0);
    assertEquals("acme", first.tenantId());
    assertEquals("web", first.sourceId());
    assertEquals(2, first.generation());
    assertEquals(0, first.startOffset());
    assertEquals(30, first.endOffset());
  }

  @Test
  void listsZstdSegmentsToo() throws Exception {
    Path file =
        new WalFixture(root, LocalSinkProperties.Compression.ZSTD)
            .segment("acme", "app", 0, 0, 2, T);

    List<Segment> pending = new WalSegments(root).pending();

    assertEquals(List.of(file.toAbsolutePath()), pending.stream().map(Segment::file).toList());
    assertEquals(20, pending.get(0).endOffset());
    assertTrue(pending.get(0).compressed());
  }

  @Test
  void anEmptyOrMissingWalHasNothingPending() throws Exception {
    assertEquals(List.of(), new WalSegments(root.resolve("never-created")).pending());
    assertEquals(List.of(), new WalSegments(root).pending());
  }

  @Test
  void archiveKeysFollowTheEventTimeInUtcAndTheObjectsByteRange() {
    Segment segment = new Segment(root, "acme", "app", 3, 100, 250, FileTime.from(T));

    assertEquals(
        "tenant=acme/dt=2025-10-09/hour=03/source=app/gen=3/100-180.parquet",
        segment.archiveKey(T, 100, 180));
    assertEquals(
        "tenant=acme/dt=unknown/hour=unknown/source=app/gen=3/180-250.parquet",
        segment.archiveKey(null, 180, 250));
  }

  @Test
  void aQuarantinedSegmentIsKeptButNoLongerPending() throws Exception {
    Path file = new WalFixture(root).segment("acme", "app", 0, 0, 1, T);
    WalSegments wal = new WalSegments(root);

    Path kept = wal.quarantine(wal.pending().get(0));

    assertEquals(Path.of(file + WalSegments.UNREADABLE_SUFFIX), kept);
    assertEquals(List.of(), wal.pending());
  }

  private static final Set<WalSink> BOTH = EnumSet.of(WalSink.ARCHIVE, WalSink.HOT);

  @Test
  void eachSinkSeesWhatItHasntAcknowledgedAndTheLastDeletesTheSegment() throws Exception {
    Path file = new WalFixture(root).segment("acme", "app", 0, 0, 1, T);
    WalSegments wal = new WalSegments(root, BOTH, true);
    Segment segment = wal.pending().get(0);

    wal.acknowledge(segment, WalSink.HOT);

    assertEquals(List.of(segment), wal.pending(WalSink.ARCHIVE));
    assertEquals(List.of(), wal.pending(WalSink.HOT));
    assertEquals(List.of(segment), wal.pending(), "the watermark still counts it");
    assertTrue(Files.exists(file));

    wal.acknowledge(segment, WalSink.ARCHIVE);

    assertFalse(Files.exists(file));
    assertFalse(Files.exists(Path.of(file + WalSink.HOT.markerSuffix())), "markers go too");
    assertFalse(Files.exists(Path.of(file + WalSink.ARCHIVE.markerSuffix())));
    assertEquals(List.of(), wal.pending());
  }

  @Test
  void keptSegmentsAreOnlyMarked() throws Exception {
    Path file = new WalFixture(root).segment("acme", "app", 0, 0, 1, T);
    WalSegments wal = new WalSegments(root, BOTH, false);
    Segment segment = wal.pending().get(0);

    wal.acknowledge(segment, WalSink.ARCHIVE);
    wal.acknowledge(segment, WalSink.HOT);
    wal.acknowledge(segment, WalSink.HOT);

    assertTrue(Files.exists(file));
    assertEquals(List.of(), wal.pending());
  }

  @Test
  void anArchivedMarkerFromBeforeP5CountsForTheArchiveOnly() throws Exception {
    Path file = new WalFixture(root).segment("acme", "app", 0, 0, 1, T);
    Files.createFile(Path.of(file + ".archived"));
    WalSegments wal = new WalSegments(root, BOTH, true);

    assertEquals(List.of(), wal.pending(WalSink.ARCHIVE));
    assertEquals(1, wal.pending(WalSink.HOT).size(), "not yet indexed");

    wal.acknowledge(wal.pending(WalSink.HOT).get(0), WalSink.HOT);

    assertFalse(Files.exists(file));
  }

  @Test
  void concurrentAcknowledgementsDeleteOnce() throws Exception {
    for (int i = 0; i < 50; i++) {
      new WalFixture(root).segment("acme", "app", 0, 10L * i, 1, T);
    }
    WalSegments wal = new WalSegments(root, BOTH, true);
    List<Segment> segments = wal.pending();
    CompletableFuture<?> archive =
        CompletableFuture.runAsync(() -> acknowledgeAll(wal, segments, WalSink.ARCHIVE));
    CompletableFuture<?> hot =
        CompletableFuture.runAsync(() -> acknowledgeAll(wal, segments, WalSink.HOT));
    CompletableFuture.allOf(archive, hot).get(10, TimeUnit.SECONDS);

    assertEquals(List.of(), wal.pending());
    try (Stream<Path> left = Files.walk(root)) {
      assertEquals(List.of(), left.filter(Files::isRegularFile).toList(), "no segment or marker");
    }
  }

  @Test
  void aSinkThatIsntRoutedCantAcknowledge() throws Exception {
    new WalFixture(root).segment("acme", "app", 0, 0, 1, T);
    WalSegments wal = new WalSegments(root);

    assertThrows(
        IllegalArgumentException.class, () -> wal.acknowledge(wal.pending().get(0), WalSink.HOT));
  }

  private static void acknowledgeAll(WalSegments wal, List<Segment> segments, WalSink sink) {
    try {
      for (Segment segment : segments) {
        wal.acknowledge(segment, sink);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
