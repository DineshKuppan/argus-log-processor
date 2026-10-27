package dev.dinesh.argus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Format-specific behaviour, using lines from the bundled samples and real-world variants. */
class FormatParsersTest {

  @Test
  void accessLogKeepsAnOptionalTrailingCategory() {
    ServerAccessLogParser parser = new ServerAccessLogParser();
    String line =
        "10.0.0.15 - - [19/Sep/2025:23:19:47 +0000] \"GET /health HTTP/1.1\" 502 1959 \"-\" \"PostmanRuntime/7.32.0\"";

    assertEquals("ERROR", parser.parse(line).getLevel());
    assertFalse(parser.parse(line).getMeta().containsKey("category"));
    assertEquals("apple", parser.parse(line + " apple").getMeta().get("category"));
  }

  @Test
  void jsonFieldsAreLiftedAndTheRestKeptAsAttributes() {
    LogRecord r =
        new JsonLogParser()
            .parse(
                "{\"timestamp\":\"2025-09-19T22:21:35.306482Z\",\"level\":\"ERROR\","
                    + "\"message\":\"PUT /api/products\",\"user\":\"frank\",\"latency_ms\":714}");

    assertEquals(Instant.parse("2025-09-19T22:21:35.306482Z"), r.getTimestamp());
    assertEquals("ERROR", r.getLevel());
    assertEquals("PUT /api/products", r.getMessage());
    assertEquals(Map.of("user", "frank", "latency_ms", 714), r.getMeta());
  }

  @Test
  void jsonUnderstandsCommonFieldNamesAndEpochTimestamps() {
    JsonLogParser parser = new JsonLogParser();

    LogRecord pino = parser.parse("{\"level\":30,\"time\":1760000000123,\"msg\":\"hello\"}");
    assertEquals(Instant.ofEpochMilli(1760000000123L), pino.getTimestamp());
    assertEquals("30", pino.getLevel());
    assertEquals("hello", pino.getMessage());

    LogRecord ecs =
        parser.parse(
            "{\"@timestamp\":\"2025-10-01T10:00:00+02:00\",\"log.level\":\"warn\",\"message\":\"m\"}");
    assertEquals(Instant.parse("2025-10-01T08:00:00Z"), ecs.getTimestamp());
    assertEquals("warn", ecs.getLevel());

    LogRecord seconds = parser.parse("{\"ts\":1760000000,\"msg\":\"m\"}");
    assertEquals(Instant.ofEpochSecond(1760000000L), seconds.getTimestamp());
  }

  @Test
  void jsonKeepsAnUnparseableTimestampAsAnAttribute() {
    LogRecord r = new JsonLogParser().parse("{\"timestamp\":\"yesterday\",\"message\":\"m\"}");

    assertNull(r.getTimestamp());
    assertEquals("yesterday", r.getMeta().get("timestamp"));
  }

  @Test
  void criLinesKeepStreamAndTag() {
    LogRecord r =
        new CriLogParser()
            .parse("2025-08-21T12:35:05.000000000Z stderr F NullPointerException in OrderService");

    assertEquals(Instant.parse("2025-08-21T12:35:05Z"), r.getTimestamp());
    assertEquals("NullPointerException in OrderService", r.getMessage());
    assertEquals("stderr", r.getMeta().get("log.iostream"));
    assertEquals("F", r.getMeta().get("logtag"));
  }

  @Test
  void criWithAJsonBodyParsesTheBody() {
    LogRecord r =
        new CriLogParser()
            .parse(
                "2025-08-21T12:00:00.5Z stdout F {\"level\":\"ERROR\",\"msg\":\"boom\",\"order\":7}");

    assertEquals(Instant.parse("2025-08-21T12:00:00.5Z"), r.getTimestamp());
    assertEquals("ERROR", r.getLevel());
    assertEquals("boom", r.getMessage());
    assertEquals(7, r.getMeta().get("order"));
    assertEquals("stdout", r.getMeta().get("log.iostream"));
  }

