package dev.dinesh.argus.ingest;

import java.util.Locale;

/**
 * Which slots a job runs on ({@code P3_PLAN.md} D8). Replays (re-reading a file from the start, or
 * an export) run on their own slots with a read budget, so they never starve live ingest.
 */
public enum Lane {
  LIVE,
  REPLAY;

  /**
   * Parses {@code live} or {@code replay} (any case); null or blank is null.
   *
   * @throws IllegalArgumentException for any other value
   */
  public static Lane parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("lane must be live or replay: " + value);
    }
  }
}
