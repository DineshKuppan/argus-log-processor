package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.StreamingAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * The {@code n} most requested endpoints (the path in {@code "GET /api/orders HTTP/1.1"}), counted
 * with the Misra–Gries summary so memory stays bounded however many distinct paths a file has.
 * Counts are exact while there are at most {@code capacity} distinct endpoints. Beyond that, every
 * endpoint seen more than {@code N / (capacity + 1)} times is kept, and its count is low by at most
 * that much.
 */
public class TopNEndpointsAggregator
    implements StreamingAggregator<Map<String, long[]>, List<Entry<String, Long>>> {

  public static final int DEFAULT_CAPACITY = 100_000;

  private static final String NO_ENDPOINT = "-";

  private final int n;
  private final int capacity;

  public TopNEndpointsAggregator(int n) {
    this(n, DEFAULT_CAPACITY);
  }

  public TopNEndpointsAggregator(int n, int capacity) {
    if (n < 0) {
      throw new IllegalArgumentException("topN must not be negative: " + n);
    }
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.n = n;
    this.capacity = capacity;
  }

  /** The second space-separated token, as {@code message.split(" ")[1]} but without the array. */
  static String endpointOf(String message) {
    int start = message.indexOf(' ') + 1;
    if (start == 0) {
      return NO_ENDPOINT;
    }
    int end = message.indexOf(' ', start);
    String endpoint = end < 0 ? message.substring(start) : message.substring(start, end);
    // split() drops trailing empty tokens: in "GET " or "GET   " there is no second token.
    boolean onlySpacesLeft = message.chars().skip(start).allMatch(c -> c == ' ');
    return endpoint.isEmpty() && onlySpacesLeft ? NO_ENDPOINT : endpoint;
  }

  @Override
  public Map<String, long[]> newState() {
    return new HashMap<>();
  }

  @Override
  public void accept(Map<String, long[]> counters, LogRecord record) {
    if (record.getMessage() == null) {
      return;
    }
    String endpoint = endpointOf(record.getMessage());
    long[] counter = counters.get(endpoint);
    if (counter != null) {
      counter[0]++;
    } else if (counters.size() < capacity) {
      counters.put(endpoint, new long[] {1});
    } else {
      // Full: this hit and one hit of every tracked endpoint cancel out. Each such round removes
      // capacity + 1 hits, so its O(capacity) cost is amortized O(1) per record.
      counters.values().forEach(c -> c[0]--);
      counters.values().removeIf(c -> c[0] == 0);
    }
  }

  @Override
  public Map<String, long[]> merge(Map<String, long[]> left, Map<String, long[]> right) {
    right.forEach((endpoint, c) -> left.merge(endpoint, c, (a, b) -> new long[] {a[0] + b[0]}));
    if (left.size() > capacity) {
      // Mergeable Misra–Gries: subtract the (capacity + 1)-th largest count from every counter.
      long[] sorted = left.values().stream().mapToLong(c -> c[0]).sorted().toArray();
      long cut = sorted[sorted.length - capacity - 1];
      left.values().forEach(c -> c[0] -= cut);
      left.values().removeIf(c -> c[0] <= 0);
    }
    return left;
  }

  @Override
  public List<Entry<String, Long>> result(Map<String, long[]> counters) {
    return counters.entrySet().stream()
        .map(e -> Map.entry(e.getKey(), e.getValue()[0]))
        .sorted(Entry.<String, Long>comparingByValue().reversed())
        .limit(n)
        .toList();
  }
}
