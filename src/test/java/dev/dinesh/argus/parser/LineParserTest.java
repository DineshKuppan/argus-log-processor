package dev.dinesh.argus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LineParserTest {

  private final LineParser parser = new LineParser();

  private static String accessLine(int status) {
    return "10.0.0.12 - - [09/Oct/2025:01:11:40 +0000] \"POST /api/orders HTTP/1.1\" "
        + status
        + " 1467 \"-\" \"Mozilla/5.0\"";
  }

  @Test
  void parsesAnAccessLogLine() {
    LogRecord record = parser.parse(accessLine(200));

    assertEquals(Instant.parse("2025-10-09T01:11:40Z"), record.getTimestamp());
    assertEquals("POST /api/orders HTTP/1.1", record.getMessage());
    assertEquals("10.0.0.12", record.getMeta().get("ip"));
    assertEquals(200, record.getMeta().get("status"));
  }

  @Test
  void derivesLevelFromHttpStatusClass() {
    assertEquals("INFO", parser.parse(accessLine(302)).getLevel());
    assertEquals("WARN", parser.parse(accessLine(404)).getLevel());
    assertEquals("ERROR", parser.parse(accessLine(503)).getLevel());
  }

  @Test
  void returnsNullForLinesThatAreNotAccessLogs() {
    assertNull(parser.parse("{\"level\":\"INFO\",\"message\":\"json\"}"));
    assertNull(parser.parse("10.0.0.12 - - [not a date] \"GET / HTTP/1.1\" 200 1 \"-\" \"ua\""));
  }
}
