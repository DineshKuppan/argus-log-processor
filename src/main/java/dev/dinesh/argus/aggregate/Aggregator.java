package dev.dinesh.argus.aggregate;

import dev.dinesh.argus.model.LogRecord;
import java.util.List;

public interface Aggregator<T> {
  T compute(List<LogRecord> records);
}
