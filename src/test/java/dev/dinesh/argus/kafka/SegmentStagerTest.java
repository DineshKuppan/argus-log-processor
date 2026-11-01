package dev.dinesh.argus.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.luben.zstd.ZstdInputStream;
import dev.dinesh.argus.kafka.SegmentStager.Result;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reassembling segments from interleaved records, without Kafka. */
class SegmentStagerTest {

  @TempDir Path root;

  private Instant now = Instant.parse("2026-11-01T10:00:00Z");

  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };

  private SegmentStager stager() {
    return new SegmentStager(root, clock);
  }

  private static byte[] b(String s) {
    return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
  }

  private static RecordHeaders line(String segment, long sourceOffset) {
    RecordHeaders h = new RecordHeaders();
    h.add(KafkaWal.SEGMENT, b(segment));
    h.add(KafkaWal.OFFSET, b(Long.toString(sourceOffset)));
    return h;
  }

  private static RecordHeaders commit(String segment, long end, long lines) {
    return commit(segment, end, lines, -1);
  }

  private static RecordHeaders commit(String segment, long end, long lines, long first) {
    RecordHeaders h = new RecordHeaders();
    if (first >= 0) {
      h.add(KafkaWal.FIRST, b(Long.toString(first)));
    }
    h.add(KafkaWal.SEGMENT, b(segment));
    h.add(KafkaWal.TYPE, b(KafkaWal.COMMIT));
    h.add(KafkaWal.END, b(Long.toString(end)));
    h.add(KafkaWal.LINES, b(Long.toString(lines)));
    return h;
  }

  private static RecordHeaders abort(String segment) {
    RecordHeaders h = new RecordHeaders();
    h.add(KafkaWal.SEGMENT, b(segment));
    h.add(KafkaWal.TYPE, b(KafkaWal.ABORT));
    return h;
  }

  private static List<String> lines(Path file) throws IOException {
    try (InputStream in = new ZstdInputStream(Files.newInputStream(file))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  @Test
  void interleavedSegmentsBecomeOneLocalSegmentEach() throws IOException {
    SegmentStager s = stager();
    long offset = 0;
    assertEquals(Result.STAGED, s.accept(0, offset++, b("acme/app"), line("0:0", 0), b("a0")));
    assertEquals(Result.STAGED, s.accept(0, offset++, b("acme/web"), line("1:50", 50), b("w0")));
    assertEquals(Result.STAGED, s.accept(0, offset++, b("acme/app"), line("0:0", 10), b("a1")));
    assertEquals(OptionalLong.of(0), s.earliest(0));

    assertEquals(
        Result.COMMITTED, s.accept(0, offset++, b("acme/app"), commit("0:0", 20, 2), null));
    SegmentStager.Ready app = s.takeReady();
    assertEquals(0, app.firstOffset());
    assertEquals("acme", app.segment().tenantId());
    assertEquals("app", app.segment().sourceId());
    assertEquals(0, app.segment().startOffset());
    assertEquals(20, app.segment().endOffset());
    assertTrue(app.segment().compressed());
    assertEquals(
        root.resolve("tenant=acme/dt=kafka/source=app/gen=0/0-20.ndjson.zst"),
        app.segment().file());
    assertEquals(List.of("a0", "a1"), lines(app.segment().file()));

    assertEquals(OptionalLong.of(1), s.earliest(0), "web is still open");
    assertEquals(1, s.open());
  }

  @Test
  void aLineAtOrBeforeTheLastStagedOffsetRestartsTheSegment() throws IOException {
    SegmentStager s = stager();
    s.accept(0, 0, b("acme/app"), line("0:0", 0), b("first try 0"));
    s.accept(0, 1, b("acme/app"), line("0:0", 10), b("first try 1"));
    // The producer crashed before its checkpoint and sends the segment again.
    s.accept(0, 2, b("acme/app"), line("0:0", 0), b("replay 0"));
    s.accept(0, 3, b("acme/app"), line("0:0", 10), b("replay 1"));
    s.accept(0, 4, b("acme/app"), line("0:0", 20), b("replay 2"));
    assertEquals(OptionalLong.of(2), s.earliest(0));

    s.accept(0, 5, b("acme/app"), commit("0:0", 30, 3), null);
    assertEquals(
        List.of("replay 0", "replay 1", "replay 2"), lines(s.takeReady().segment().file()));
  }

  @Test
  void anAbortedSegmentIsDiscarded() throws IOException {
    SegmentStager s = stager();
    s.accept(1, 7, b("acme/app"), line("0:0", 0), b("x"));
    assertEquals(Result.ABORTED, s.accept(1, 8, b("acme/app"), abort("0:0"), null));
    assertEquals(0, s.open());
    assertEquals(OptionalLong.empty(), s.earliest(1));
    try (var files = Files.walk(root)) {
      assertFalse(files.anyMatch(Files::isRegularFile));
    }
  }

  @Test
  void aMarkerForASegmentNotStagedIsUnknown() throws IOException {
    assertEquals(Result.UNKNOWN, stager().accept(0, 0, b("acme/app"), commit("0:0", 30, 3), null));
  }

  @Test
  void aCountMismatchIsStillArchived() throws IOException {
    SegmentStager s = stager();
    s.accept(0, 0, b("acme/app"), line("0:0", 0), b("only one"));
    assertEquals(Result.COMMITTED, s.accept(0, 1, b("acme/app"), commit("0:0", 20, 2), null));
    assertEquals(List.of("only one"), lines(s.takeReady().segment().file()));
  }

  @Test
  void aCopyStagedFromPartWayThroughItsSegmentIsDropped() throws IOException {
    SegmentStager s = stager();
    // Reading resumed at Kafka offset 5; the segment's first line is at 2.
    s.accept(0, 5, b("acme/app"), line("0:0", 30), b("tail"));
    assertEquals(Result.PARTIAL, s.accept(0, 6, b("acme/app"), commit("0:0", 40, 4, 2), null));
    assertEquals(null, s.takeReady());
    try (var files = Files.walk(root)) {
      assertFalse(files.anyMatch(Files::isRegularFile));
    }

    s.accept(0, 7, b("acme/web"), line("0:0", 0), b("whole"));
    assertEquals(Result.COMMITTED, s.accept(0, 8, b("acme/web"), commit("0:0", 10, 1, 7), null));
  }

  @Test
  void touchRestartsTheOrphanClock() throws IOException {
    SegmentStager s = stager();
    s.accept(0, 0, b("acme/app"), line("0:0", 0), b("x"));
    now = now.plus(Duration.ofMinutes(20)); // the consumer held its partitions
    s.touch();
    now = now.plus(Duration.ofMinutes(1));
    assertEquals(List.of(), s.expire(Duration.ofMinutes(15)));
  }

  @Test
  void segmentsSilentForTheOrphanTimeoutExpire() throws IOException {
    SegmentStager s = stager();
    s.accept(0, 0, b("acme/app"), line("0:0", 0), b("x"));
    now = now.plus(Duration.ofMinutes(10));
    s.accept(0, 1, b("acme/web"), line("0:0", 0), b("y"));
    now = now.plus(Duration.ofMinutes(6));

    assertEquals(
        List.of(new SegmentStager.Key("acme", "app", 0, 0)), s.expire(Duration.ofMinutes(15)));
    assertEquals(OptionalLong.of(1), s.earliest(0));
  }

  @Test
  void aRevokedPartitionsSegmentsAreDropped() throws IOException {
    SegmentStager s = stager();
    s.accept(0, 0, b("acme/app"), line("0:0", 0), b("x"));
    s.accept(1, 0, b("acme/web"), line("0:0", 0), b("y"));
    s.drop(0);
    assertEquals(OptionalLong.empty(), s.earliest(0));
    assertEquals(OptionalLong.of(0), s.earliest(1));
  }

  @Test
  void recordsArgusDidNotWriteAreSkippedNotStaged() throws IOException {
    SegmentStager s = stager();
    for (String key : List.of("../../etc/passwd", "acme/../x", "no-slash", "acme/a b")) {
      assertEquals(Result.INVALID, s.accept(0, 0, b(key), line("0:0", 0), b("x")), key);
    }
    assertEquals(Result.INVALID, s.accept(0, 0, b("acme/app"), new RecordHeaders(), b("x")));
    assertEquals(Result.INVALID, s.accept(0, 0, b("acme/app"), line("x:0", 0), b("x")));
    assertEquals(0, s.open());
  }

  @Test
  void clearKeepsTheArchiveIndex() throws IOException {
    SegmentStager s = stager();
    s.accept(0, 0, b("acme/app"), line("0:0", 0), b("x"));
    Path index = Files.createDirectories(root.resolve("tenant=acme/_archive/source=app"));
    Files.writeString(index.resolve("gen=0.json"), "{}");

    stager().clear();

    assertTrue(Files.exists(index.resolve("gen=0.json")));
    assertFalse(Files.exists(root.resolve("tenant=acme/dt=kafka/source=app/gen=0/0.staging")));
  }
}
