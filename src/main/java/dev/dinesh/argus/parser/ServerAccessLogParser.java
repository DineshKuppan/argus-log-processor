package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Nginx/Apache combined access log lines, optionally followed by one extra token that is kept as
 * {@code category}. Access logs carry no level, so it is derived from the HTTP status class.
 */
@Slf4j
public class ServerAccessLogParser implements LogParser {

  public static final String NAME = "nginx_access";

  private static final Pattern ACCESS_LINE =
      Pattern.compile(
          "^(\\S+) (\\S+) (\\S+) \\[([^\\]]+)] \"([^\"]*)\" (\\d{3}) (\\d+|-) \"([^\"]*)\" \"([^\"]*)\"(?: (\\S+))?$");

  private static final DateTimeFormatter DTF =
      DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ROOT);

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean canParse(String line) {
    return line.indexOf(" [") > 0 && line.indexOf('"') > 0;
  }

  @Override
  public LogRecord parse(String line) {
    Matcher m = ACCESS_LINE.matcher(line);
    if (!m.matches()) {
      return null;
    }
    try {
      return toRecord(m);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Parses {@code line} with a caller-supplied regex using this class's group layout (groups 1-9,
   * optional group 10 as {@code category}). Kept for existing callers; prefer {@link #parse}.
   *
   * @throws IllegalArgumentException if the line does not match {@code pattern}
   */
  public LogRecord parseLine(String line, String pattern) {
    Matcher m = Pattern.compile(pattern).matcher(line);
    if (!m.matches()) {
      throw new IllegalArgumentException("Bad access line");
    }
    LogRecord record = toRecord(m);
    if (record.getMeta().containsKey("category")) {
      log.info("Custom Log Parser Name - {}", record.getMeta().get("category"));
    }
    return record;
  }

  private static LogRecord toRecord(Matcher m) {
    Instant ts = OffsetDateTime.parse(m.group(4), DTF).toInstant();
    int status = Integer.parseInt(m.group(6));
    Map<String, Object> meta = new HashMap<>();
    meta.put("ip", m.group(1));
    meta.put("status", status);
    meta.put("bytes", m.group(7));
    meta.put("ref", m.group(8));
    meta.put("ua", m.group(9));
    if (m.groupCount() >= 10 && m.group(10) != null) {
      meta.put("category", m.group(10));
    }
    return new LogRecord(ts, levelForStatus(status), m.group(5), meta);
  }

  private static String levelForStatus(int status) {
    if (status >= 500) {
      return "ERROR";
    }
    return status >= 400 ? "WARN" : "INFO";
  }
}
