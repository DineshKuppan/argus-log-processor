package dev.dinesh.argus.model;

import java.util.Locale;
import java.util.Map;

/** OpenTelemetry severity, with the level spellings that real log producers use. */
public enum Severity {
  UNSPECIFIED(0),
  TRACE(1),
  DEBUG(5),
  INFO(9),
  WARN(13),
  ERROR(17),
  FATAL(21);

  private static final Map<String, Severity> BY_TEXT =
      Map.ofEntries(
          Map.entry("trace", TRACE),
          Map.entry("finest", TRACE),
          Map.entry("finer", TRACE),
          Map.entry("debug", DEBUG),
          Map.entry("dbg", DEBUG),
          Map.entry("fine", DEBUG),
          Map.entry("verbose", DEBUG),
          Map.entry("info", INFO),
          Map.entry("information", INFO),
          Map.entry("informational", INFO),
          Map.entry("notice", INFO),
          Map.entry("warn", WARN),
          Map.entry("warning", WARN),
          Map.entry("error", ERROR),
          Map.entry("err", ERROR),
          Map.entry("severe", ERROR),
          Map.entry("fatal", FATAL),
          Map.entry("critical", FATAL),
          Map.entry("crit", FATAL),
          Map.entry("alert", FATAL),
          Map.entry("emerg", FATAL),
          Map.entry("emergency", FATAL),
          Map.entry("panic", FATAL),
          // pino / bunyan numeric levels
          Map.entry("10", TRACE),
          Map.entry("20", DEBUG),
          Map.entry("30", INFO),
          Map.entry("40", WARN),
          Map.entry("50", ERROR),
          Map.entry("60", FATAL));

  private final int number;

  Severity(int number) {
    this.number = number;
  }

  public int number() {
    return number;
  }

  /** Maps a level as written in a log line; unknown or missing levels are {@link #UNSPECIFIED}. */
  public static Severity of(String level) {
    if (level == null) {
      return UNSPECIFIED;
    }
    return BY_TEXT.getOrDefault(level.strip().toLowerCase(Locale.ROOT), UNSPECIFIED);
  }
}
