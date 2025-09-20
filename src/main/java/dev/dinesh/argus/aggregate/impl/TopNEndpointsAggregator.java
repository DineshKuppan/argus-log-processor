package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.Aggregator;
import dev.dinesh.argus.model.LogRecord;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class TopNEndpointsAggregator implements Aggregator<List<Entry<String, Long>>> {

  private final int n;

  public TopNEndpointsAggregator(int n) {
    this.n = n;
  }

  @Override
  public List<Map.Entry<String, Long>> compute(List<LogRecord> records) {
    Map<String, Long> counts =
        records.stream()
            .map(LogRecord::getMessage)
            .filter(Objects::nonNull)
            .map(
                msg -> {
                  if (msg.contains(" ")) {
                    String[] parts = msg.split(" ");
                    if (parts.length >= 2) return parts[1]; // path from "GET /api/orders HTTP/1.1"
                  }
                  return "-";
                })
            .collect(Collectors.groupingBy(m -> m, Collectors.counting()));

    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(n)
        .toList();
  }
}
