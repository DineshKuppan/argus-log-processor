package dev.dinesh.argus.framing;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A source's framing as requested ({@code framing} on file jobs and streams): {@code auto}, {@code
 * none} or {@code start:<regex>}. Immutable; {@link #framer} makes a new framer for each run.
 */
public final class FramingSpec {

  public enum Mode {
    AUTO,
    NONE,
    START
  }

  public static final FramingSpec AUTO = new FramingSpec(Mode.AUTO, null);
  public static final FramingSpec NONE = new FramingSpec(Mode.NONE, null);

  static final String START_PREFIX = "start:";

  /** Longest start pattern accepted. */
  static final int MAX_PATTERN_LENGTH = 256;

  private final Mode mode;
  private final Pattern start;

  private FramingSpec(Mode mode, Pattern start) {
    this.mode = mode;
    this.start = start;
  }

  /**
   * Parses a requested framing; empty means "not requested" (the configured default applies).
   *
   * @throws IllegalArgumentException for an unknown value, or a start pattern that is empty, too
   *     long or invalid
   */
  public static Optional<FramingSpec> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    if (value.equals("auto")) {
      return Optional.of(AUTO);
    }
    if (value.equals("none")) {
      return Optional.of(NONE);
    }
    if (!value.startsWith(START_PREFIX)) {
      throw new IllegalArgumentException(
          "Unknown framing '" + value + "'; known: auto, none, start:<regex>");
    }
    String regex = value.substring(START_PREFIX.length());
    if (regex.isEmpty() || regex.length() > MAX_PATTERN_LENGTH) {
      throw new IllegalArgumentException(
          "A framing start pattern must be 1 to " + MAX_PATTERN_LENGTH + " characters");
    }
    try {
      return Optional.of(new FramingSpec(Mode.START, Pattern.compile(regex)));
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid framing start pattern: " + e.getDescription(), e);
    }
  }

  public Mode mode() {
    return mode;
  }

  /** A new framer for one run. */
  public RecordFramer framer(RecordFramer.Limits limits) {
    return switch (mode) {
      case AUTO -> new StackTraceFramer(limits);
      case NONE -> NoFraming.INSTANCE;
      case START -> new StartPatternFramer(start, limits);
    };
  }

  /** The value as requested, so a stream's framing can be stored and compared. */
  @Override
  public String toString() {
    return switch (mode) {
      case AUTO -> "auto";
      case NONE -> "none";
      case START -> START_PREFIX + start.pattern();
    };
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof FramingSpec other && toString().equals(other.toString());
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }
}