  @Test
  void keyValueHandlesQuotedValuesAndDottedKeys() {
    LogRecord r =
        new KeyValueLogParser()
            .parse(
                "timestamp=2025-08-21T12:15:00Z level=ERROR host=node-1 msg=\"Disk full\" mount=/var trace.id=abc");

    assertEquals(Instant.parse("2025-08-21T12:15:00Z"), r.getTimestamp());
    assertEquals("Disk full", r.getMessage());
    assertEquals(Map.of("host", "node-1", "mount", "/var", "trace.id", "abc"), r.getMeta());
  }

  @Test
  void keyValueDoesNotClaimAccessLinesWithQueryStrings() {
    assertFalse(
        new KeyValueLogParser()
            .canParse("10.0.0.1 - - [09/Oct/2025:01:11:40 +0000] \"GET /a?b=c HTTP/1.1\" 200 1"));
  }

  @Test
  void rawTextPicksUpALeadingTimestampAndLevelWord() {
    LogRecord r = new RawTextParser().parse("2025-10-26 18:12:00,123 WARN pool exhausted");

    assertEquals(Instant.parse("2025-10-26T18:12:00.123Z"), r.getTimestamp());
    assertEquals("WARN", r.getLevel());
    assertEquals("2025-10-26 18:12:00,123 WARN pool exhausted", r.getMessage());
    assertNull(new RawTextParser().parse("no structure here").getTimestamp());
  }

  @Test
  void logbackLayoutFromSkillMdIsParsed() {
    LogbackPatternParser parser = new LogbackPatternParser("%d{ISO8601} [%t] %-5p %c - %m%n");

    LogRecord r =
        parser.parse(
            "2025-10-26 18:12:00,123 [http-nio-9000-exec-1] WARN  d.d.a.LogService - File size: 2 MB");

    assertEquals(Instant.parse("2025-10-26T18:12:00.123Z"), r.getTimestamp());
    assertEquals("WARN", r.getLevel());
    assertEquals("File size: 2 MB", r.getMessage());
    assertEquals("http-nio-9000-exec-1", r.getMeta().get("thread.name"));
    assertEquals("d.d.a.LogService", r.getMeta().get("logger"));
    assertNull(parser.parse("not in this layout"));
  }

  @Test
  void logbackLayoutWithZoneMdcAndPlaceholders() {
    LogbackPatternParser parser =
        new LogbackPatternParser(
            "%d{yyyy-MM-dd'T'HH:mm:ss.SSS, Asia/Kolkata} %5p ${PID:- } --- [%15.15t] %X{traceId} : %m");

    LogRecord r =
        parser.parse("2026-10-26T18:12:00.000  INFO 4242 --- [           main] abc123 : Started");

    assertEquals(Instant.parse("2026-10-26T12:42:00Z"), r.getTimestamp());
    assertEquals("INFO", r.getLevel());
    assertEquals("4242", r.getMeta().get("pid"));
    assertEquals("main", r.getMeta().get("thread.name"));
    assertEquals("abc123", r.getMeta().get("traceId"));
    assertEquals("Started", r.getMessage());
  }

  @Test
  void logbackOffsetsInTheDatePatternWinOverTheDefaultZone() {
    LogbackPatternParser parser = new LogbackPatternParser("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %m");

    assertEquals(
        Instant.parse("2026-10-26T12:42:00Z"),
        parser.parse("2026-10-26T18:12:00.000+05:30 hello").getTimestamp());
  }

  @Test
  void logbackRejectsUnsupportedLayouts() {
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser(""));
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser("just text"));
    assertThrows(
        IllegalArgumentException.class, () -> new LogbackPatternParser("%replace(%m){'a','b'}"));
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser("%X %m"));
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser("%d{qqqq-bad} %m"));
    assertTrue(new LogbackPatternParser("%m").parse("anything").getMessage().equals("anything"));
  }
}
