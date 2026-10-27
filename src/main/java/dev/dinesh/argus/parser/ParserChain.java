package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.util.List;

/**
 * Parses each line with a source's primary parser, then with the other registered parsers in
 * priority order, and finally (if configured) keeps it as raw text. A {@code raw} primary means the
 * source has no structured format, so every line goes straight to the other parsers. Thread-safe.
 */
public final class ParserChain {

  /** Attribute set on records that no structured parser understood. */
  public static final String UNPARSED_ATTRIBUTE = "argus.unparsed";

  public enum Outcome {
    /** Parsed by the source's primary parser. */
    PRIMARY,
    /** Parsed by another registered parser. */
    FALLBACK,
    /** No parser understood the line; kept as raw text. */
    UNPARSED
  }

  /**
   * @param format name of the parser that produced {@code record}
   */
  public record Parsed(LogRecord record, String format, Outcome outcome) {}

  /** Null when the source has no structured primary format. */
  private final LogParser primary;

  private final List<LogParser> fallbacks;
  private final RawTextParser raw;

  ParserChain(LogParser primary, List<LogParser> fallbacks, RawTextParser raw) {
    this.primary = primary;
    this.fallbacks = List.copyOf(fallbacks);
    this.raw = raw;
  }

  /** Returns the parsed line, or null if nothing parsed it and unparsed lines are not kept. */
  public Parsed parse(String line) {
    LogRecord record = primary == null ? null : tryParse(primary, line);
    if (record != null) {
      return new Parsed(record, primary.name(), Outcome.PRIMARY);
    }
    for (LogParser parser : fallbacks) {
      record = tryParse(parser, line);
      if (record != null) {
        return new Parsed(record, parser.name(), Outcome.FALLBACK);
      }
    }
    if (raw == null) {
      return null;
    }
    record = raw.parse(line);
    record.getMeta().put(UNPARSED_ATTRIBUTE, true);
    return new Parsed(record, raw.name(), Outcome.UNPARSED);
  }

  private static LogRecord tryParse(LogParser parser, String line) {
    if (!parser.canParse(line)) {
      return null;
    }
    try {
      return parser.parse(line);
    } catch (RuntimeException e) {
      // The contract says parse never throws; one bad parser must not fail a whole job.
      return null;
    }
  }
}
