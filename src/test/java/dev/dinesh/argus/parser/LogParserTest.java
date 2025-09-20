package dev.dinesh.argus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

public class LogParserTest {

  private final JsonLogParser parser = new JsonLogParser();
  private final KeyValueLogParser keyValueLogParser = new KeyValueLogParser();
  private final ServerAccessLogParser accessLogParser = new ServerAccessLogParser();

  @Test
  public void testParseValidJsonLine() {
    String line = "{\"timestamp\":\"2025-09-20T04:00:00Z\",\"level\":\"ERROR\",\"message\":\"Something failed\"}";
    assertTrue(parser.canParse(line));
    LogRecord record = null;
    try {
      record = parser.parseLine(line);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    assertEquals(Instant.parse("2025-09-20T04:00:00Z"), record.getTimestamp());
    assertEquals("ERROR", record.getLevel());
    assertEquals("Something failed", record.getMessage());
  }

  @Test
  public void testParseInvalidJsonLine() {
    String line = "not a json";
    assertFalse(parser.canParse(line));
    assertThrows(Exception.class, () -> parser.parseLine(line));
  }

  @Test
  public void testParseValidKeyValueLine() {
    String line = "timestamp=2025-09-20T04:00:00Z level=ERROR message=Something failed";
    assertTrue(keyValueLogParser.canParse(line));
    LogRecord record = keyValueLogParser.parseLine(line);
    assertEquals(Instant.parse("2025-09-20T04:00:00Z"), record.getTimestamp());
    assertEquals("ERROR", record.getLevel());
    assertEquals("Something", record.getMessage());
  }

  @Test
  public void testParseInvalidKeyValueLine() throws Exception {
    String line = "invalid log line";
    assertFalse(keyValueLogParser.canParse(line));
    assertThrows(Exception.class, () -> keyValueLogParser.parseLine(line));
  }

  @Test
  public void testParseValidAccessLogLine() {
    String line = "10.0.0.15 - - [19/Sept/2025:23:19:47 +0000] \"GET /health HTTP/1.1\" 502 1959 \"-\" \"PostmanRuntime/7.32.0\"";
    assertTrue(accessLogParser.canParse(line));
    LogRecord record = accessLogParser.parseLine(line);
    assertEquals(Instant.parse("2025-09-19T23:19:47Z"), record.getTimestamp());
    assertTrue(record.getMessage().contains("GET /health"));
  }

  @Test
  public void testParseInvalidAccessLogLine() {
    String line = "bad log";
    assertFalse(accessLogParser.canParse(line));
    assertThrows(Exception.class, () -> accessLogParser.parseLine(line));
  }
}