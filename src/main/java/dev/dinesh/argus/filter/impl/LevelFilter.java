package dev.dinesh.argus.filter.impl;

import dev.dinesh.argus.filter.LogFilter;
import dev.dinesh.argus.model.LogRecord;
import java.util.Set;

public class LevelFilter implements LogFilter {
  private final Set<String> levels;

  public LevelFilter(Set<String> levels) {
    this.levels = levels;
  }

  @Override
  public boolean matches(LogRecord record) {
    if (levels == null || levels.isEmpty()) return true;
    return record.getLevel() != null && levels.contains(record.getLevel());
  }
}
