package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.StreamingAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts records per level in each time bucket of {@code unit}. */
public class DurationAggregator
    implements StreamingAggregator<Map<Instant, Map<String, Long>>, List<Map<String, Object>>> {

  private final ChronoUnit unit;

  public DurationAggregator(ChronoUnit unit) {
    this.unit = unit;
  }

  @Override
  public Map<Instant, Map<String, Long>> newState() {
    return new TreeMap<>();
  }

  @Override
  public void accept(Map<Instant, Map<String, Long>> buckets, LogRecord r) {
    // Records without an event time can't be bucketed.
    if (r.getTimestamp() == null) {
      return;
    }
    buckets
        .computeIfAbsent(r.getTimestamp().truncatedTo(unit), k -> new TreeMap<>())
        .merge(CountByLevelAggregator.levelOf(r), 1L, Long::sum);
  }

  @Override
  public Map<Instant, Map<String, Long>> merge(
      Map<Instant, Map<String, Long>> left, Map<Instant, Map<String, Long>> right) {
    right.forEach(
        (bucket, levels) ->
            levels.forEach(
                (level, n) ->
                    left.computeIfAbsent(bucket, k -> new TreeMap<>()).merge(level, n, Long::sum)));
    return left;
  }

  @Override
  public List<Map<String, Object>> result(Map<Instant, Map<String, Long>> buckets) {
    List<Map<String, Object>> out = new ArrayList<>();
    buckets.forEach(
        (bucket, levelMap) -> {
          Map<String, Object> result = new TreeMap<>();
          result.put(unit.toString().toLowerCase(), bucket.toString());
          result.putAll(levelMap);
          out.add(result);
        });
    return out;
  }
}
