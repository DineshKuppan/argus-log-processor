package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.pipeline.EnvelopeFactory;
import dev.dinesh.argus.seeder.DataSeeder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * P7a load checks ({@code P7_PLAN.md} §5), on a generated application log in which {@value
 * #TRACE_RATIO_TEXT} of records carry a Java stack trace. Each run is a separate JVM capped at
 * {@value #HEAP}.
 *
 * <ul>
 *   <li><b>T1:</b> the same file with framing {@code auto} and {@code none}: records, MB/s and peak
 *       heap; {@code auto} must still sustain T1 (4,150 lines/s) many times over.
 *   <li><b>T4:</b> {@code kill -9} mid-file with 8MB segments, so many traces straddle a segment
 *       end; after a restart every record is in the output exactly once, whole.
 * </ul>
 *
 * Run with {@code mvn test -Dgroups=load -DexcludedGroups=none -Dtest=FramingIT}; sizes with {@code
 * -Dargus.load.framing-size} (T1, default 1GB) and {@code -Dargus.load.framing-kill-size} (T4,
 * 256MB).
 */
@Tag("load")
class FramingIT {

  private static final String HEAP = "-Xmx256m";
  private static final double TRACE_RATIO = 0.05;
  private static final String TRACE_RATIO_TEXT = "5%";
  private static final String SIZE = System.getProperty("argus.load.framing-size", "1GB");
  private static final String KILL_SIZE =
      System.getProperty("argus.load.framing-kill-size", "256MB");
  private static final long T1_LINES_PER_SECOND = 4_150;

  @TempDir Path work;

  @Test
  void t1FramingKeepsIngestFarAboveThePeakRate() throws Exception {
    Path input = work.resolve("app.log");
    DataSeeder.Generated generated =
        DataSeeder.generateAppLogs(input, DataSize.parse(SIZE).toBytes(), TRACE_RATIO, 42L);

    Map<String, String> none = run(input, work.resolve("none"), "none", "500MB");
    Map<String, String> auto = run(input, work.resolve("auto"), "auto", "500MB");

    System.out.println("Framing T1: generated=" + generated);
    System.out.println("Framing T1 none: " + none);
    System.out.println("Framing T1 auto: " + auto);
    assertEquals("COMPLETED", auto.get("outcome"));
    assertEquals(generated.records(), num(auto, "records"));
    assertEquals(generated.traces(), num(auto, "multilineRecords"));
    assertEquals(0, num(auto, "unparsed"));
    assertEquals(generated.lines(), num(none, "records"), "framing none: a record per line");
    assertEquals(generated.bytes(), num(auto, "committedOffset"));
    double autoLinesPerSecond = generated.lines() / Double.parseDouble(auto.get("seconds"));
    double noneLinesPerSecond = generated.lines() / Double.parseDouble(none.get("seconds"));
    System.out.printf(
        "Framing T1: lines/s auto=%.0f none=%.0f (auto/none %.2f)%n",
        autoLinesPerSecond, noneLinesPerSecond, autoLinesPerSecond / noneLinesPerSecond);
    assertTrue(autoLinesPerSecond > 10 * T1_LINES_PER_SECOND);
  }

  @Test
  void t4AKillMidFileLeavesEveryRecordExactlyOnceAndWhole() throws Exception {
    Path input = work.resolve("app.log");
    DataSeeder.Generated generated =
        DataSeeder.generateAppLogs(input, DataSize.parse(KILL_SIZE).toBytes(), TRACE_RATIO, 7L);
    Path runDir = work.resolve("kill");
    Path out = runDir.resolve("out");

    Process first = start(input, runDir, "auto", "8MB", runDir.resolve("first.out"));
    long committedAtKill = awaitSegments(out, 4, first);
    first.destroyForcibly().waitFor(1, TimeUnit.MINUTES);
    Map<String, String> rerun = run(input, runDir, "auto", "8MB");

    System.out.println("Framing T4: generated=" + generated + " segmentsAtKill=" + committedAtKill);
    System.out.println("Framing T4 rerun: " + rerun);
    assertEquals("COMPLETED", rerun.get("outcome"));
    assertEquals("CHECKPOINT", rerun.get("resumeReason"));
    assertTrue(num(rerun, "startOffset") > 0);
    Output output = read(out);
    assertEquals(generated.records(), output.records, "every record, none twice");
    assertEquals(generated.records(), output.offsets.size());
    assertEquals(generated.traces(), output.multiline);
    assertEquals(generated.lines(), output.lines, "every line inside exactly one record");
    assertEquals(0, output.badIds);
  }

  private record Output(long records, long lines, long multiline, Set<Long> offsets, long badIds) {}

  private static Output read(Path out) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    long records = 0;
    long lines = 0;
    long multiline = 0;
    long badIds = 0;
    Set<Long> offsets = new HashSet<>();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(out)) {
      files = walk.filter(p -> p.toString().endsWith(".ndjson")).toList();
    }
    for (Path file : files) {
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          JsonNode record = mapper.readTree(line);
          long offset = record.at("/source/offset").asLong();
          int generation = record.at("/source/generation").asInt();
          offsets.add(offset);
          records++;
          JsonNode recordLines = record.at("/attributes/argus.lines");
          lines += recordLines.isMissingNode() ? 1 : recordLines.asInt();
          if (!recordLines.isMissingNode()) {
            multiline++;
          }
          if (!EnvelopeFactory.eventId("default", "app", generation, offset)
              .equals(record.get("eventId").asText())) {
            badIds++;
          }
        }
      }
    }
    return new Output(records, lines, multiline, offsets, badIds);
  }

  /** Waits until {@code count} segments are committed, then returns how many there are. */
  private static long awaitSegments(Path out, int count, Process process) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
    while (System.nanoTime() < deadline) {
      assertTrue(process.isAlive(), "the run finished before it could be killed");
      if (Files.exists(out)) {
        try (Stream<Path> walk = Files.walk(out)) {
          long segments = walk.filter(p -> p.toString().endsWith(".ndjson")).count();
          if (segments >= count) {
            return segments;
          }
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no " + count + " segments within 5 minutes");
  }

  private Process start(Path input, Path runDir, String framing, String segment, Path log)
      throws IOException {
    Files.createDirectories(runDir);
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            HEAP,
            "-XX:+ExitOnOutOfMemoryError",
            "-cp",
            System.getProperty("java.class.path"),
            FramingLoadRunner.class.getName(),
            input.toString(),
            runDir.toString(),
            framing,
            segment)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
  }

  private Map<String, String> run(Path input, Path runDir, String framing, String segment)
      throws Exception {
    Path log = runDir.resolve(framing + "-" + System.nanoTime() + ".out");
    Process process = start(input, runDir, framing, segment, log);
    boolean finished = process.waitFor(20, TimeUnit.MINUTES);
    String text = Files.readString(log, StandardCharsets.UTF_8);
    assertTrue(finished, "runner did not finish within 20 minutes");
    assertEquals(0, process.exitValue(), "runner failed:\n" + tail(text));
    String line =
        text.lines()
            .filter(l -> l.startsWith("RESULT "))
            .findFirst()
            .orElseThrow(() -> new IOException("no RESULT line:\n" + tail(text)));
    Map<String, String> result = new HashMap<>();
    Arrays.stream(line.substring("RESULT ".length()).split(" "))
        .map(kv -> kv.split("=", 2))
        .forEach(kv -> result.put(kv[0], kv[1]));
    return result;
  }

  private static long num(Map<String, String> result, String key) {
    return Long.parseLong(result.get(key));
  }

  private static String tail(String text) {
    List<String> lines = text.lines().toList();
    return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
  }
}
