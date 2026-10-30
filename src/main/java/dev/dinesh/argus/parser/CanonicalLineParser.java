package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical log lines ({@code P4_PLAN.md} D6): one wide line per request, as Stripe describes them,
 * {@code [2019-03-18 22:48:32.999] canonical-log-line http_method=POST http_path=/v1/charges
 * http_status=200 duration=0.050}. The marker may be spelled {@code canonical-log-line} or {@code
 * canonical_log_line}, after an optional {@code [timestamp]} or ISO 8601 timestamp. The pairs are
 * logfmt; numbers and booleans are typed, so they stay numbers in the output and on the tail.
 */
public class CanonicalLineParser implements LogParser {

  public static final String NAME = "canonical";

  private static final Pattern PREFIX =
      Pattern.compile(
          "^\\s*(?:\\[([^\\]]{1,64})]\\s+|(\\d{4}-\\d\\d-\\d\\d[T ]\\d\\d:\\d\\d:\\d\\d\\S*)\\s+)?"
              + "canonical[-_]log[-_]line(?=\\s|$)");
  // Only values that keep their value as numbers are typed: no leading zero (ids and zip codes stay
  // text) and at most 15 significant digits in a decimal (what a double holds exactly).
  private static final Pattern INTEGER = Pattern.compile("0|-?[1-9]\\d{0,17}");
  private static final Pattern DECIMAL = Pattern.compile("-?(?:0|[1-9]\\d*)\\.\\d+");
  private static final int DOUBLE_DIGITS = 15;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean canParse(String line) {
    return PREFIX.matcher(line).lookingAt();
  }

  @Override
  public LogRecord parse(String line) {
    Matcher prefix = PREFIX.matcher(line);
    if (!prefix.lookingAt()) {
      return null;
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    Matcher m = KeyValueLogParser.KV.matcher(line).region(prefix.end(), line.length());
    while (m.find()) {
      fields.put(m.group(1), value(m.group(2)));
    }
    String prefixTime = prefix.group(1) != null ? prefix.group(1) : prefix.group(2);
    Object timestamp = Fields.take(fields, KeyValueLogParser.TIMESTAMP_KEYS);
    Instant fromPrefix = Timestamps.parse(prefixTime);
    Instant fromField = Timestamps.parse(timestamp);
    Instant time = fromPrefix != null ? fromPrefix : fromField;
    // A time that isn't the event time (unparsable, or second to the prefix) stays an attribute.
    if (timestamp != null && (fromField == null || time != fromField)) {
      fields.put("timestamp", timestamp);
    }
    if (prefix.group(1) != null && fromPrefix == null) {
      // A bracket that isn't a time is usually a thread or request id: keep it, but not as a time.
      fields.putIfAbsent("prefix", prefix.group(1));
    } else if (prefixTime != null && fromPrefix == null) {
      fields.putIfAbsent("timestamp", prefixTime);
    }
    // No message unless the line has one: suppression then compares whole lines, so two requests'
    // canonical lines are never taken for copies of each other.
    return new LogRecord(
        time,
        Fields.asText(Fields.take(fields, KeyValueLogParser.LEVEL_KEYS)),
        Fields.asText(Fields.take(fields, KeyValueLogParser.MESSAGE_KEYS)),
        fields);
  }

  /** {@code "quoted"} stays text; bare integers, decimals and booleans are typed. */
  private static Object value(String raw) {
    if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
      return raw.substring(1, raw.length() - 1);
    }
    if (INTEGER.matcher(raw).matches()) {
      return Long.parseLong(raw);
    }
    if (DECIMAL.matcher(raw).matches() && digits(raw) <= DOUBLE_DIGITS) {
      return Double.parseDouble(raw);
    }
    if (raw.equals("true") || raw.equals("false")) {
      return Boolean.parseBoolean(raw);
    }
    return raw;
  }

  private static int digits(String decimal) {
    int start = decimal.startsWith("-") ? 1 : 0;
    String unsigned = decimal.substring(start).replace(".", "");
    String significant = unsigned.replaceFirst("^0+", "");
    return significant.isEmpty() ? 1 : significant.length();
  }
}
