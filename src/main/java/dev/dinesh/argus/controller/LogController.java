package dev.dinesh.argus.controller;

import dev.dinesh.argus.filter.FilterSpec;
import dev.dinesh.argus.filter.LogFilter;
import dev.dinesh.argus.filter.impl.LevelFilter;
import dev.dinesh.argus.filter.impl.RegexFilter;
import dev.dinesh.argus.filter.impl.TimeRangeFilter;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.service.LogService;
import dev.dinesh.argus.service.LogService.AggregationSlotsBusyException;
import dev.dinesh.argus.tenant.CurrentTenant;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for log management operations. Provides endpoints for parsing, filtering,
 * aggregating, exporting, and summarizing logs.
 */
@RestController
@RequestMapping("/api/v1/logs")
@Slf4j
public class LogController {

  /** Seconds a client should wait before retrying when all aggregation slots are busy. */
  static final String RETRY_AFTER_SECONDS = "5";

  private final LogService logService;

  /**
   * Constructs a LogController with the given LogService.
   *
   * @param logService the log service to use
   */
  public LogController(LogService logService) {
    this.logService = logService;
  }

  /**
   * Parses logs from the specified file.
   *
   * @param file the log file path
   * @return list of parsed LogRecord objects
   * @throws Exception if parsing fails
   */
  @GetMapping("/parse")
  public List<LogRecord> parseLogs(@CurrentTenant String tenantId, @RequestParam String file)
      throws Exception {
    return logService.parseFile(tenantId, file);
  }

  /**
   * Parses only the lines of the specified file that match the given regex.
   *
   * @param file the log file path
   * @param pattern regex a line must fully match to be parsed
   * @return list of parsed LogRecord objects
   * @throws Exception if parsing fails
   */
  @GetMapping("/parse/regexp")
  public List<LogRecord> parseLogsWithPattern(
      @CurrentTenant String tenantId, @RequestParam String file, @RequestParam String pattern)
      throws Exception {

    return logService.parseFile(tenantId, file, pattern);
  }

  /**
   * Filters logs based on criteria in the request body.
   *
   * @param body filter parameters (file, from, to, levels, regex)
   * @return filtered list of LogRecord objects
   * @throws Exception if filtering fails
   */
  @PostMapping("/filter")
  public List<LogRecord> filterLogs(
      @CurrentTenant String tenantId, @RequestBody Map<String, Object> body) throws Exception {
    String file = (String) body.get("file");
    List<LogRecord> logs = logService.parseFile(tenantId, file);

    Instant from = body.containsKey("from") ? Instant.parse((String) body.get("from")) : null;
    Instant to = body.containsKey("to") ? Instant.parse((String) body.get("to")) : null;
    Set<String> levels =
        body.containsKey("levels") ? new HashSet<>((List<String>) body.get("levels")) : Set.of();
    Pattern regex = body.containsKey("regex") ? Pattern.compile((String) body.get("regex")) : null;

    FilterSpec spec = new FilterSpec(from, to, levels, regex);
    return logService.filterLogs(logs, spec);
  }

  /**
   * Aggregates logs and returns summary statistics. Streams the file, so it has no size limit.
   *
   * @param body aggregation parameters (file, topN)
   * @return aggregation results
   * @throws Exception if aggregation fails
   */
  @PostMapping("/aggregate")
  public Map<String, Object> aggregateLogs(
      @CurrentTenant String tenantId, @RequestBody Map<String, Object> body) throws Exception {
    return logService.summarize(
        tenantId, (String) body.get("file"), (int) body.getOrDefault("topN", 3));
  }

  /** Filters, then aggregates, in one streaming pass over the file (no size limit). */
  @PostMapping("/aggregateV2")
  public Map<String, Object> aggregateLogsV2(
      @CurrentTenant String tenantId, @RequestBody Map<String, Object> body) throws Exception {
    String file = (String) body.get("file");
    // Apply filters before aggregating
    List<LogFilter> filters = new ArrayList<>();
    if (body.containsKey("from") || body.containsKey("to")) {
      Instant from = body.containsKey("from") ? Instant.parse((String) body.get("from")) : null;
      Instant to = body.containsKey("to") ? Instant.parse((String) body.get("to")) : null;
      filters.add(new TimeRangeFilter(from, to));
    }
    if (body.containsKey("levels")) {
      Set<String> levels = new HashSet<>((List<String>) body.get("levels"));
      filters.add(new LevelFilter(levels));
    }
    if (body.containsKey("regex")) {
      filters.add(new RegexFilter((String) body.get("regex")));
    }

    int topN = (int) body.getOrDefault("topN", 3);
    return logService.aggregate(tenantId, file, filters, topN);
  }

  /**
   * Exports aggregated log data as JSON.
   *
   * @param body export parameters
   * @return JSON response with log data
   * @throws Exception if export fails
   */
  @PostMapping(value = "/export/json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> exportJson(
      @CurrentTenant String tenantId, @RequestBody Map<String, Object> body) throws Exception {
    Map<String, Object> results = aggregateLogs(tenantId, body);
    String json = logService.exportToJson(results);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=logs.json")
        .body(json);
  }

  /**
   * Exports error rate data as CSV.
   *
   * @param body export parameters
   * @return CSV response with error rate data
   * @throws Exception if export fails
   */
  @PostMapping(value = "/export/csv", produces = "text/csv")
  public ResponseEntity<String> exportCsv(
      @CurrentTenant String tenantId, @RequestBody Map<String, Object> body) throws Exception {
    List<Map<String, Object>> errorRate =
        logService.errorRateOverTime(tenantId, (String) body.get("file"));

    String csv = logService.exportErrorRateToCsv(errorRate);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=error_rate.csv")
        .body(csv);
  }

  /**
   * Returns a CLI-style summary of error logs.
   *
   * @param body summary parameters (file)
   * @return summary string
   * @throws Exception if summary generation fails
   */
  @PostMapping("/cli-summary")
  public ResponseEntity<String> cliSummary(
      @CurrentTenant String tenantId, @RequestBody Map<String, Object> body) throws Exception {
    String summary = logService.errorSummary(tenantId, (String) body.get("file"));
    log.info("*********** CLI Summary - Processing ***********");
    log.info(summary);
    log.info("*********** CLI Summary - Processed ***********");

    return ResponseEntity.ok(summary);
  }

  /** Bad input (a negative topN, a file over the size limit, a bad regex) is the client's error. */
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(NoSuchFileException.class)
  ResponseEntity<Map<String, String>> notFound(NoSuchFileException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "File not found: " + e.getFile()));
  }

  @ExceptionHandler(AggregationSlotsBusyException.class)
  ResponseEntity<Map<String, String>> tooManyRequests(AggregationSlotsBusyException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .body(Map.of("error", e.getMessage()));
  }
}
