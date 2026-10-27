package dev.dinesh.argus.aggregate;

import dev.dinesh.argus.model.LogRecord;
import java.util.List;
import java.util.stream.Collector;

/**
 * An aggregation computed one record at a time, so its memory depends on the result, not on the
 * input: a file of any size can be aggregated in one pass. States of disjoint inputs can be merged,
 * which is the shape of {@link Collector} (see {@link #collector()}).
 *
 * @param <A> mutable accumulation state; not thread-safe, one per thread
 * @param <T> result type
 */
public interface StreamingAggregator<A, T> extends Aggregator<T> {

  A newState();

  void accept(A state, LogRecord record);

  /** Combines the states of two disjoint inputs; may reuse and return either argument. */
  A merge(A left, A right);

  T result(A state);

  default Collector<LogRecord, A, T> collector() {
    return Collector.of(this::newState, this::accept, this::merge, this::result);
  }

  @Override
  default T compute(List<LogRecord> records) {
    return records.stream().collect(collector());
  }
}
