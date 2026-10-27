package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.StreamingAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.util.HashMap;
import java.util.Map;

public class CountByLevelAggregator
    implements StreamingAggregator<Map<String, Long>, Map<String, Long>> {

  /** Bucket for records without a level (JSON, kv and raw lines may have none). */
  public static final String NO_LEVEL = "-";

  static String levelOf(LogRecord record) {
    return record.getLevel() == null ? NO_LEVEL : record.getLevel();
  }

  @Override
  public Map<String, Long> newState() {
    return new HashMap<>();
  }

  @Override
  public void accept(Map<String, Long> counts, LogRecord record) {
    counts.merge(levelOf(record), 1L, Long::sum);
  }

  @Override
  public Map<String, Long> merge(Map<String, Long> left, Map<String, Long> right) {
    right.forEach((level, n) -> left.merge(level, n, Long::sum));
    return left;
  }

  @Override
  public Map<String, Long> result(Map<String, Long> counts) {
    return counts;
  }
}
