package dev.dinesh.argus.aggregate.impl;

import dev.dinesh.argus.aggregate.StreamingAggregator;
import dev.dinesh.argus.model.LogRecord;

/** Number of records. */
public class CountAggregator implements StreamingAggregator<long[], Long> {

  @Override
  public long[] newState() {
    return new long[1];
  }

  @Override
  public void accept(long[] count, LogRecord record) {
    count[0]++;
  }

  @Override
  public long[] merge(long[] left, long[] right) {
    left[0] += right[0];
    return left;
  }

  @Override
  public Long result(long[] count) {
    return count[0];
  }
}
