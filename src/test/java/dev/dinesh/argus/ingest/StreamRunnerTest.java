package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.sink.LocalVolumeSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** Stream runs through the real pipeline, sink and checkpoint store. */
class StreamRunnerTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final Instant AT = Instant.parse("2026-10-28T12:00:00Z");

  @TempDir Path dir;

  private final AtomicLong nanos = new AtomicLong(1);
  private final SourceLeases leases = new SourceLeases();
  private StreamRegistry registry;
  private StreamRunner runner;
  private PushProperties push;
  private IngestProperties ingest;
  private FileCheckpointStore checkpoints;

  @AfterEach
  void tearDown() {
    if (runner != null) {
      runner.stop();
    }
    if (registry != null) {
      registry.close();
    }
  }

  private void start(long segmentBytes, long rotateBytes) throws IOException {
    push =
        new PushProperties(
            dir.resolve("spool"),
            null,
            0,
            0,
            Duration.ofMinutes(1),
            null,
            DataSize.ofBytes(rotateBytes),
            0,
            0,
            0,
            null);
    ingest =
        new IngestProperties(
            0,
            null,
            DataSize.ofBytes(segmentBytes),
            0,
            0,
            null,
            0,
            List.of(),
            dir.resolve("cp"),
            null);
    checkpoints = new FileCheckpointStore(ingest, MAPPER);
    restart();
  }

  /** A new registry and runner over the same directories, as after a process restart. */
  private void restart() throws IOException {
    if (registry != null) {
      registry.close();
    }
    registry = new StreamRegistry(push, checkpoints, leases, nanos::get);
    IngestPipeline pipeline =
        new IngestPipeline(
            ingest,
            new ParserRegistry(),
            new LocalVolumeSink(
                new LocalSinkProperties(dir.resolve("out"), DataSize.ofBytes(0)), MAPPER),
            checkpoints);
    runner = new StreamRunner(registry, pipeline, checkpoints, leases, push, ingest, nanos::get);
  }

  private PushStream append(String... lines) throws IOException {
    PushStream stream = registry.getOrCreate("acme", "app");
    byte[] bytes = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    stream.spool().append(bytes, bytes.length, null, AT);
    return stream;
  }

  private static String json(int i) {
    return "{\"ts\":\"2026-10-28T12:00:0" + i + "Z\",\"level\":\"INFO\",\"msg\":\"m" + i + "\"}";
  }

  /** Output files, relative to the sink root. */
  private List<String> outputs() throws IOException {
    Path out = dir.resolve("out");
    if (!Files.exists(out)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(out)) {
      return walk.filter(Files::isRegularFile)
          .map(p -> out.relativize(p).toString())
          .sorted()
          .toList();
    }
  }

  private long outputLines() throws IOException {
    long lines = 0;
    for (String file : outputs()) {
      lines += Files.readAllLines(dir.resolve("out").resolve(file)).size();
    }
    return lines;
  }

  @Test
  void aStreamIsDueOnceItsOldestLineHasWaitedTheFlushIntervalAndTheRunIngestsIt()
      throws IOException {
    start(1L << 30, 1L << 30);
    PushStream stream = append(json(1), json(2));
    assertFalse(runner.due(stream));

    nanos.addAndGet(Duration.ofMinutes(1).toNanos());
    assertTrue(runner.due(stream));

    assertEquals(Optional.of(Outcome.COMPLETED), runner.runNow(stream));
    assertEquals(
        List.of(
            "tenant=acme/dt=2026-10-28/source=app/gen=0/0-"
                + stream.spool().status().bytesOnDisk()
                + ".ndjson"),
        outputs());
    assertEquals(2, outputLines());
    assertEquals(0, stream.spool().pendingBytes());
    assertFalse(runner.due(stream));
    assertEquals("COMPLETED", stream.lastRun().outcome());
    assertEquals(2, stream.lastRun().progress().lines());
  }

  @Test
  void aStreamIsDueAtOnceWhenASegmentsWorthIsWaiting() throws IOException {
    start(100, 1L << 30);
    PushStream stream = append(json(1));
    assertFalse(runner.due(stream));

    append(json(2), json(3));

    assertTrue(runner.due(stream));
  }

  @Test
  void eachSpoolFileIsItsOwnGenerationAndIsDeletedOnceIngested() throws IOException {
    start(1L << 30, 10);
    append(json(1));
    PushStream stream = append(json(2));
    assertEquals(2, stream.spool().status().files());
    assertTrue(runner.due(stream), "a sealed file is due at once");

    runner.runNow(stream);
    assertFalse(Files.exists(dir.resolve("spool/tenant=acme/stream=app/000001.log")));
    nanos.addAndGet(Duration.ofMinutes(1).toNanos());
    runner.runNow(stream);

    List<String> outputs = outputs();
    assertEquals(2, outputs.size());
    assertTrue(outputs.get(0).contains("/gen=0/"), outputs.toString());
    assertTrue(outputs.get(1).contains("/gen=1/"), outputs.toString());
    assertEquals(2, outputLines());
  }

  @Test
  void aRunWaitsWhileAFileJobHoldsTheSource() throws IOException {
    start(1L << 30, 1L << 30);
    PushStream stream = append(json(1));
    nanos.addAndGet(Duration.ofMinutes(1).toNanos());
    leases.tryAcquire("acme", "app", "job-1");

    assertEquals(Optional.empty(), runner.runNow(stream));
    assertFalse(runner.due(stream), "retried after a delay");
    assertEquals(List.of(), outputs());

    leases.release("acme", "app", "job-1");
    nanos.addAndGet(StreamRunner.RETRY_DELAY.toNanos());
    assertTrue(runner.due(stream), "the line keeps its age");
    assertEquals(Optional.of(Outcome.COMPLETED), runner.runNow(stream));
  }

  @Test
  void afterARestartOnlyTheUningestedPartIsRead() throws IOException {
    start(1L << 30, 1L << 30);
    PushStream stream = append(json(1));
    nanos.addAndGet(Duration.ofMinutes(1).toNanos());
    runner.runNow(stream);
    long firstEnd = stream.spool().status().bytesOnDisk();

    restart();
    PushStream reopened = registry.find("acme", "app").orElseThrow();
    assertEquals(0, reopened.spool().pendingBytes(), "the checkpoint covers the spool");
    append(json(2));
    nanos.addAndGet(Duration.ofMinutes(1).toNanos());
    runner.runNow(reopened);

    assertTrue(
        outputs()
            .get(1)
            .endsWith(
                "/gen=0/" + firstEnd + "-" + reopened.spool().status().bytesOnDisk() + ".ndjson"),
        outputs().toString());
    assertEquals(2, outputLines());
  }

  @Test
  void theScannerRunsDueStreamsInTheBackground() throws Exception {
    start(1L << 30, 1L << 30);
    PushStream stream = append(json(1));
    nanos.addAndGet(Duration.ofMinutes(1).toNanos());
    runner.start();

    runner.scan();
    for (int i = 0; i < 250 && (stream.lastRun() == null || stream.scheduled.get()); i++) {
      Thread.sleep(20);
    }

    assertEquals("COMPLETED", stream.lastRun().outcome());
    assertEquals(1, outputLines());
  }
}
