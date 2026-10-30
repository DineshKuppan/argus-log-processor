package dev.dinesh.argus.tail;

import dev.dinesh.argus.model.Severity;
import dev.dinesh.argus.source.SourceOffset;

/**
 * Which lines a tail connection receives; evaluated on the connection's thread, never on ingest's.
 *
 * @param minSeverity lines at least this severe (an OpenTelemetry number); 0 for every line,
 *     including lines without a level
 * @param source only this source or stream id, or null
 * @param query case-insensitive substring of the body, or null
 */
public record TailFilter(int minSeverity, String source, String query) {

  public static final int MAX_QUERY_CHARS = 256;
  public static final TailFilter ALL = new TailFilter(0, null, null);

  /**
   * @param level a level in any spelling {@link Severity} knows ({@code warn}, {@code WARNING},
   *     {@code 40}), or null
   * @throws IllegalArgumentException for an unknown level, an invalid source id or a long query
   */
  public static TailFilter parse(String level, String source, String query) {
    int minSeverity = 0;
    if (level != null && !level.isBlank()) {
      Severity severity = Severity.of(level);
      if (severity == Severity.UNSPECIFIED) {
        throw new IllegalArgumentException("Unknown level: " + level);
      }
      minSeverity = severity.number();
    }
    String sourceId =
        source == null || source.isBlank() ? null : SourceOffset.requireValidSourceId(source);
    String q = query == null || query.isEmpty() ? null : query;
    if (q != null && q.length() > MAX_QUERY_CHARS) {
      throw new IllegalArgumentException("q is longer than " + MAX_QUERY_CHARS + " characters");
    }
    return new TailFilter(minSeverity, sourceId, q);
  }

  public boolean matches(TailEvent event) {
    if (minSeverity > 0 && event.severityNumber() < minSeverity) {
      return false;
    }
    if (source != null && !source.equals(event.source())) {
      return false;
    }
    return query == null || containsIgnoreCase(event.body(), query);
  }

  /** Without lower-casing a copy of every body. */
  static boolean containsIgnoreCase(String text, String part) {
    if (text == null) {
      return false;
    }
    for (int i = 0, last = text.length() - part.length(); i <= last; i++) {
      if (text.regionMatches(true, i, part, 0, part.length())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return "level>=" + minSeverity + " source=" + source + " q=" + query;
  }
}
