package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.ingest.IngestPipeline.Request;
import dev.dinesh.argus.parser.LineParser;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.source.LogBatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
            cpDir);
    return new FileCheckpointStore(withDir, MAPPER);
  }

  private IngestPipeline pipeline(LogSink sink, FileCheckpointStore checkpoints) {
    return new IngestPipeline(PROPS, new LineParser(), sink, checkpoints);
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

  private static long recordCount(Map<String, String> files) {
    return files.values().stream().mapToLong(s -> s.lines().count()).sum();
  }

  @Test
  void ingestsWholeFileInSegmentsAndCheckpointsTheEnd() throws Exception {
    Path file = writeLog(100);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    IngestProgress progress = new IngestProgress();

    Outcome outcome =
        pipeline(localSink(out), cps)
            .run(new Request(file, "app", false), progress, new AtomicBoolean());

    assertEquals(Outcome.COMPLETED, outcome);
    Map<String, String> files = outputFiles(out);
    assertEquals(100, recordCount(files));
    assertTrue(files.size() > 1, "expected several segments, got " + files.keySet());
    assertEquals(Files.size(file), cps.load("app").orElseThrow().byteOffset());
    assertEquals(Files.size(file), progress.committedOffset());
    assertEquals(files.size(), progress.outputs().size());
    assertEquals(0, progress.rejected());
  }

  @Test
  void countsUnparseableLinesWithoutFailing() throws Exception {
    Path file = writeLog(20);
    Files.writeString(file, "garbage line\n{\"json\":true}\n", StandardOpenOption.APPEND);
    IngestProgress progress = new IngestProgress();

    pipeline(localSink(dir.resolve("out")), checkpoints(dir.resolve("cp")))
        .run(new Request(file, "app", false), progress, new AtomicBoolean());

    assertEquals(20, progress.lines());
    assertEquals(2, progress.rejected());
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
        .run(new Request(file, "app", false), progress, new AtomicBoolean());

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
        .run(new Request(file, "app", false), new IngestProgress(), new AtomicBoolean());

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
                        new Request(file, "app", false),
                        new IngestProgress(),
                        new AtomicBoolean()));
    assertTrue(crash.getMessage().contains("simulated crash"));

    IngestProgress rerun = new IngestProgress();
    pipeline(localSink(out), cps).run(new Request(file, "app", false), rerun, new AtomicBoolean());

    assertEquals("CHECKPOINT", rerun.resumeReason());
    assertTrue(rerun.startOffset() > 0);
    assertEquals(outputFiles(cleanOut), outputFiles(out));
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
        pipeline(cancellingSink, cps).run(new Request(file, "app", false), progress, cancel);

    assertEquals(Outcome.CANCELLED, outcome);
    long checkpoint = cps.load("app").orElseThrow().byteOffset();
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
            .run(new Request(file, "app", false), new IngestProgress(), cancel);

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
    pipeline(localSink(out), cps).run(new Request(file, "app", false), first, new AtomicBoolean());
    Files.writeString(file, line.substring(30) + "\n", StandardOpenOption.APPEND);
    IngestProgress second = new IngestProgress();
    pipeline(localSink(out), cps).run(new Request(file, "app", false), second, new AtomicBoolean());

    assertEquals(5, first.lines());
    assertEquals(1, second.lines());
    assertEquals(0, first.rejected() + second.rejected());
    assertEquals(Files.size(file), cps.load("app").orElseThrow().byteOffset());
  }

  @Test
  void copytruncateRotationKeepsTheEarlierFilesOutputAndIngestsTheNewOne() throws Exception {
    Path file = writeLog(30);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    pipeline(localSink(out), cps)
        .run(new Request(file, "app", false), new IngestProgress(), new AtomicBoolean());
    Map<String, String> beforeRotation = outputFiles(out);

    // Truncated in place (same inode), then regrown past the old checkpoint with new lines.
    StringBuilder rotated = new StringBuilder();
    IntStream.range(100, 160).forEach(i -> rotated.append(accessLine(i)).append('\n'));
    Files.writeString(file, rotated);
    IngestProgress rerun = new IngestProgress();
    pipeline(localSink(out), cps).run(new Request(file, "app", false), rerun, new AtomicBoolean());

    assertEquals("FILE_REWRITTEN", rerun.resumeReason());
    assertEquals(0, rerun.startOffset());
    assertEquals(60, rerun.lines());
    Map<String, String> after = outputFiles(out);
    assertTrue(after.entrySet().containsAll(beforeRotation.entrySet()), "old output was replaced");
    assertEquals(90, recordCount(after));
    assertEquals(1, cps.load("app").orElseThrow().generation());
  }

  @Test
  void rerunResumesFromCheckpointAndReadsOnlyAppendedLines() throws Exception {
    Path file = writeLog(30);
    Path out = dir.resolve("out");
    FileCheckpointStore cps = checkpoints(dir.resolve("cp"));
    pipeline(localSink(out), cps)
        .run(new Request(file, "app", false), new IngestProgress(), new AtomicBoolean());
    long firstEnd = Files.size(file);

    IngestProgress nothingNew = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request(file, "app", false), nothingNew, new AtomicBoolean());
    assertEquals(0, nothingNew.lines());

    Files.writeString(
        file, accessLine(30) + "\n" + accessLine(31) + "\n", StandardOpenOption.APPEND);
    IngestProgress appended = new IngestProgress();
    pipeline(localSink(out), cps)
        .run(new Request(file, "app", false), appended, new AtomicBoolean());

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
    public void discardUncommitted(String sourceId) throws IOException {
      delegate.discardUncommitted(sourceId);
    }

    @Override
    public SegmentWriter open(String sourceId, int generation, long startOffset)
        throws IOException {
      SegmentWriter writer = delegate.open(sourceId, generation, startOffset);
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
}
