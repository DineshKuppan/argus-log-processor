package dev.dinesh.argus.filter.impl;

import dev.dinesh.argus.filter.LogFilter;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;

public class TimeRangeFilter implements LogFilter {

  private final Instant from;
  private final Instant to;

  public TimeRangeFilter(Instant from, Instant to) {
    this.from = from;
    this.to = to;
  }

  @Override
  public boolean matches(LogRecord record) {
    Instant ts = record.getTimestamp();
    if (ts == null) {
      return false;
    }
    if (from != null && ts.isBefore(from)) {
      return false;
    }
    if (to != null && !ts.isBefore(to)) {
      return false;
    }
    return true;
  }
}
