package dev.dinesh.argus.controller;

import dev.dinesh.argus.filter.FilterSpec;
import dev.dinesh.argus.filter.LogFilter;
import dev.dinesh.argus.filter.impl.LevelFilter;
import dev.dinesh.argus.filter.impl.RegexFilter;
import dev.dinesh.argus.filter.impl.TimeRangeFilter;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.service.LogService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
  public List<LogRecord> parseLogs(@RequestParam String file) throws Exception {
    return logService.parseFile(file);
  }

  /**
   * Filters logs based on criteria in the request body.
   *
   * @param body filter parameters (file, from, to, levels, regex)
   * @return filtered list of LogRecord objects
   * @throws Exception if filtering fails
   */
  @PostMapping("/filter")
  public List<LogRecord> filterLogs(@RequestBody Map<String, Object> body) throws Exception {
    String file = (String) body.get("file");
    List<LogRecord> logs = logService.parseFile(file);

    Instant from = body.containsKey("from") ? Instant.parse((String) body.get("from")) : null;
    Instant to = body.containsKey("to") ? Instant.parse((String) body.get("to")) : null;
    Set<String> levels =
        body.containsKey("levels") ? new HashSet<>((List<String>) body.get("levels")) : Set.of();
    Pattern regex = body.containsKey("regex") ? Pattern.compile((String) body.get("regex")) : null;

    FilterSpec spec = new FilterSpec(from, to, levels, regex);
    return logService.filterLogs(logs, spec);
  }

  /**
   * Aggregates logs and returns summary statistics.
   *
   * @param body aggregation parameters (file, topN)
   * @return aggregation results
   * @throws Exception if aggregation fails
   */
  @PostMapping("/aggregate")
  public Map<String, Object> aggregateLogs(@RequestBody Map<String, Object> body) throws Exception {
    String file = (String) body.get("file");
    List<LogRecord> logs = logService.parseFile(file);

    Map<String, Long> byLevel = logService.countByLevel(logs);
    List<Map.Entry<String, Long>> topEndpoints =
        logService.topNEndpoints(logs, (int) body.getOrDefault("topN", 3));
    List<Map<String, Object>> errorRate = logService.errorRateOverTime(logs);

    return Map.of("byLevel", byLevel, "topEndpoints", topEndpoints, "errorRate", errorRate);
  }

  @PostMapping("/aggregateV2")
  public Map<String, Object> aggregateLogsV2(@RequestBody Map<String, Object> body)
      throws Exception {
    String file = (String) body.get("file");
    List<LogRecord> logs = logService.parseFile(file);

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

    List<LogRecord> filtered = logService.applyFilters(logs, filters);

    int topN = (int) body.getOrDefault("topN", 3);
    return logService.runAggregations(filtered, topN);
  }

  /**
   * Exports aggregated log data as JSON.
   *
   * @param body export parameters
   * @return JSON response with log data
   * @throws Exception if export fails
   */
  @PostMapping(value = "/export/json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> exportJson(@RequestBody Map<String, Object> body) throws Exception {
    Map<String, Object> results = aggregateLogs(body);
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
  public ResponseEntity<String> exportCsv(@RequestBody Map<String, Object> body) throws Exception {
    String file = (String) body.get("file");
    List<LogRecord> logs = logService.parseFile(file);
    List<Map<String, Object>> errorRate = logService.errorRateOverTime(logs);

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
  public ResponseEntity<String> cliSummary(@RequestBody Map<String, Object> body) throws Exception {
    String file = (String) body.get("file");
    List<LogRecord> logs = logService.parseFile(file);

    String summary = logService.printErrorSummary(logs);
    log.info("*********** CLI Summary - Processing ***********");
    log.info(summary);
    log.info("*********** CLI Summary - Processed ***********");

    return ResponseEntity.ok(summary);
  }
}
