package dev.dinesh.argus.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.config.IngestProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class FileSourceTest {

  @TempDir Path dir;

  private static IngestProperties props(
      int maxLines, long maxBatchBytes, long segmentBytes, long maxLineBytes) {
    return new IngestProperties(
        maxLines,
        DataSize.ofBytes(maxBatchBytes),
        DataSize.ofBytes(segmentBytes),
        2,
        4,
        DataSize.ofBytes(maxLineBytes),
        2,
        List.of(),
        null);
  }

  private static IngestProperties linesOnly(int maxLines) {
    return props(maxLines, Long.MAX_VALUE, Long.MAX_VALUE, 1024 * 1024);
  }

  private Path write(String content) throws IOException {
    Path file = dir.resolve("app.log");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static List<String> numberedLines(int count) {
    return IntStream.range(0, count).mapToObj(i -> String.format("line-%04d", i)).toList();
  }

  private static List<RawBatch> readAll(Path file, long start, IngestProperties props)
      throws IOException {
    List<RawBatch> batches = new ArrayList<>();
    try (FileSource source = FileSource.open(file, "src", start, props)) {
      for (RawBatch b = source.nextBatch(); b != null; b = source.nextBatch()) {
        batches.add(b);
      }
    }
    return batches;
  }

  private static List<String> linesOf(List<RawBatch> batches) {
    return batches.stream().flatMap(b -> b.lines().stream()).toList();
  }

  @Test
  void batchClosesAtMaxLinesWithContiguousOffsets() throws IOException {
    Path file = write(String.join("\n", numberedLines(25)) + "\n");

    List<RawBatch> batches = readAll(file, 0, linesOnly(10));

    assertEquals(List.of(10, 10, 5), batches.stream().map(b -> b.lines().size()).toList());
    assertEquals(0, batches.get(0).startOffset());
    for (int i = 1; i < batches.size(); i++) {
      assertEquals(batches.get(i - 1).endOffset(), batches.get(i).startOffset());
    }
    assertEquals(Files.size(file), batches.get(2).endOffset());
    assertTrue(batches.get(2).lastInSegment());
    assertFalse(batches.get(0).lastInSegment());
  }

  @Test
  void batchClosesOnceByteBudgetIsReachedAtALineBoundary() throws IOException {
    String hundredBytes = "x".repeat(100);
    Path file = write((hundredBytes + "\n").repeat(7));

    List<RawBatch> batches = readAll(file, 0, props(1000, 250, Long.MAX_VALUE, 1024));

    // 101 bytes per line: the budget is first met after the 3rd line.
    assertEquals(List.of(3, 3, 1), batches.stream().map(b -> b.lines().size()).toList());
    assertEquals(303, batches.get(0).endOffset());
  }

  @Test
  void resumingFromAnyBatchEndReproducesTheRemainingLines() throws IOException {
    List<String> expected = numberedLines(37);
    Path file = write(String.join("\n", expected) + "\n");
    IngestProperties props = linesOnly(5);

    List<RawBatch> batches = readAll(file, 0, props);
    int linesSoFar = 0;
    for (RawBatch batch : batches) {
      linesSoFar += batch.lines().size();
      List<String> resumed = linesOf(readAll(file, batch.endOffset(), props));
      assertEquals(expected.subList(linesSoFar, expected.size()), resumed);
    }
  }

  @Test
  void handlesCrlfAndAFinalLineWithoutNewline() throws IOException {
    Path file = write("a\r\nb\r\nc");

    List<RawBatch> batches = readAll(file, 0, linesOnly(100));

    assertEquals(List.of("a", "b", "c"), linesOf(batches));
    assertEquals(Files.size(file), batches.get(batches.size() - 1).endOffset());
  }

  @Test
  void decodesMultiByteCharacterSplitAcrossReadBuffers() throws IOException {
    // 'é' is 2 bytes in UTF-8; place it across the 64KB read buffer boundary.
    String prefix = "x".repeat(64 * 1024 - 1);
    Path file = write(prefix + "é\nnext\n");

    List<String> lines = linesOf(readAll(file, 0, linesOnly(100)));

    assertEquals(List.of(prefix + "é", "next"), lines);
  }

  @Test
  void truncatesOverlongLinesAndKeepsOffsetsExact() throws IOException {
    Path file = write("0123456789ABCDEF\nok\n");

    List<RawBatch> batches = readAll(file, 0, props(100, Long.MAX_VALUE, Long.MAX_VALUE, 10));

    assertEquals(List.of("0123456789", "ok"), linesOf(batches));
    assertEquals(1, batches.get(0).truncatedLines());
    assertEquals(Files.size(file), batches.get(0).endOffset());
  }

  @Test
  void skipsBlankLinesButCountsTheirBytes() throws IOException {
    Path file = write("a\n\n   \nb\n");

    List<RawBatch> batches = readAll(file, 0, linesOnly(100));

    assertEquals(List.of("a", "b"), linesOf(batches));
    assertEquals(Files.size(file), batches.get(0).endOffset());
  }

  @Test
  void closesSegmentAtTheFirstBatchBoundaryPastTheSegmentSize() throws IOException {
    // 10 bytes per line, 2 lines (20 bytes) per batch, segments of at least 50 bytes.
    Path file = write(String.join("\n", numberedLines(10)) + "\n");

    List<RawBatch> batches = readAll(file, 0, props(2, Long.MAX_VALUE, 50, 1024));

    assertEquals(
        List.of(false, false, true, false, true),
        batches.stream().map(RawBatch::lastInSegment).toList());
    assertEquals(List.of(0, 0, 0, 1, 1), batches.stream().map(RawBatch::segmentIndex).toList());
  }

  @Test
  void readsOnlyUpToTheSizeSeenAtOpen() throws IOException {
    Path file = write("a\nb\n");
    List<RawBatch> batches = new ArrayList<>();
    try (FileSource source = FileSource.open(file, "src", 0, linesOnly(1))) {
      batches.add(source.nextBatch());
      Files.writeString(file, "c\n", StandardOpenOption.APPEND);
      for (RawBatch b = source.nextBatch(); b != null; b = source.nextBatch()) {
        batches.add(b);
      }
    }

    assertEquals(List.of("a", "b"), linesOf(batches));
  }

  @Test
  void emptyFileYieldsNoBatches() throws IOException {
    Path file = write("");
    try (FileSource source = FileSource.open(file, "src", 0, linesOnly(10))) {
      assertNull(source.nextBatch());
    }
  }

  @Test
  void rejectsStartOffsetBeyondEndOfFile() throws IOException {
    Path file = write("a\n");
    assertThrows(
        IllegalArgumentException.class, () -> FileSource.open(file, "src", 99, linesOnly(10)));
  }
}
