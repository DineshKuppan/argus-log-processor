package dev.dinesh.argus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import dev.dinesh.argus.aggregate.impl.CountByLevelAggregator;
import dev.dinesh.argus.aggregate.impl.ErrorRateAggregator;
import dev.dinesh.argus.aggregate.impl.TopNEndpointsAggregator;
import dev.dinesh.argus.filter.FilterSpec;
import dev.dinesh.argus.filter.LogFilter;
import dev.dinesh.argus.model.LogRecord;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for parsing, filtering, analyzing, and exporting log records. Max File Limit: 50MB
 * (Configurable via application properties)
 */
@Service
@Slf4j
public class LogService {

  private final ObjectMapper mapper = new ObjectMapper();

  @Value("${log.max-file-size}")
  private long maxFileSize;

  private static final long DEFAULT_MAX_FILE_SIZE = 50L * 1024 * 1024;

  public static String getFileSizeInfo(Path path) throws Exception {
    long bytes = Files.size(path);
    double mb = bytes / (1024.0 * 1024.0);
    double gb = bytes / (1024.0 * 1024.0 * 1024.0);
    return String.format("Size: %d bytes, %.2f MB, %.4f GB", bytes, mb, gb);
  }

  public static String formatSmartSize(long bytes) {
    if (bytes < 1024 * 1024) {
      return bytes + " bytes";
    } else if (bytes < 1024L * 1024 * 1024) {
      double mb = bytes / (1024.0 * 1024.0);
      return String.format("%.2f MB", mb);
    } else {
      double gb = bytes / (1024.0 * 1024.0 * 1024.0);
      return String.format("%.4f GB", gb);
    }
  }

  public List<LogRecord> parseFile(String filePath) throws Exception {
    log.info("parsing file: {}", filePath);
    Path path = Path.of(filePath);
    log.info("Path exists: {}", Files.exists(path));
    long maxSize = maxFileSize > 0 ? maxFileSize : DEFAULT_MAX_FILE_SIZE;
    long fileSize = Files.size(path);
    log.info("File size: {}", formatSmartSize(fileSize));
    if (fileSize > maxSize) {
      throw new IllegalArgumentException("File size exceeds 50MB limit");
    }
    List<String> lines = Files.readAllLines(path);
    log.info("Total lines: {}", lines.size());
    return lines.stream()
        .filter(l -> !l.isBlank())
        .map(this::parseLineSafely)
        .filter(Objects::nonNull)
        .toList();
  }

  private LogRecord parseLineSafely(String line) {
    try {
      if (line.trim().startsWith("{")) {
        Map<String, Object> map = mapper.readValue(line, Map.class);
        return LogRecord.builder()
            .timestamp(
                map.containsKey("timestamp")
                    ? java.time.Instant.parse((String) map.get("timestamp"))
                    : java.time.Instant.now())
            .level((String) map.get("level"))
            .message((String) map.get("message"))
            .meta(map)
            .build();
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  public List<LogRecord> applyFilters(List<LogRecord> logs, List<LogFilter> filters) {
    return logs.stream().filter(log -> filters.stream().allMatch(f -> f.matches(log))).toList();
  }

  public Map<String, Object> runAggregations(List<LogRecord> logs, int topN) {
    Map<String, Object> results = new LinkedHashMap<>();

    results.put("totalLogs", logs.size());
    results.put("byLevel", new CountByLevelAggregator().compute(logs));
    results.put("topEndpoints", new TopNEndpointsAggregator(topN).compute(logs));
    results.put("errorRate", new ErrorRateAggregator().compute(logs));

    return results;
  }

  public List<LogRecord> filterLogs(List<LogRecord> logs, FilterSpec spec) {
    Predicate<LogRecord> pred = spec.toPredicate();
    return logs.stream().filter(pred).toList();
  }

  public Map<String, Long> countByLevel(List<LogRecord> logs) {
    return logs.stream()
        .collect(
            Collectors.groupingBy(
                r -> r.getLevel() == null ? "-" : r.getLevel(), Collectors.counting()));
  }

  public List<Map.Entry<String, Long>> topNEndpoints(List<LogRecord> logs, int n) {
    Map<String, Long> counts =
        logs.stream()
            .map(LogRecord::getMessage)
            .filter(Objects::nonNull)
            .map(
                msg -> {
                  if (msg.contains(" ")) {
                    String[] parts = msg.split(" ");
                    if (parts.length >= 2) {
                      return parts[1];
                    }
                  }
                  return "-";
                })
            .collect(Collectors.groupingBy(m -> m, Collectors.counting()));
    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(n)
        .toList();
  }

  public List<Map<String, Object>> errorRateOverTime(List<LogRecord> logs) {
    Map<java.time.Instant, long[]> buckets = new TreeMap<>();
    logs.forEach(
        r -> {
          java.time.Instant minute =
              r.getTimestamp().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
          long[] pair = buckets.computeIfAbsent(minute, k -> new long[2]);
          pair[0]++;
          if ("ERROR".equalsIgnoreCase(r.getLevel())) {
            pair[1]++;
          }
        });
    List<Map<String, Object>> out = new ArrayList<>();
    buckets.forEach(
        (k, v) -> {
          double rate = v[0] == 0 ? 0.0 : (double) v[1] / v[0];
          out.add(Map.of("minute", k.toString(), "total", v[0], "errors", v[1], "errorRate", rate));
        });
    return out;
  }

  public String exportToJson(Map<String, Object> results) throws Exception {
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
  }

  public String exportErrorRateToCsv(List<Map<String, Object>> errorRate) throws Exception {
    StringWriter sw = new StringWriter();
    try (CSVWriter writer = new CSVWriter(sw)) {
      writer.writeNext(new String[] {"minute", "total", "errors", "errorRate"});
      for (Map<String, Object> row : errorRate) {
        writer.writeNext(
            new String[] {
              row.get("minute").toString(),
              row.get("total").toString(),
              row.get("errors").toString(),
              row.get("errorRate").toString()
            });
      }
    }
    return sw.toString();
  }

  public String printErrorSummary(List<LogRecord> logs) {

    //    Map<String, Long> errorCounts = logs.stream()
    //        .filter(r -> "ERROR".equalsIgnoreCase(r.getLevel()))
    //        .collect(Collectors.groupingBy(
    //            r -> r.getTimestamp().truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString(),
    //            Collectors.counting()));
    //    errorCounts.forEach((hour, count) ->
    //        System.out.printf("%d ERROR logs between %s–%s\n", count, hour, hour + ":59"));

    Map<String, Long> errorCounts =
        logs.stream()
            .filter(r -> "ERROR".equalsIgnoreCase(r.getLevel()))
            .collect(
                Collectors.groupingBy(
                    r ->
                        r.getTimestamp()
                            .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                            .toString(),
                    Collectors.counting()));

    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-8s %-8s %-40s%n", "Total", "Level", "Time Range"));
    errorCounts.forEach(
        (hour, count) ->
            sb.append(
                String.format("%-8d %-8s logs between %s–%s:59%n", count, "ERROR", hour, hour)));
    return sb.toString();
  }
}
