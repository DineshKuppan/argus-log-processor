package dev.dinesh.argus.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.aggregate.impl.CountByLevelAggregator;
import dev.dinesh.argus.aggregate.impl.TopNEndpointsAggregator;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LogProperties;
import dev.dinesh.argus.filter.FilterSpec;
import dev.dinesh.argus.filter.impl.LevelFilter;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.source.AllowedPaths.OutsideAllowedRootsException;
import dev.dinesh.argus.tenant.TenantRoots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class LogServiceTest {

  @TempDir Path dir;

  private LogService service;

  /** v1 may read the temp dir and the bundled samples. */
  private IngestProperties ingest() {
    return new IngestProperties(
        0, null, null, 0, 0, null, 0, List.of(dir, Path.of("src/main/resources/logs")), null, null);
  }

  private LogService service(DataSize maxFileSize, int slots, boolean enforceAllowedRoots) {
    return new LogService(
        new ParserRegistry(),
        ingest(),
        new LogProperties(maxFileSize, slots, enforceAllowedRoots),
        TenantRoots.sharedOnly(ingest().allowedRoots()));
  }

  @BeforeEach
  void defaults() {
    service = service(null, 0, true);
  }

  private String write(String... lines) throws IOException {
    Path file = dir.resolve("app.log");
    Files.write(file, List.of(lines));
    return file.toString();
  }

  @Test
  void parsesTheBundledJsonAndKubernetesSamples() throws Exception {
    List<LogRecord> json = service.parseFile("default", "src/main/resources/logs/app.json");
    List<LogRecord> cri = service.parseFile("default", "src/main/resources/logs/k8s.cri.txt");

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

    List<LogRecord> records = service.parseFile("default", file);

    assertEquals(3, records.size());
    assertEquals("GET /x HTTP/1.1", records.get(2).getMessage());
  }

  @Test
  void recordsWithoutTimestampsDoNotBreakTimeBasedViews() throws Exception {
    String file =
        write(
            "{\"level\":\"ERROR\",\"message\":\"no time\"}",
            "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"ERROR\",\"message\":\"timed\"}");
    List<LogRecord> logs = service.parseFile("default", file);

    Map<String, Object> aggregates =
        assertDoesNotThrow(() -> service.aggregate("default", file, List.of(), 3));
    assertEquals(2L, aggregates.get("totalLogs"));
    assertEquals(1, service.errorRateOverTime("default", file).size());
    assertTrue(service.errorSummary("default", file).contains("2025-09-19T22:00:00Z"));
    FilterSpec range = new FilterSpec(Instant.parse("2025-01-01T00:00:00Z"), null, Set.of(), null);
    assertEquals(1, service.filterLogs(logs, range).size());
    assertEquals(2, service.filterLogs(logs, new FilterSpec(null, null, Set.of(), null)).size());
  }

  @Test
  void streamingAggregationMatchesTheListBasedResult() throws Exception {
    String file =
        write(
            IntStream.range(0, 500)
                .mapToObj(
                    i ->
                        String.format(
                            "10.0.0.%d - - [09/Oct/2025:%02d:%02d:00 +0000] \"GET /api/%d HTTP/1.1\""
                                + " %d 512 \"-\" \"curl/8.0\"",
                            i % 7, i / 60, i % 60, i % 9, i % 5 == 0 ? 500 : 200))
                .toArray(String[]::new));
    List<LogRecord> all = service.parseFile("default", file);
    assertEquals(500, all.size());

    Map<String, Object> summary = service.summarize("default", file, 5);

    assertEquals(new CountByLevelAggregator().compute(all), summary.get("byLevel"));
    assertEquals(new TopNEndpointsAggregator(5).compute(all), summary.get("topEndpoints"));
    Map<String, Object> errorsOnly =
        service.aggregate("default", file, List.of(new LevelFilter(Set.of("ERROR"))), 5);
    assertEquals(
        all.stream().filter(r -> "ERROR".equals(r.getLevel())).count(),
        errorsOnly.get("totalLogs"));
  }

  @Test
  void aggregationStreamsFilesOverTheSizeLimitAndCountsAnUnterminatedLastLine() throws Exception {
    Path file = dir.resolve("big.log");
    Files.writeString(
        file,
        "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"ERROR\",\"message\":\"a\"}\n"
            + "{\"timestamp\":\"2025-09-19T22:21:36Z\",\"level\":\"INFO\",\"message\":\"b\"}");
    service = service(DataSize.ofBytes(10), 0, true);

    assertThrows(
        IllegalArgumentException.class, () -> service.parseFile("default", file.toString()));
    Map<String, Object> aggregates = service.aggregate("default", file.toString(), List.of(), 3);
    assertEquals(2L, aggregates.get("totalLogs"));
    assertEquals(Map.of("ERROR", 1L, "INFO", 1L), aggregates.get("byLevel"));
  }

  @Test
  void rejectsAggregationsBeyondTheSlotsInsteadOfQueueing() throws Exception {
    LogService oneSlot = service(null, 1, true);
    String file = write("{\"level\":\"INFO\",\"message\":\"a\"}");
    CountDownLatch scanning = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () -> {
              try {
                oneSlot.forEachRecord(
                    "default",
                    file,
                    r -> {
                      scanning.countDown();
                      awaitQuietly(release);
                    });
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    assertTrue(scanning.await(5, TimeUnit.SECONDS));

    assertThrows(
        LogService.AggregationSlotsBusyException.class,
        () -> oneSlot.aggregate("default", file, List.of(), 3));
    release.countDown();
    first.get(5, TimeUnit.SECONDS);
    assertEquals(1L, oneSlot.aggregate("default", file, List.of(), 3).get("totalLogs"));
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void linesLongerThanIngestsLimitStillParse() throws Exception {
    String payload = "x".repeat(2 * 1024 * 1024);
    String file =
        write(
            "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"ERROR\",\"message\":\""
                + payload
                + "\"}");

    assertEquals(1L, service.aggregate("default", file, List.of(), 3).get("totalLogs"));
  }

  @Test
  void aNegativeTopNFailsBeforeTheFileIsRead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.aggregate("default", "/no/such/file/is/read", List.of(), -1));
  }

  @Test
  void refusesFilesOutsideTheAllowedRootsUnlessEnforcementIsOff(@TempDir Path elsewhere)
      throws Exception {
    Path outside = elsewhere.resolve("secret.log");
    Files.writeString(outside, "{\"level\":\"INFO\",\"message\":\"secret\"}\n");

    assertThrows(
        OutsideAllowedRootsException.class, () -> service.parseFile("default", outside.toString()));
    assertThrows(
        OutsideAllowedRootsException.class,
        () -> service.aggregate("default", outside.toString(), List.of(), 3));
    assertThrows(
        OutsideAllowedRootsException.class,
        () ->
            service.parseFile(
                "default",
                dir.resolve("../" + elsewhere.getFileName() + "/secret.log").toString()));
    LogService warnOnly = service(null, 0, false);
    assertEquals(1, warnOnly.parseFile("default", outside.toString()).size());
    assertEquals(
        1L, warnOnly.aggregate("default", outside.toString(), List.of(), 3).get("totalLogs"));
  }
}
