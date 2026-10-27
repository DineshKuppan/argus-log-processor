package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.StreamingAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.util.HashMap;
import java.util.Map;

public class CountByLevelAggregator
    implements StreamingAggregator<Map<String, Long>, Map<String, Long>> {

  /** Bucket for records without a level (JSON, kv and raw lines may have none). */
  public static final String NO_LEVEL = "-";

  /**
   * JSON and kv levels are free text, so distinct levels are capped to keep the state bounded;
   * levels first seen after the cap is reached are counted under {@link #OTHER_LEVELS}.
   */
  public static final int MAX_LEVELS = 64;

  public static final String OTHER_LEVELS = "other";

  static String levelOf(LogRecord record) {
    return record.getLevel() == null ? NO_LEVEL : record.getLevel();
  }

  /** Adds {@code n} to {@code level}'s count, or to {@link #OTHER_LEVELS} once the cap is hit. */
  static void count(Map<String, Long> counts, String level, long n) {
    String key = counts.containsKey(level) || counts.size() < MAX_LEVELS ? level : OTHER_LEVELS;
    counts.merge(key, n, Long::sum);
  }

  @Override
  public Map<String, Long> newState() {
    return new HashMap<>();
  }

  @Override
  public void accept(Map<String, Long> counts, LogRecord record) {
    count(counts, levelOf(record), 1);
  }

  @Override
  public Map<String, Long> merge(Map<String, Long> left, Map<String, Long> right) {
    right.forEach((level, n) -> count(left, level, n));
    return left;
  }

  @Override
  public Map<String, Long> result(Map<String, Long> counts) {
    return counts;
  }
}
