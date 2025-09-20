package dev.dinesh.argus.filter;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Specification for filtering log records. Defines time range, log levels, and message regex for
 * filtering.
 *
 * @param from start timestamp (inclusive)
 * @param to end timestamp (exclusive)
 * @param levels set of log levels to include
 * @param messageRegex regex pattern to match log messages
 */
public record FilterSpec(Instant from, Instant to, Set<String> levels, Pattern messageRegex) {

  /**
   * Converts this filter specification to a predicate for LogRecord filtering.
   *
   * @return predicate that returns true if a LogRecord matches the filter criteria
   */
  public Predicate<LogRecord> toPredicate() {
    return record -> {
      if (from != null && record.getTimestamp().isBefore(from)) {
        return false;
      }
      if (to != null && !record.getTimestamp().isBefore(to)) {
        return false;
      }
      if (levels != null
          && !levels.isEmpty()
          && (record.getLevel() == null || !levels.contains(record.getLevel()))) {
        return false;
      }
      if (messageRegex != null
          && (record.getMessage() == null || !messageRegex.matcher(record.getMessage()).find())) {
        return false;
      }
      return true;
    };
  }
}
