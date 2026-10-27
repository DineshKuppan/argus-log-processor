package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accepts any line: the body is the line itself, with a leading ISO-8601 timestamp and the first
 * level word picked up when present. Used when no structured format fits, so no line is lost.
 */
public class RawTextParser implements LogParser {

  public static final String NAME = "raw";

  private static final Pattern LEADING_TIMESTAMP =
      Pattern.compile(
          "^\\[?(\\d{4}-\\d\\d-\\d\\d[T ]\\d\\d:\\d\\d:\\d\\d(?:[.,]\\d{1,9})?(?:Z|[+-]\\d\\d:?\\d\\d)?)");
  private static final Pattern LEVEL_WORD =
      Pattern.compile("\\b(TRACE|DEBUG|INFO|NOTICE|WARN|WARNING|ERROR|CRITICAL|FATAL)\\b");

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean canParse(String line) {
    return true;
  }

  @Override
  public LogRecord parse(String line) {
    Matcher ts = LEADING_TIMESTAMP.matcher(line);
    Matcher level = LEVEL_WORD.matcher(line);
    return new LogRecord(
        ts.lookingAt() ? Timestamps.parse(ts.group(1)) : null,
        level.find() ? level.group(1) : null,
        line,
        new HashMap<>());
  }
}
