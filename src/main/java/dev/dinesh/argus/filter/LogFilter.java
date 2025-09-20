package dev.dinesh.argus.filter;

import dev.dinesh.argus.model.LogRecord;

public interface LogFilter {

  boolean matches(LogRecord record);
}
