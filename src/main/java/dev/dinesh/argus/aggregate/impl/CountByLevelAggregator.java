package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.Aggregator;
import dev.dinesh.argus.model.LogRecord;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CountByLevelAggregator implements Aggregator<Map<String, Long>> {

  @Override
  public Map<String, Long> compute(List<LogRecord> records) {
    return records.stream()
        .collect(
            Collectors.groupingBy(
                r -> r.getLevel() == null ? "-" : r.getLevel(), Collectors.counting()));
  }
}
