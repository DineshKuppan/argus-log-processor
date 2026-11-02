package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.FramingProperties;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.framing.FramingSpec;
import dev.dinesh.argus.framing.RecordFramer;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.ingest.IngestPipeline.Request;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.pipeline.EnvelopeFactory;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.sink.WalCapacity;
import dev.dinesh.argus.source.LogBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * Framing in the pipeline ({@code P7_PLAN.md} D1, D3): records across batch, segment and run ends.
 */
class IngestPipelineFramingTest {

  /** 10 lines per batch and a segment every ~2.5KB, so traces straddle both. */
  private static final IngestProperties PROPS =
      new IngestProperties(
          10,
          DataSize.ofMegabytes(1),
          DataSize.ofBytes(2_500),
          2,
          4,
          DataSize.ofKilobytes(64),
          2,
          List.of(),
          null,
          null);

  /** The file under test was just written: an open trace at its end is held back. */
  private static final FramingProperties HOLD =
      new FramingProperties("auto", null, null, Duration.ofHours(1));

  /** An open trace at the end is ingested at once. */
  private static final FramingProperties QUIET =
      new FramingProperties("auto", null, null, Duration.ZERO);

  private static final int FRAMES = 5;

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @TempDir Path dir;

  private static String head(int i, String level) {
    return String.format(
        "timestamp=2025-10-09T01:%02d:%02dZ level=%s msg=\"request %d\"",
        (i / 60) % 60, i % 60, level, i);
  }

