package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.StreamingAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Total records, ERROR records and their ratio per time bucket, oldest first. */
public class ErrorRateAggregator
    implements StreamingAggregator<Map<Instant, long[]>, List<Map<String, Object>>> {

  private final ChronoUnit unit;
  private final String bucketKey;

  /** Hourly buckets, keyed {@code "hour"}. */
  public ErrorRateAggregator() {
    this(ChronoUnit.HOURS, "hour");
  }

  /**
   * @param bucketKey name of the bucket-start field in each result row, e.g. {@code "minute"}
   */
  public ErrorRateAggregator(ChronoUnit unit, String bucketKey) {
    this.unit = unit;
    this.bucketKey = bucketKey;
  }

  @Override
  public Map<Instant, long[]> newState() {
    return new TreeMap<>();
  }

  @Override
  public void accept(Map<Instant, long[]> buckets, LogRecord r) {
    // Records without an event time can't be bucketed.
    if (r.getTimestamp() == null) {
      return;
    }
    long[] totalAndErrors =
        buckets.computeIfAbsent(r.getTimestamp().truncatedTo(unit), k -> new long[2]);
    totalAndErrors[0]++;
    if ("ERROR".equalsIgnoreCase(r.getLevel())) {
      totalAndErrors[1]++;
    }
  }

  @Override
  public Map<Instant, long[]> merge(Map<Instant, long[]> left, Map<Instant, long[]> right) {
    right.forEach(
        (bucket, counts) -> {
          long[] sum = left.computeIfAbsent(bucket, k -> new long[2]);
          sum[0] += counts[0];
          sum[1] += counts[1];
        });
    return left;
  }

  @Override
  public List<Map<String, Object>> result(Map<Instant, long[]> buckets) {
    List<Map<String, Object>> out = new ArrayList<>();
    buckets.forEach(
        (bucket, v) -> {
          double rate = v[0] == 0 ? 0.0 : (double) v[1] / v[0];
          out.add(
              Map.of(
                  bucketKey, bucket.toString(), "total", v[0], "errors", v[1], "errorRate", rate));
        });
    return out;
  }
}
