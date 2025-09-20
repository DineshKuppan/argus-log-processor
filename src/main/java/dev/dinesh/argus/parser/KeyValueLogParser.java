package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogParser;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for Key-Value log entries. */
public class KeyValueLogParser implements LogParser {

  private static final Pattern KV = Pattern.compile("(\\w+)=((\"[^\"]*\")|\\S+)");

  @Override
  public boolean canParse(String s) {
    return KV.matcher(s).find();
  }

  @Override
  public LogRecord parseLine(String line) {
    Matcher m = KV.matcher(line);
    Map<String, Object> map = new HashMap<>();
    while (m.find()) {
      String k = m.group(1);
      String v = m.group(2);
      if (v.startsWith("\"") && v.endsWith("\"")) {
        v = v.substring(1, v.length() - 1);
      }
      map.put(k, v);
    }
    Instant ts =
        map.containsKey("timestamp") ? Instant.parse((String) map.get("timestamp")) : Instant.EPOCH;
    String lvl = (String) map.getOrDefault("level", null);
    String msg = (String) map.getOrDefault("msg", map.getOrDefault("message", null));
    return new LogRecord(ts, lvl, msg, map);
  }

  @Override
  public String name() {
    return "kv";
  }
}
