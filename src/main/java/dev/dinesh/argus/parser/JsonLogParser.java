package dev.dinesh.argus.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.model.LogParser;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.Map;

/** Parser for JSON-formatted log entries. */
public class JsonLogParser implements LogParser {

  private final ObjectMapper om = new ObjectMapper();

  @Override
  public boolean canParse(String sample) {
    return sample.trim().startsWith("{") && sample.trim().endsWith("}");
  }

  @Override
  public LogRecord parseLine(String line) throws Exception {
    Map<String, Object> map = om.readValue(line, Map.class);
    Instant ts = Instant.parse((String) map.getOrDefault("timestamp", Instant.EPOCH.toString()));
    String level = (String) map.getOrDefault("level", null);
    String msg = (String) map.getOrDefault("message", null);
    return new LogRecord(ts, level, msg, map);
  }

  @Override
  public String name() {
    return "json";
  }
}