  /** {@code records} records; every {@code traceEvery}-th is an ERROR with a 6-line trace. */
  private Path writeLog(int records, int traceEvery) throws IOException {
    Path file = dir.resolve("in/app.log");
    Files.createDirectories(file.getParent());
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < records; i++) {
      boolean trace = traceEvery > 0 && i % traceEvery == traceEvery - 1;
      sb.append(head(i, trace ? "ERROR" : "INFO")).append('\n');
      if (trace) {
        sb.append("java.lang.IllegalStateException: boom ").append(i).append('\n');
        for (int f = 0; f < FRAMES; f++) {
          sb.append("\tat com.example.Orders.place").append(f).append("(Orders.java:").append(i);
          sb.append(")\n");
        }
      }
    }
    Files.writeString(file, sb);
    return file;
  }

  private LocalVolumeSink localSink(Path out) {
    return new LocalVolumeSink(new LocalSinkProperties(out, DataSize.ofBytes(0)), MAPPER);
  }

  private FileCheckpointStore checkpoints(Path cpDir) {
    return new FileCheckpointStore(
        new IngestProperties(
            PROPS.batchMaxLines(),
            PROPS.batchMaxBytes(),
            PROPS.segmentBytes(),
            PROPS.rawQueueCapacity(),
            PROPS.parsedQueueCapacity(),
            PROPS.maxLineBytes(),
            PROPS.maxConcurrentJobs(),
            PROPS.allowedRoots(),
            cpDir,
            PROPS.keepUnparsed()),
        MAPPER);
  }

  private IngestPipeline pipeline(
      LogSink sink, FileCheckpointStore cps, FramingProperties framing) {
    return new IngestPipeline(
        PROPS,
        new ParserRegistry(),
        sink,
        cps,
        WalCapacity.UNBOUNDED,
        ArgusMetrics.noop(),
        null,
        null,
        framing);
  }

  private static Request request(Path file) {
    return new Request("default", file, "app", false, null);
  }

  private static Map<String, String> outputFiles(Path out) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    if (!Files.exists(out)) {
      return files;
    }
    try (Stream<Path> walk = Files.walk(out)) {
      for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
        files.put(out.relativize(p).toString(), Files.readString(p));
      }
    }
    return files;
  }

  private static List<JsonNode> records(Path out) throws IOException {
    List<JsonNode> records = new ArrayList<>();
    for (String content : outputFiles(out).values()) {
      for (String line : content.lines().toList()) {
        records.add(MAPPER.readTree(line));
      }
    }
    records.sort((a, b) -> Long.compare(offset(a), offset(b)));
    return records;
  }

  private static long offset(JsonNode record) {
    return record.at("/source/offset").asLong();
  }

  private static Map<String, String> withoutObservedTime(Map<String, String> files) {
    Map<String, String> normalized = new LinkedHashMap<>();
    files.forEach(
        (k, v) -> normalized.put(k, v.replaceAll("\"observedTimestamp\":\"[^\"]*\",", "")));
    return normalized;
  }

  /** {@code [start, end]} of each segment file, from its {@code START-END.ndjson} name. */
  private static List<long[]> segmentRanges(Path out) throws IOException {
    List<long[]> ranges = new ArrayList<>();
    for (String name : outputFiles(out).keySet()) {
      String file = Path.of(name).getFileName().toString().replace(".ndjson", "");
      String[] parts = file.split("-");
      ranges.add(new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])});
    }
    ranges.sort((a, b) -> Long.compare(a[0], b[0]));
    return ranges;
  }

  @Test
  void aTraceIsOneRecordWhereverBatchAndSegmentEndsFall() throws Exception {
    Path file = writeLog(120, 4);
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    Outcome outcome =
        pipeline(localSink(out), checkpoints(dir.resolve("cp")), QUIET)
            .run(request(file), progress, new AtomicBoolean());

    assertEquals(Outcome.COMPLETED, outcome);
    List<JsonNode> records = records(out);
    assertEquals(120, records.size());
    assertEquals(120, progress.lines());
    assertEquals(0, progress.unparsed());
    Set<Long> heads = new TreeSet<>();
    for (int i = 0; i < records.size(); i++) {
      JsonNode r = records.get(i);
      heads.add(offset(r));
      assertEquals("request " + i, r.get("body").asText().lines().findFirst().orElseThrow());
      assertEquals(
          EnvelopeFactory.eventId("default", "app", 0, offset(r)), r.get("eventId").asText());
      if (i % 4 == 3) {
        assertEquals(2 + FRAMES, r.at("/attributes/argus.lines").asInt());
        assertEquals(
            "java.lang.IllegalStateException", r.at("/attributes/exception.type").asText());
        assertTrue(r.get("body").asText().endsWith("(Orders.java:" + i + ")"));
      } else {
        assertTrue(r.at("/attributes/argus.lines").isMissingNode());
      }
    }
    assertSegmentsEndAtRecordStarts(out, file, heads);
  }

  /** Segments tile the file, and each ends at a record start (or the file's end). */
  private static void assertSegmentsEndAtRecordStarts(Path out, Path file, Set<Long> heads)
      throws IOException {
    List<long[]> ranges = segmentRanges(out);
    assertTrue(ranges.size() > 3, "the test needs several segments: " + ranges.size());
    assertEquals(0, ranges.get(0)[0]);
    for (int i = 0; i < ranges.size(); i++) {
      long end = ranges.get(i)[1];
      if (i + 1 < ranges.size()) {
        assertEquals(end, ranges.get(i + 1)[0]);
        assertTrue(heads.contains(end), "segment ends inside a record at " + end);
      } else {
        assertEquals(Files.size(file), end);
      }
    }
  }

  @Test
  void splitCriLinesAreJoinedAndParsedOnceAcrossBatchAndSegmentEnds() throws Exception {
    Path file = dir.resolve("in/pod.log");
    Files.createDirectories(file.getParent());
    String ts = "2025-08-21T12:00:00.000000000Z";
    StringBuilder sb = new StringBuilder();
    int n = 90;
    for (int i = 0; i < n; i++) {
      if (i % 3 == 2) {
        String json =
            "{\"level\":\"ERROR\",\"message\":\"split " + i + " " + "x".repeat(60) + "\"}";
        int a = json.length() / 3;
        int b = 2 * a;
        sb.append(ts).append(" stdout P ").append(json, 0, a).append('\n');
        sb.append(ts).append(" stdout P ").append(json, a, b).append('\n');
        sb.append(ts).append(" stdout F ").append(json, b, json.length()).append('\n');
      } else {
        sb.append(ts).append(" stdout F {\"level\":\"INFO\",\"message\":\"line ").append(i);
        sb.append("\"}\n");
      }
    }
    Files.writeString(file, sb);
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    pipeline(localSink(out), checkpoints(dir.resolve("cp")), QUIET)
        .run(request(file), progress, new AtomicBoolean());

    assertEquals("cri", progress.format());
    List<JsonNode> records = records(out);
    assertEquals(n, records.size());
    Set<Long> heads = new TreeSet<>();
    for (int i = 0; i < n; i++) {
      JsonNode r = records.get(i);
      heads.add(offset(r));
      if (i % 3 == 2) {
        assertEquals("split " + i + " " + "x".repeat(60), r.get("body").asText());
        assertEquals("ERROR", r.get("severityText").asText());
        assertEquals(3, r.at("/attributes/argus.cri.fragments").asInt());
      } else {
        assertEquals("line " + i, r.get("body").asText());
        assertTrue(r.at("/attributes/argus.cri.fragments").isMissingNode());
      }
    }
    assertSegmentsEndAtRecordStarts(out, file, heads);
  }

  @Test
  void anUnclosedCriPartialAtTheEndIsHeldBackLikeAnOpenTrace() throws Exception {
    Path file = dir.resolve("in/pod.log");
    Files.createDirectories(file.getParent());
    String ts = "2025-08-21T12:00:00.000000000Z";
    String done = ts + " stdout F {\"message\":\"done\"}\n";
    Files.writeString(file, done + ts + " stdout P {\"message\":\"still\n");
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    pipeline(localSink(out), checkpoints(dir.resolve("cp")), HOLD)
        .run(request(file), progress, new AtomicBoolean());

    assertEquals(Files.size(file) - done.length(), progress.heldBackBytes());
    assertEquals(1, records(out).size());
  }

  @Test
  void aSourceOfSingleLineRecordsCommitsExactlyAsWithoutFraming() throws Exception {
    Path file = writeLog(150, 0);
    Path auto = dir.resolve("auto");
    Path none = dir.resolve("none");

    pipeline(localSink(auto), checkpoints(dir.resolve("cp-auto")), HOLD)
        .run(request(file), new IngestProgress(), new AtomicBoolean());
    IngestProgress progress = new IngestProgress();
    pipeline(localSink(none), checkpoints(dir.resolve("cp-none")), HOLD)
        .run(request(file).withFraming(FramingSpec.NONE), progress, new AtomicBoolean());

    assertEquals(withoutObservedTime(outputFiles(none)), withoutObservedTime(outputFiles(auto)));
    assertEquals(150, progress.lines());
    assertEquals(0, progress.heldBackBytes());
  }

  @Test
  void framingNoneKeepsEachTraceLineAsItsOwnRecord() throws Exception {
    Path file = writeLog(8, 4);
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    pipeline(localSink(out), checkpoints(dir.resolve("cp")), HOLD)
        .run(request(file).withFraming(FramingSpec.NONE), progress, new AtomicBoolean());

    assertEquals(8 + 2 * (1 + FRAMES), records(out).size());
    assertEquals(2 * (1 + FRAMES), progress.unparsed());
  }

  @Test
  void aRerunAfterACrashCommitsTheSameRecords() throws Exception {
    Path file = writeLog(120, 3);
    Path clean = dir.resolve("clean");
    pipeline(localSink(clean), checkpoints(dir.resolve("clean-cp")), QUIET)
        .run(request(file), new IngestProgress(), new AtomicBoolean());

    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    AtomicInteger commits = new AtomicInteger();
    LogSink crashing =
        new LogSink() {
          final LocalVolumeSink delegate = localSink(out);

          @Override
          public void discardUncommitted(String tenantId, String sourceId) throws IOException {
            delegate.discardUncommitted(tenantId, sourceId);
          }

          @Override
          public SegmentWriter open(
              String tenantId, String sourceId, int generation, long startOffset)
              throws IOException {
            SegmentWriter writer = delegate.open(tenantId, sourceId, generation, startOffset);
            return new SegmentWriter() {
              @Override
              public void write(LogBatch batch) throws IOException {
                writer.write(batch);
              }

              @Override
              public String commit(long endOffset) throws IOException {
                if (commits.incrementAndGet() == 3) {
                  throw new IOException("simulated crash while committing segment 3");
                }
                return writer.commit(endOffset);
              }

              @Override
              public void abort() {
                writer.abort();
              }
            };
          }
        };
    assertThrows(
        IOException.class,
        () ->
            pipeline(crashing, cps, QUIET)
                .run(request(file), new IngestProgress(), new AtomicBoolean()));

    IngestProgress rerun = new IngestProgress();
    pipeline(localSink(out), cps, QUIET).run(request(file), rerun, new AtomicBoolean());

    assertEquals("CHECKPOINT", rerun.resumeReason());
    assertTrue(rerun.startOffset() > 0);
    assertEquals(withoutObservedTime(outputFiles(clean)), withoutObservedTime(outputFiles(out)));
  }

  @Test
  void aTraceOpenAtTheEndOfAFreshFileIsHeldBackUntilTheFileIsQuiet() throws Exception {
    Path file = writeLog(12, 4);
    long lastHead = Files.size(file) - lastTraceBytes(11);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    IngestProgress first = new IngestProgress();

    Outcome outcome =
        pipeline(localSink(out), cps, HOLD).run(request(file), first, new AtomicBoolean());

    assertEquals(Outcome.COMPLETED, outcome);
    assertEquals(Files.size(file) - lastHead, first.heldBackBytes());
    assertEquals(lastHead, first.committedOffset());
    assertEquals(11, records(out).size());

    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
    IngestProgress second = new IngestProgress();
    pipeline(localSink(out), cps, HOLD).run(request(file), second, new AtomicBoolean());

    assertEquals(lastHead, second.startOffset());
    assertEquals(0, second.heldBackBytes());
    List<JsonNode> records = records(out);
    assertEquals(12, records.size());
    assertEquals(lastHead, offset(records.get(11)));
    assertEquals(2 + FRAMES, records.get(11).at("/attributes/argus.lines").asInt());
  }

  @Test
  void aFinalRunIngestsTheOpenTraceAtOnce() throws Exception {
    Path file = writeLog(12, 4);
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    pipeline(localSink(out), checkpoints(dir.resolve("cp")), HOLD)
        .run(request(file).asFinalRun(), progress, new AtomicBoolean());

    assertEquals(0, progress.heldBackBytes());
    assertEquals(Files.size(file), progress.committedOffset());
    assertEquals(12, records(out).size());
  }

  @Test
  void aSingleLineRecordAtTheEndOfAFreshFileIsNotHeldBack() throws Exception {
    Path file = writeLog(11, 4);
    Files.writeString(file, Files.readString(file) + head(11, "INFO") + "\n");
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    pipeline(localSink(out), checkpoints(dir.resolve("cp")), HOLD)
        .run(request(file), progress, new AtomicBoolean());

    assertEquals(0, progress.heldBackBytes());
    assertEquals(12, records(out).size());
  }

  @Test
  void continuationLinesPastTheLimitsAreCountedAsTruncated() throws Exception {
    Path file = writeLog(4, 4);
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    pipeline(
            localSink(out),
            checkpoints(dir.resolve("cp")),
            new FramingProperties("auto", 3, null, Duration.ZERO))
        .run(request(file), progress, new AtomicBoolean());

    JsonNode trace = records(out).get(3);
    assertEquals(3, trace.at("/attributes/argus.lines").asInt());
    assertEquals(FRAMES - 1, trace.at("/attributes/" + RecordFramer.DROPPED_ATTRIBUTE).asInt());
    assertEquals(FRAMES - 1, progress.truncated());
    assertNotEquals(0, progress.committedOffset());
    assertFalse(trace.at("/attributes/argus.multiline.truncated").isMissingNode());
  }

  /** Bytes of record {@code i}'s head and trace, as {@link #writeLog} writes them. */
  private static long lastTraceBytes(int i) {
    long bytes = head(i, "ERROR").length() + 1;
    bytes += ("java.lang.IllegalStateException: boom " + i + "\n").length();
    for (int f = 0; f < FRAMES; f++) {
      bytes += ("\tat com.example.Orders.place" + f + "(Orders.java:" + i + ")\n").length();
    }
    return bytes;
  }
}
