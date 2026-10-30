package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code key=value} lines (logfmt), with {@code key="quoted value"} for values containing spaces. A
 * line must start with a pair, so access-log lines whose URLs contain {@code ?a=b} don't match.
 */
public class KeyValueLogParser implements LogParser {

  public static final String NAME = "kv";

  private static final Pattern LEADING_PAIR = Pattern.compile("^\\s*[\\w.]+=");
  // Shared with CanonicalLineParser. The lookbehind starts a key only where a run of key characters
  // starts, and the possessive run never backtracks: without them, find() over a long run with no
  // '='
  // retries from every position in it, which takes time quadratic in its length (1MB: ~45 minutes).
  static final Pattern KV = Pattern.compile("(?<![\\w.])([\\w.]++)=(\"[^\"]*\"|\\S*)");
  static final List<String> TIMESTAMP_KEYS = List.of("timestamp", "time", "ts");
  static final List<String> LEVEL_KEYS = List.of("level", "lvl", "severity");
  static final List<String> MESSAGE_KEYS = List.of("msg", "message");

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean canParse(String line) {
    return LEADING_PAIR.matcher(line).lookingAt();
  }

  @Override
  public LogRecord parse(String line) {
    if (!canParse(line)) {
      return null;
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    Matcher m = KV.matcher(line);
    while (m.find()) {
      String v = m.group(2);
      if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
        v = v.substring(1, v.length() - 1);
      }
      fields.put(m.group(1), v);
    }
    if (fields.isEmpty()) {
      return null;
    }
    Object timestamp = Fields.take(fields, TIMESTAMP_KEYS);
    LogRecord record =
        new LogRecord(
            Timestamps.parse(timestamp),
            Fields.asText(Fields.take(fields, LEVEL_KEYS)),
            Fields.asText(Fields.take(fields, MESSAGE_KEYS)),
            fields);
    if (record.getTimestamp() == null && timestamp != null) {
      fields.put("timestamp", timestamp);
    }
    return record;
  }
}
