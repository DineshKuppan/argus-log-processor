package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.Aggregator;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class DurationAggregator implements Aggregator<List<Map<String, Object>>> {

  private final ChronoUnit unit;

  public DurationAggregator(ChronoUnit unit) {
    this.unit = unit;
  }

  @Override
  public List<Map<String, Object>> compute(List<LogRecord> records) {
    // Map<Hour, Map<LogLevel, Count>>
    Map<Instant, Map<String, Long>> buckets = new TreeMap<>();

    records.forEach(
        r -> {
          Instant hour = r.getTimestamp().truncatedTo(unit);
          String level = r.getLevel();
          Map<String, Long> levelMap = buckets.computeIfAbsent(hour, k -> new TreeMap<>());
          levelMap.put(level, levelMap.getOrDefault(level, 0L) + 1);
        });

    List<Map<String, Object>> out = new ArrayList<>();
    buckets.forEach(
        (hour, levelMap) -> {
          Map<String, Object> result = new TreeMap<>();
          result.put(unit.toString().toLowerCase(), hour.toString());
          result.putAll(levelMap);
          out.add(result);
        });
    return out;
  }
}
