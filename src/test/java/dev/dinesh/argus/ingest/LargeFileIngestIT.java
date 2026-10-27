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
 * Proves memory use does not grow with file size: a file several times larger than the heap is
 * ingested by a separate JVM capped at {@value #HEAP}. Excluded from {@code mvn test}; run with
 * {@code mvn test -Dgroups=load -DexcludedGroups=none} (needs ~4x the input size in free disk).
 */
@Tag("load")
class LargeFileIngestIT {

  private static final String HEAP = "-Xmx256m";
  private static final String INPUT_SIZE = System.getProperty("argus.load.input-size", "1GB");

  @TempDir Path work;

  @Test
  void ingestsAFileLargerThanTheHeapWithoutRunningOutOfMemory() throws Exception {
    Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
    Path output = work.resolve("runner.out");
    Process process =
        new ProcessBuilder(
                javaBin.toString(),
                HEAP,
                "-XX:+ExitOnOutOfMemoryError",
                "-cp",
                System.getProperty("java.class.path"),
                LargeFileIngestRunner.class.getName(),
                work.toString(),
                INPUT_SIZE)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();

    boolean finished = process.waitFor(20, TimeUnit.MINUTES);
    String log = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(finished, "runner did not finish within 20 minutes");
    assertEquals(0, process.exitValue(), "runner failed:\n" + tail(log));

    Map<String, String> result = parseResult(log);
    System.out.println("Large file ingest: " + result);
    assertEquals("COMPLETED", result.get("outcome"));
    assertEquals(result.get("generatedLines"), result.get("lines"));
    assertEquals("0", result.get("rejected"));
    assertEquals(result.get("inputBytes"), result.get("committedOffset"));
    assertTrue(Long.parseLong(result.get("inputBytes")) > 256L * 1024 * 1024);
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
