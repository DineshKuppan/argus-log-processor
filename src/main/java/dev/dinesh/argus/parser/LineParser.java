package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses one Nginx/Apache access-log line into a {@link LogRecord}. Shared by {@code LogService}
 * and the streaming ingest pipeline so both parse identically. Replaced by a format-detecting
 * parser registry in P1.
 */
@Component
public class LineParser {

  private static final Pattern ACCESS_LINE =
      Pattern.compile(
          "^(\\S+) (\\S+) (\\S+) \\[([^\\]]+)] \"([^\"]*)\" (\\d{3}) (\\d+|-) \"([^\"]*)\" \"([^\"]*)\"$");
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");

  /** Returns the parsed record, or null if the line is not a valid access-log line. */
  public LogRecord parse(String line) {
    Matcher m = ACCESS_LINE.matcher(line);
    if (!m.matches()) {
      return null;
    }
    try {
      Instant ts = OffsetDateTime.parse(m.group(4), TIMESTAMP).toInstant();
      int status = Integer.parseInt(m.group(6));
      return new LogRecord(
          ts,
          levelForStatus(status),
          m.group(5),
          Map.of(
              "ip",
              m.group(1),
              "status",
              status,
              "bytes",
              m.group(7),
              "ref",
              m.group(8),
              "ua",
              m.group(9),
              "category",
              ""));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  // Access logs carry no level, so derive one from the HTTP status class.
  private static String levelForStatus(int status) {
    if (status >= 500) {
      return "ERROR";
    }
    return status >= 400 ? "WARN" : "INFO";
  }
}
