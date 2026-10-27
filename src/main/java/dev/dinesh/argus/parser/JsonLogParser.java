package dev.dinesh.argus.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.model.LogRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JSON object per line (NDJSON), as written by Logstash/ECS encoders, pino, bunyan, zap and
 * most cloud log exports. The timestamp, level and message fields are recognised under their common
 * names and removed from the attributes; every other field is kept as an attribute.
 */
public class JsonLogParser implements LogParser {

  public static final String NAME = "json";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
  private static final List<String> TIMESTAMP_KEYS =
      List.of("timestamp", "@timestamp", "time", "ts");
  private static final List<String> LEVEL_KEYS =
      List.of("level", "severity", "log.level", "levelname", "lvl");
  private static final List<String> MESSAGE_KEYS = List.of("message", "msg", "body", "log");

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean canParse(String line) {
    String s = line.strip();
    return s.startsWith("{") && s.endsWith("}");
  }

  @Override
  public LogRecord parse(String line) {
    if (!canParse(line)) {
      return null;
    }
    Map<String, Object> fields;
    try {
      fields = MAPPER.readValue(line, MAP);
    } catch (JsonProcessingException e) {
      return null;
    }
    Object timestamp = take(fields, TIMESTAMP_KEYS);
    LogRecord record =
        new LogRecord(
            Timestamps.parse(timestamp),
            asText(take(fields, LEVEL_KEYS)),
            asText(take(fields, MESSAGE_KEYS)),
            fields);
    if (record.getTimestamp() == null && timestamp != null) {
      fields.put("timestamp", timestamp); // unparseable: keep the original value
    }
    return record;
  }

  /** Removes and returns the first present key's value. */
  static Object take(Map<String, Object> fields, List<String> keys) {
    for (String key : keys) {
      if (fields.containsKey(key)) {
        return fields.remove(key);
      }
    }
    return null;
  }

  static String asText(Object value) {
    return value == null ? null : value.toString();
  }
}
