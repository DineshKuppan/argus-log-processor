package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.archive.WalSegments.Segment;
import dev.dinesh.argus.config.LocalSinkProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
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
    Files.createFile(Path.of(marked + WalSegments.ARCHIVED_SUFFIX));
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
}
