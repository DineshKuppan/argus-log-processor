package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.ingest.IngestPipeline.Request;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.pipeline.EnvelopeFactory;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.sink.WalCapacity;
import dev.dinesh.argus.source.LogBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class IngestPipelineTest {

  /** 10 lines per batch, a segment every ~2.5KB of source (about 3 batches). */
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

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @TempDir Path dir;

  @AfterEach
  void noPipelineThreadsLeak() {
    assertTrue(
        Thread.getAllStackTraces().keySet().stream()
            .noneMatch(t -> t.isAlive() && t.getName().startsWith(IngestPipeline.THREAD_PREFIX)),
        "pipeline threads still alive after run");
  }

  private static String accessLine(int i) {
    int status = i % 7 == 0 ? 500 : 200;
    return String.format(
        "10.0.0.%d - - [09/Oct/2025:01:%02d:%02d +0000] \"GET /api/items/%d HTTP/1.1\" %d 512 \"-\" \"curl/8.0\"",
        i % 250, (i / 60) % 60, i % 60, i, status);
  }

  private Path writeLog(int lines) throws IOException {
    Path file = dir.resolve("in/app.log");
    Files.createDirectories(file.getParent());
    StringBuilder sb = new StringBuilder();
    IntStream.range(0, lines).forEach(i -> sb.append(accessLine(i)).append('\n'));
    Files.writeString(file, sb);
    return file;
  }

  private LocalVolumeSink localSink(Path out) {
    return new LocalVolumeSink(new LocalSinkProperties(out, DataSize.ofBytes(0)), MAPPER);
  }

  private FileCheckpointStore checkpoints(Path cpDir) {
    IngestProperties withDir =
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
            PROPS.keepUnparsed());
    return new FileCheckpointStore(withDir, MAPPER);
  }

  private IngestPipeline pipeline(LogSink sink, FileCheckpointStore checkpoints) {
    return new IngestPipeline(PROPS, new ParserRegistry(), sink, checkpoints);
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
    return records;
  }

  /** Output with the per-run ingest time removed, so replays can be compared byte for byte. */
  private static Map<String, String> withoutObservedTime(Map<String, String> files) {
    Map<String, String> normalized = new LinkedHashMap<>();
    files.forEach(
        (k, v) -> normalized.put(k, v.replaceAll("\"observedTimestamp\":\"[^\"]*\",", "")));
    return normalized;
  }

  private static long recordCount(Map<String, String> files) {
    return files.values().stream().mapToLong(s -> s.lines().count()).sum();
  }

  @Test
  void tenantsIngestingTheSameSourceShareNothing() throws Exception {
    Path file = writeLog(20);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    IngestPipeline pipeline = pipeline(localSink(out), cps);

    pipeline.run(
        new Request("acme", file, "app", false, null), new IngestProgress(), new AtomicBoolean());
    pipeline.run(
        new Request("globex", file, "app", false, null), new IngestProgress(), new AtomicBoolean());

    assertEquals(20, recordCount(outputFiles(out.resolve("tenant=acme"))));
    assertEquals(20, recordCount(outputFiles(out.resolve("tenant=globex"))));
    assertEquals(Files.size(file), cps.load("acme", "app").orElseThrow().byteOffset());
    assertEquals(Files.size(file), cps.load("globex", "app").orElseThrow().byteOffset());
    JsonNode acme = records(out.resolve("tenant=acme")).get(0);
    JsonNode globex = records(out.resolve("tenant=globex")).get(0);
    assertEquals("acme", acme.get("tenantId").asText());
    assertEquals("globex", globex.get("tenantId").asText());
    assertNotEquals(acme.get("eventId").asText(), globex.get("eventId").asText());
  }

  @Test
  void ingestsWholeFileInSegmentsAndCheckpointsTheEnd() throws Exception {
    Path file = writeLog(100);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    IngestProgress progress = new IngestProgress();

    Outcome outcome =
        pipeline(localSink(out), cps)
            .run(new Request("default", file, "app", false, null), progress, new AtomicBoolean());

    assertEquals(Outcome.COMPLETED, outcome);
    Map<String, String> files = outputFiles(out);
    assertEquals(100, recordCount(files));
    assertTrue(files.size() > 1, "expected several segments, got " + files.keySet());
    assertEquals(Files.size(file), cps.load("default", "app").orElseThrow().byteOffset());
    assertEquals(Files.size(file), progress.committedOffset());
    assertEquals(files.size(), progress.outputs().size());
    assertEquals(0, progress.rejected());
  }

  @Test
  void parsesStrayFormatsAndKeepsUnknownLinesAsRaw() throws Exception {
    Path file = writeLog(20);
    Files.writeString(file, "garbage line\n{\"json\":true}\n", StandardOpenOption.APPEND);
    IngestProgress progress = new IngestProgress();
    Path out = dir.resolve("out");

    pipeline(localSink(out), checkpoints(dir.resolve("cp")))
        .run(new Request("default", file, "app", false, null), progress, new AtomicBoolean());

    assertEquals("nginx_access", progress.format());
    assertEquals("detected", progress.formatSource());
    assertEquals(22, progress.lines());
    assertEquals(1, progress.fallbackParsed());
    assertEquals(1, progress.unparsed());
    assertEquals(0, progress.rejected());
    List<JsonNode> records = records(out);
    JsonNode garbage = records.get(20);
    assertEquals("garbage line", garbage.get("body").asText());
    assertEquals("raw", garbage.at("/source/format").asText());
    assertTrue(garbage.at("/attributes/argus.unparsed").asBoolean());
    assertEquals("json", records.get(21).at("/source/format").asText());
  }

  @Test
  void dropsUnknownLinesWhenKeepUnparsedIsOff() throws Exception {
    Path file = writeLog(20);
    Files.writeString(file, "garbage line\n", StandardOpenOption.APPEND);
    IngestProperties dropping =
        new IngestProperties(
            PROPS.batchMaxLines(),
            PROPS.batchMaxBytes(),
            PROPS.segmentBytes(),
            PROPS.rawQueueCapacity(),
            PROPS.parsedQueueCapacity(),
            PROPS.maxLineBytes(),
            PROPS.maxConcurrentJobs(),
            PROPS.allowedRoots(),
            null,
            false);
    IngestProgress progress = new IngestProgress();

    new IngestPipeline(
            dropping,
            new ParserRegistry(),
            localSink(dir.resolve("out")),
            checkpoints(dir.resolve("cp")))
        .run(new Request("default", file, "app", false, null), progress, new AtomicBoolean());

    assertEquals(20, progress.lines());
    assertEquals(1, progress.rejected());
    assertEquals(0, progress.unparsed());
  }

  @Test
  void writesOpenTelemetryEnvelopesWithDeterministicEventIds() throws Exception {
    Path file = dir.resolve("in/app.json");
    Files.createDirectories(file.getParent());
    Files.write(
        file,
        List.of(
            "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"warning\",\"message\":\"slow\","
                + "\"trace_id\":\"t1\",\"service\":\"orders\",\"latency_ms\":714}",
            "{\"timestamp\":\"2025-09-19T22:21:36Z\",\"level\":\"INFO\",\"message\":\"ok\"}"));
    Path out = dir.resolve("out");
    IngestProgress progress = new IngestProgress();

    pipeline(localSink(out), checkpoints(dir.resolve("cp")))
        .run(new Request("default", file, "api", false, null), progress, new AtomicBoolean());

    assertEquals("json", progress.format());
    JsonNode first = records(out).get(0);
    assertEquals("default", first.get("tenantId").asText());
    assertEquals("2025-09-19T22:21:35Z", first.get("timestamp").asText());
    assertTrue(first.has("observedTimestamp"));
    assertEquals("warning", first.get("severityText").asText());
    assertEquals(13, first.get("severityNumber").asInt());
    assertEquals("slow", first.get("body").asText());
    assertEquals("t1", first.get("traceId").asText());
    assertEquals("orders", first.at("/resource/service.name").asText());
    assertEquals(714, first.at("/attributes/latency_ms").asInt());
    assertEquals("api", first.at("/source/id").asText());
    assertEquals(0, first.at("/source/offset").asLong());
    assertEquals(
        new EnvelopeFactory("default", "api", 0)
            .create(
                new ParserRegistry()
                    .chain(new ParserRegistry().resolve("raw", null).orElseThrow(), true)
                    .parse("x"),
                0,
                Instant.EPOCH)
            .eventId(),
        first.get("eventId").asText());
    assertTrue(records(out).get(1).at("/source/offset").asLong() > 0);
  }

  @Test
  void requestedLogbackLayoutSkipsDetection() throws Exception {
    Path file = dir.resolve("in/app.log");
    Files.createDirectories(file.getParent());
    Files.write(
        file,
        List.of(
            "2025-10-26 18:12:00,123 [main] INFO  d.d.a.App - Started",
            "2025-10-26 18:12:01,000 [main] ERROR d.d.a.App - Failed"));
    IngestProgress progress = new IngestProgress();
    Path out = dir.resolve("out");

    pipeline(localSink(out), checkpoints(dir.resolve("cp")))
        .run(
            new Request(
                "default",
                file,
                "app",
                false,
                new ParserRegistry().resolve(null, "%d [%t] %-5p %c - %m%n").orElseThrow()),
            progress,
            new AtomicBoolean());

    assertEquals("logback", progress.format());
    assertEquals("requested", progress.formatSource());
    assertEquals(0, progress.unparsed());
    assertEquals(17, records(out).get(1).get("severityNumber").asInt());
    assertEquals("main", records(out).get(1).at("/attributes/thread.name").asText());
  }

  @Test
  void slowSinkBackpressuresTheReaderSoInFlightDataStaysBounded() throws Exception {
    Path file = writeLog(400);
    long batchBytes = Files.size(file) / 40; // 10 lines per batch, 40 batches
    IngestProgress progress = new IngestProgress();
    AtomicLong maxLagBytes = new AtomicLong();
    LocalVolumeSink delegate = localSink(dir.resolve("out"));
    LogSink slowSink =
        new ForwardingSink(delegate) {
          @Override
          void beforeWrite(LogBatch batch) throws IOException {
            try {
              Thread.sleep(20);
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            maxLagBytes.accumulateAndGet(progress.bytesRead() - batch.endOffset(), Math::max);
          }
        };

    pipeline(slowSink, checkpoints(dir.resolve("cp")))
        .run(new Request("default", file, "app", false, null), progress, new AtomicBoolean());

    // Read-ahead is limited to what the two queues and the reader/parser hands can hold.
    long maxBatchesAhead = PROPS.rawQueueCapacity() + PROPS.parsedQueueCapacity() + 2;
    assertTrue(
        maxLagBytes.get() <= (maxBatchesAhead + 1) * batchBytes,
        "reader ran " + maxLagBytes.get() + " bytes ahead of the writer");
    assertTrue(maxLagBytes.get() > 0, "expected the reader to run ahead a little");
  }

  @Test
  void rerunAfterAMidRunCrashProducesExactlyTheCleanRunOutput() throws Exception {
    Path file = writeLog(150);

    Path cleanOut = dir.resolve("clean-out");
    pipeline(localSink(cleanOut), checkpoints(dir.resolve("clean-cp")))
        .run(
            new Request("default", file, "app", false, null),
            new IngestProgress(),
            new AtomicBoolean());

    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    AtomicInteger commits = new AtomicInteger();
    LogSink crashingSink =
        new ForwardingSink(localSink(out)) {
          @Override
          void beforeCommit() throws IOException {
            if (commits.incrementAndGet() == 3) {
              throw new IOException("simulated crash while committing segment 3");
            }
          }
        };
    IOException crash =
        assertThrows(
            IOException.class,
            () ->
                pipeline(crashingSink, cps)
                    .run(
                        new Request("default", file, "app", false, null),
                        new IngestProgress(),
                        new AtomicBoolean()));
    assertTrue(crash.getMessage().contains("simulated crash"));

    IngestProgress rerun = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request("default", file, "app", false, null), rerun, new AtomicBoolean());

    assertEquals("CHECKPOINT", rerun.resumeReason());
    assertTrue(rerun.startOffset() > 0);
    assertEquals(withoutObservedTime(outputFiles(cleanOut)), withoutObservedTime(outputFiles(out)));
  }

  @Test
  void cancelStopsAtABatchBoundaryAndKeepsTheLastCommittedCheckpoint() throws Exception {
    Path file = writeLog(150);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    AtomicBoolean cancel = new AtomicBoolean();
    IngestProgress progress = new IngestProgress();
    LogSink cancellingSink =
        new ForwardingSink(localSink(out)) {
          @Override
          void afterCommit() {
            cancel.set(true);
          }
        };

    Outcome outcome =
        pipeline(cancellingSink, cps)
            .run(new Request("default", file, "app", false, null), progress, cancel);

    assertEquals(Outcome.CANCELLED, outcome);
    long checkpoint = cps.load("default", "app").orElseThrow().byteOffset();
    assertTrue(checkpoint > 0 && checkpoint < Files.size(file));
    assertTrue(outputFiles(out).keySet().stream().noneMatch(p -> p.endsWith(".part")));
  }

  @Test
  void cancelArrivingAfterTheFinalCommitStillReportsCompleted() throws Exception {
    Path file = writeLog(10); // well under one segment
    AtomicBoolean cancel = new AtomicBoolean();
    LogSink cancellingSink =
        new ForwardingSink(localSink(dir.resolve("out"))) {
          @Override
          void afterCommit() {
            cancel.set(true);
          }
        };

    Outcome outcome =
        pipeline(cancellingSink, checkpoints(dir.resolve("cp")))
            .run(new Request("default", file, "app", false, null), new IngestProgress(), cancel);

    assertEquals(Outcome.COMPLETED, outcome);
  }

  @Test
  void aLineStillBeingWrittenIsIngestedWholeOnTheNextRun() throws Exception {
    Path file = writeLog(5);
    String line = accessLine(5);
    Files.writeString(file, line.substring(0, 30), StandardOpenOption.APPEND);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));

    IngestProgress first = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request("default", file, "app", false, null), first, new AtomicBoolean());
    Files.writeString(file, line.substring(30) + "\n", StandardOpenOption.APPEND);
    IngestProgress second = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request("default", file, "app", false, null), second, new AtomicBoolean());

    assertEquals(5, first.lines());
    assertEquals(1, second.lines());
    assertEquals(0, first.rejected() + second.rejected());
    assertEquals(Files.size(file), cps.load("default", "app").orElseThrow().byteOffset());
  }

  @Test
  void copytruncateRotationKeepsTheEarlierFilesOutputAndIngestsTheNewOne() throws Exception {
    Path file = writeLog(30);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    pipeline(localSink(out), cps)
        .run(
            new Request("default", file, "app", false, null),
            new IngestProgress(),
            new AtomicBoolean());
    Map<String, String> beforeRotation = outputFiles(out);

    // Truncated in place (same inode), then regrown past the old checkpoint with new lines.
    StringBuilder rotated = new StringBuilder();
    IntStream.range(100, 160).forEach(i -> rotated.append(accessLine(i)).append('\n'));
    Files.writeString(file, rotated);
    IngestProgress rerun = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request("default", file, "app", false, null), rerun, new AtomicBoolean());

    assertEquals("FILE_REWRITTEN", rerun.resumeReason());
    assertEquals(0, rerun.startOffset());
    assertEquals(60, rerun.lines());
    Map<String, String> after = outputFiles(out);
    assertTrue(after.entrySet().containsAll(beforeRotation.entrySet()), "old output was replaced");
    assertEquals(90, recordCount(after));
    assertEquals(1, cps.load("default", "app").orElseThrow().generation());
  }

  @Test
  void rerunResumesFromCheckpointAndReadsOnlyAppendedLines() throws Exception {
    Path file = writeLog(30);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    pipeline(localSink(out), cps)
        .run(
            new Request("default", file, "app", false, null),
            new IngestProgress(),
            new AtomicBoolean());
    long firstEnd = Files.size(file);

    IngestProgress nothingNew = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request("default", file, "app", false, null), nothingNew, new AtomicBoolean());
    assertEquals(0, nothingNew.lines());

    Files.writeString(
        file, accessLine(30) + "\n" + accessLine(31) + "\n", StandardOpenOption.APPEND);
    IngestProgress appended = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request("default", file, "app", false, null), appended, new AtomicBoolean());

    assertEquals(firstEnd, appended.startOffset());
    assertEquals(2, appended.lines());
    assertEquals(32, recordCount(outputFiles(out)));
  }

  /** Decorates a sink with hooks so tests can slow it down or make it fail. */
  private abstract static class ForwardingSink implements LogSink {

    private final LogSink delegate;

    ForwardingSink(LogSink delegate) {
      this.delegate = delegate;
    }

    void beforeWrite(LogBatch batch) throws IOException {}

    void beforeCommit() throws IOException {}

    void afterCommit() {}

    @Override
    public void discardUncommitted(String tenantId, String sourceId) throws IOException {
      delegate.discardUncommitted(tenantId, sourceId);
    }

    @Override
    public SegmentWriter open(String tenantId, String sourceId, int generation, long startOffset)
        throws IOException {
      SegmentWriter writer = delegate.open(tenantId, sourceId, generation, startOffset);
      return new SegmentWriter() {
        @Override
        public void write(LogBatch batch) throws IOException {
          beforeWrite(batch);
          writer.write(batch);
        }

        @Override
        public String commit(long endOffset) throws IOException {
          beforeCommit();
          String location = writer.commit(endOffset);
          afterCommit();
          return location;
        }

        @Override
        public void abort() {
          writer.abort();
        }
      };
    }
  }

  /** A WAL gate the test opens and closes. */
  private static final class SwitchableCapacity implements WalCapacity {
    final AtomicBoolean full = new AtomicBoolean(true);

    @Override
    public boolean isFull() {
      return full.get();
    }

    @Override
    public boolean awaitRoom(BooleanSupplier stopping, Consumer<Boolean> paused) {
      if (!full.get()) {
        return true;
      }
      paused.accept(true);
      try {
        while (full.get()) {
          if (stopping.getAsBoolean()) {
            return false;
          }
          Thread.sleep(5);
        }
        return true;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        paused.accept(false);
      }
    }
  }

  private static void awaitPaused(IngestProgress progress) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (progress.pauseReason() == null && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals("WAL_FULL", progress.pauseReason());
  }

  @Test
  void aFullWalPausesTheRunWithBoundedReadAheadThenItResumes() throws Exception {
    Path file = writeLog(2_000);
    Path out = dir.resolve("out");
    SwitchableCapacity wal = new SwitchableCapacity();
    IngestProgress progress = new IngestProgress();
    IngestPipeline pipeline =
        new IngestPipeline(
            PROPS, new ParserRegistry(), localSink(out), checkpoints(dir.resolve("cp")), wal);
    CompletableFuture<Outcome> run =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return pipeline.run(
                    new Request("default", file, "app", false, null),
                    progress,
                    new AtomicBoolean());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    awaitPaused(progress);
    Thread.sleep(100); // let the reader run as far ahead as the queues allow
    assertEquals(0, progress.segmentsCommitted());
    // Raw (2) + parsed (4) queues and one batch in each stage: ~8 batches of 10 lines.
    assertTrue(progress.lines() <= 100, "parsed " + progress.lines() + " lines while paused");
    assertTrue(progress.bytesRead() < Files.size(file) / 4, "read " + progress.bytesRead());
    assertTrue(pipeline.walFull());

    wal.full.set(false);

    assertEquals(Outcome.COMPLETED, run.get(10, TimeUnit.SECONDS));
    assertEquals(2_000, progress.lines());
    assertTrue(progress.pausedMs() >= 100, "pausedMs " + progress.pausedMs());
    assertEquals(null, progress.pauseReason());
  }

  @Test
  void aRunPausedOnAFullWalCanBeCancelled() throws Exception {
    Path file = writeLog(500);
    SwitchableCapacity wal = new SwitchableCapacity();
    IngestProgress progress = new IngestProgress();
    AtomicBoolean cancel = new AtomicBoolean();
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    IngestPipeline pipeline =
        new IngestPipeline(PROPS, new ParserRegistry(), localSink(dir.resolve("out")), cps, wal);
    CompletableFuture<Outcome> run =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return pipeline.run(
                    new Request("default", file, "app", false, null), progress, cancel);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    awaitPaused(progress);
    cancel.set(true);

    assertEquals(Outcome.CANCELLED, run.get(10, TimeUnit.SECONDS));
    assertTrue(cps.load("default", "app").isEmpty());
  }

  @Test
  void anInterruptWhilePausedFailsTheRunInsteadOfLookingLikeACancel() throws Exception {
    Path file = writeLog(500);
    SwitchableCapacity wal = new SwitchableCapacity();
    IngestProgress progress = new IngestProgress();
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    IngestPipeline pipeline =
        new IngestPipeline(PROPS, new ParserRegistry(), localSink(dir.resolve("out")), cps, wal);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread runner =
        new Thread(
            () -> {
              try {
                pipeline.run(
                    new Request("default", file, "app", false, null),
                    progress,
                    new AtomicBoolean());
              } catch (Throwable t) {
                thrown.set(t);
              }
            });
    runner.start();

    awaitPaused(progress);
    runner.interrupt();
    runner.join(10_000);

    assertFalse(runner.isAlive());
    assertInstanceOf(InterruptedException.class, thrown.get());
    assertTrue(cps.load("default", "app").isEmpty());
  }
}
