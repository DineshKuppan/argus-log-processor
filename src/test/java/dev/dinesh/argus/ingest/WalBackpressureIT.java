package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T3, scaled down: the archive store is down while a file larger than the heap is ingested. The
 * ingest must pause at the WAL's high watermark instead of failing or running out of memory, the
 * WAL must stay near its cap, and once the store is back everything must be archived and the job
 * must complete. The design's 10-minute outage is {@code -Dargus.load.outage-seconds} (default 30)
 * here. Run with {@code mvn test -Dgroups=load -DexcludedGroups=none -Dtest=WalBackpressureIT}.
 */
@Tag("load")
class WalBackpressureIT {

  private static final String HEAP = "-Xmx256m";
  private static final String INPUT_SIZE = System.getProperty("argus.load.input-size", "500MB");
  private static final String OUTAGE_SECONDS =
      System.getProperty("argus.load.outage-seconds", "30");

  @TempDir Path work;

  @Test
  void anArchiveOutagePausesIngestAtTheWatermarkAndEverythingArrivesAfterwards() throws Exception {
    Path output = work.resolve("runner.out");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                HEAP,
                "-XX:+ExitOnOutOfMemoryError",
                "-cp",
                System.getProperty("java.class.path"),
                WalBackpressureRunner.class.getName(),
                work.toString(),
                INPUT_SIZE,
                OUTAGE_SECONDS)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();

    boolean finished = process.waitFor(20, TimeUnit.MINUTES);
    String log = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(finished, "runner did not finish within 20 minutes");
    assertEquals(0, process.exitValue(), "runner failed:\n" + tail(log));

    Map<String, String> r = parseResult(log);
    System.out.println("WAL backpressure (T3): " + r);
    assertEquals("COMPLETED", r.get("outcome"), "the outage must pause ingest, not fail it");
    assertEquals(r.get("generatedLines"), r.get("lines"));
    assertEquals(r.get("lines"), r.get("archivedRows"), "every line archived after the outage");
    assertEquals("0", r.get("walLeftBytes"));
    assertTrue(Long.parseLong(r.get("failedPuts")) > 0, "the outage happened");
    assertTrue(Long.parseLong(r.get("pausedMs")) > 0, "ingest paused at the watermark");
    // The gate measures before each segment, so one job's WAL passes the high watermark by at most
    // the segment it is writing.
    long high = Long.parseLong(r.get("highWatermarkBytes"));
    long segment = Long.parseLong(r.get("segmentBytesMax"));
    long maxPending = Long.parseLong(r.get("maxPendingBytes"));
    assertTrue(maxPending <= high + segment, "WAL reached " + maxPending + " bytes");
  }

  private static Map<String, String> parseResult(String log) throws IOException {
    String line =
        log.lines()
            .filter(l -> l.startsWith("RESULT "))
            .findFirst()
            .orElseThrow(() -> new IOException("no RESULT line in runner output:\n" + tail(log)));
    Map<String, String> result = new HashMap<>();
    Arrays.stream(line.substring("RESULT ".length()).split(" "))
        .map(kv -> kv.split("=", 2))
        .forEach(kv -> result.put(kv[0], kv[1]));
    return result;
  }

  private static String tail(String log) {
    List<String> lines = log.lines().toList();
    return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
  }
}
