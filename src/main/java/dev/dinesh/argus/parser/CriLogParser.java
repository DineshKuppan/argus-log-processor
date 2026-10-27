package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kubernetes container runtime (CRI) log lines, as found under {@code /var/log/pods}: {@code
 * <RFC3339Nano> <stdout|stderr> <F|P> <body>}. A JSON body (an app logging JSON to stdout) is
 * parsed as JSON, keeping the runtime's timestamp when the body has none.
 */
public class CriLogParser implements LogParser {

  public static final String NAME = "cri";

  private static final Pattern CRI_LINE = Pattern.compile("^(\\S+) (stdout|stderr) ([FP]) ?(.*)$");

  private final JsonLogParser json = new JsonLogParser();

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean canParse(String line) {
    return line.length() > 20
        && line.charAt(4) == '-'
        && (line.contains(" stdout ") || line.contains(" stderr "));
  }

  @Override
  public LogRecord parse(String line) {
    Matcher m = CRI_LINE.matcher(line);
    if (!m.matches()) {
      return null;
    }
    Instant ts;
    try {
      ts = OffsetDateTime.parse(m.group(1)).toInstant();
    } catch (DateTimeException e) {
      return null;
    }
    String body = m.group(4);
    LogRecord inner = json.canParse(body) ? json.parse(body) : null;
    LogRecord record = inner != null ? inner : new LogRecord(ts, null, body, new HashMap<>());
    if (record.getTimestamp() == null) {
      record.setTimestamp(ts);
    }
    Map<String, Object> meta = record.getMeta();
    meta.put("log.iostream", m.group(2));
    meta.put("logtag", m.group(3));
    return record;
  }
}
