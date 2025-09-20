package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.Aggregator;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class ErrorRateAggregator implements Aggregator<List<Map<String, Object>>> {
  @Override
  public List<Map<String, Object>> compute(List<LogRecord> records) {
    Map<Instant, long[]> buckets = new TreeMap<>();

    records.forEach(
        r -> {
          Instant minute = r.getTimestamp().truncatedTo(ChronoUnit.MINUTES);
          long[] pair = buckets.computeIfAbsent(minute, k -> new long[2]);
          pair[0]++; // total logs
          if ("ERROR".equalsIgnoreCase(r.getLevel())) pair[1]++; // error count
        });

    List<Map<String, Object>> out = new ArrayList<>();
    buckets.forEach(
        (k, v) -> {
          double rate = v[0] == 0 ? 0.0 : (double) v[1] / v[0];
          out.add(Map.of("minute", k.toString(), "total", v[0], "errors", v[1], "errorRate", rate));
        });
    return out;
  }
}
