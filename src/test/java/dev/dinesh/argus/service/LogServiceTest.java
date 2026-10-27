package dev.dinesh.argus.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.filter.FilterSpec;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.ParserRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogServiceTest {

  private final LogService service = new LogService(new ParserRegistry());

  @TempDir Path dir;

  private String write(String... lines) throws IOException {
    Path file = dir.resolve("app.log");
    Files.write(file, List.of(lines));
    return file.toString();
  }

  @Test
  void parsesTheBundledJsonAndKubernetesSamples() throws Exception {
    List<LogRecord> json = service.parseFile("src/main/resources/logs/app.json");
    List<LogRecord> cri = service.parseFile("src/main/resources/logs/k8s.cri.txt");

    assertTrue(json.size() > 0);
    assertTrue(json.stream().allMatch(r -> r.getTimestamp() != null && r.getLevel() != null));
    assertEquals(
        Files.readAllLines(Path.of("src/main/resources/logs/k8s.cri.txt")).stream()
            .filter(l -> !l.isBlank())
            .count(),
        cri.size());
  }

  @Test
  void parsesStrayFormatsAndDropsLinesNothingUnderstands() throws Exception {
    String file =
        write(
            "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"ERROR\",\"message\":\"a\"}",
            "{\"timestamp\":\"2025-09-19T22:22:35Z\",\"level\":\"INFO\",\"message\":\"b\"}",
            "10.0.0.12 - - [19/Sep/2025:22:23:00 +0000] \"GET /x HTTP/1.1\" 200 1 \"-\" \"ua\"",
            "garbage that no parser understands");

    List<LogRecord> records = service.parseFile(file);

    assertEquals(3, records.size());
    assertEquals("GET /x HTTP/1.1", records.get(2).getMessage());
  }

  @Test
  void recordsWithoutTimestampsDoNotBreakTimeBasedViews() throws Exception {
    String file =
        write(
            "{\"level\":\"ERROR\",\"message\":\"no time\"}",
            "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"ERROR\",\"message\":\"timed\"}");
    List<LogRecord> logs = service.parseFile(file);

    Map<String, Object> aggregates = assertDoesNotThrow(() -> service.runAggregations(logs, 3));
    assertEquals(2, aggregates.get("totalLogs"));
    assertEquals(1, service.errorRateOverTime(logs).size());
    assertTrue(service.printErrorSummary(logs).contains("2025-09-19T22:00:00Z"));
    FilterSpec range = new FilterSpec(Instant.parse("2025-01-01T00:00:00Z"), null, Set.of(), null);
    assertEquals(1, service.filterLogs(logs, range).size());
    assertEquals(2, service.filterLogs(logs, new FilterSpec(null, null, Set.of(), null)).size());
  }
}
