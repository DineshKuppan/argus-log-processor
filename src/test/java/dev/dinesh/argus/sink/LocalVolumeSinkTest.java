package dev.dinesh.argus.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.sink.LogSink.SegmentWriter;
import dev.dinesh.argus.source.LogBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class LocalVolumeSinkTest {

  private static final Instant TS = Instant.parse("2026-10-26T10:15:30Z");

  @TempDir Path root;

  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private LocalVolumeSink sink() {
    return new LocalVolumeSink(new LocalSinkProperties(root, DataSize.ofBytes(0)), mapper);
  }

  private static LogBatch batch(long start, long end, String... messages) {
    List<LogRecord> records =
        Stream.of(messages).map(m -> new LogRecord(TS, "INFO", m, Map.of("k", "v"))).toList();
    return new LogBatch("app", 0, 0, records, start, end, true, 0);
  }

  private List<Path> committedFiles() throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(p -> p.toString().endsWith(".ndjson")).sorted().toList();
    }
  }

  @Test
  void commitMovesThePartFileToItsOffsetDerivedPath() throws IOException {
    SegmentWriter writer = sink().open("app", 0, 0);
    writer.write(batch(0, 40, "one", "two"));

    String location = writer.commit(40);

    Path expected = root.resolve("tenant=default/dt=2026-10-26/source=app/gen=0/0-40.ndjson");
    assertEquals(expected.toString(), location);
    List<String> lines = Files.readAllLines(expected);
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("\"message\":\"one\""));
    assertTrue(lines.get(0).contains("\"timestamp\":\"2026-10-26T10:15:30Z\""));
    assertFalse(Files.exists(root.resolve("tenant=default/_staging/source=app/0.ndjson.part")));
  }

  @Test
  void abortDeletesThePartFile() throws IOException {
    SegmentWriter writer = sink().open("app", 0, 0);
    writer.write(batch(0, 40, "one"));

    writer.abort();

    assertTrue(committedFiles().isEmpty());
    assertFalse(Files.exists(root.resolve("tenant=default/_staging/source=app/0.ndjson.part")));
  }

  @Test
  void recommittingASegmentReplacesTheEarlierCopy() throws IOException {
    LocalVolumeSink sink = sink();
    SegmentWriter first = sink.open("app", 0, 0);
    first.write(batch(0, 40, "one"));
    first.commit(40);

    // Rerun after a crash: same start offset, file has grown so the segment ends later.
    SegmentWriter replay = sink.open("app", 0, 0);
    replay.write(batch(0, 60, "one", "two"));
    replay.commit(60);

    List<Path> files = committedFiles();
    assertEquals(1, files.size());
    assertTrue(files.get(0).endsWith("0-60.ndjson"));
  }

  @Test
  void aNewGenerationNeverReplacesTheEarlierFilesOutput() throws IOException {
    LocalVolumeSink sink = sink();
    SegmentWriter beforeRotation = sink.open("app", 0, 0);
    beforeRotation.write(batch(0, 40, "old file"));
    beforeRotation.commit(40);

    // Same path after rotation: offsets restart at 0, on the same day.
    SegmentWriter afterRotation = sink.open("app", 1, 0);
    afterRotation.write(batch(0, 60, "new file"));
    afterRotation.commit(60);

    List<Path> files = committedFiles();
    assertEquals(2, files.size());
    assertTrue(files.get(0).endsWith("gen=0/0-40.ndjson"));
    assertTrue(files.get(1).endsWith("gen=1/0-60.ndjson"));
  }

  @Test
  void writesOneJsonRecordPerLineWithATrailingNewline() throws IOException {
    SegmentWriter writer = sink().open("app", 0, 0);
    writer.write(batch(0, 20, "one", "two"));
    writer.write(batch(20, 40, "three"));

    String content = Files.readString(Path.of(writer.commit(40)));

    assertTrue(content.endsWith("}\n"));
    List<String> lines = content.lines().toList();
    assertEquals(3, lines.size());
    for (String line : lines) {
      assertTrue(line.startsWith("{") && line.endsWith("}"), line);
      mapper.readTree(line);
    }
  }

  @Test
  void segmentsOfSimilarlyNamedSourcesDoNotCollide() throws IOException {
    LocalVolumeSink sink = sink();
    SegmentWriter app = sink.open("app", 0, 0);
    app.write(batch(0, 40, "one"));
    app.commit(40);
    SegmentWriter app0 = sink.open("app-0", 0, 0);
    app0.write(batch(0, 40, "one"));
    app0.commit(40);

    sink.discardUncommitted("app");

    assertEquals(2, committedFiles().size());
  }

  @Test
  void emptySegmentIsNotWritten() throws IOException {
    SegmentWriter writer = sink().open("app", 0, 0);
    writer.write(batch(0, 40));

    assertNull(writer.commit(40));
    assertTrue(committedFiles().isEmpty());
  }

  @Test
  void discardUncommittedRemovesStalePartsForThatSourceOnly() throws IOException {
    LocalVolumeSink sink = sink();
    sink.open("app", 0, 0).write(batch(0, 40, "crashed"));
    sink.open("other", 0, 0).write(batch(0, 40, "in progress"));

    sink.discardUncommitted("app");

    assertFalse(Files.exists(root.resolve("tenant=default/_staging/source=app/0.ndjson.part")));
    assertTrue(Files.exists(root.resolve("tenant=default/_staging/source=other/0.ndjson.part")));
  }

  @Test
  void failsFastWhenFreeSpaceIsBelowTheMinimum() {
    LocalVolumeSink sink =
        new LocalVolumeSink(
            new LocalSinkProperties(root, DataSize.ofBytes(Long.MAX_VALUE)), mapper);

    IOException e = assertThrows(IOException.class, () -> sink.open("app", 0, 0));
    assertTrue(e.getMessage().startsWith("Insufficient disk space"));
  }

  @Test
  void rejectsSourceIdsThatCouldEscapeTheRoot() {
    assertThrows(IllegalArgumentException.class, () -> sink().open("..", 0, 0));
    assertThrows(IllegalArgumentException.class, () -> sink().open("a/b", 0, 0));
  }
}
