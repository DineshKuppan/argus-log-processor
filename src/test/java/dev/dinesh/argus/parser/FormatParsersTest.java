package dev.dinesh.argus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.List;
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
  void canonicalLinesKeepEveryFieldTyped() {
    CanonicalLineParser parser = new CanonicalLineParser();
    LogRecord r =
        parser.parse(
            "[2019-03-18 22:48:32.999] canonical-log-line http_method=POST http_path=/v1/charges"
                + " http_status=200 duration=0.050 cached=false user=\"Jo Smith\" level=info");

    assertEquals(Instant.parse("2019-03-18T22:48:32.999Z"), r.getTimestamp());
    assertEquals("info", r.getLevel());
    assertNull(r.getMessage(), "suppression then compares whole lines");
    assertEquals(
        Map.of(
            "http_method",
            "POST",
            "http_path",
            "/v1/charges",
            "http_status",
            200L,
            "duration",
            0.05,
            "cached",
            false,
            "user",
            "Jo Smith"),
        r.getMeta());
  }

  @Test
  void canonicalLinesTakeEitherMarkerSpellingAndAnIsoPrefixOrNone() {
    CanonicalLineParser parser = new CanonicalLineParser();

    LogRecord iso = parser.parse("2025-10-09T01:11:40Z canonical_log_line status=503");
    assertEquals(Instant.parse("2025-10-09T01:11:40Z"), iso.getTimestamp());
    assertEquals(503L, iso.getMeta().get("status"));

    LogRecord bare = parser.parse("canonical-log-line ts=1760000000 id=00123 version=1.2.3");
    assertEquals(Instant.ofEpochSecond(1760000000L), bare.getTimestamp());
    assertEquals("00123", bare.getMeta().get("id"), "a leading zero is part of an id");
    assertEquals("1.2.3", bare.getMeta().get("version"));

    LogRecord badTime = parser.parse("[yesterday] canonical-log-line ts=soon a=1");
    assertNull(badTime.getTimestamp());
    assertEquals("soon", badTime.getMeta().get("timestamp"));
    assertEquals("yesterday", badTime.getMeta().get("prefix"));

    assertFalse(parser.canParse("canonical-log-lines a=1"));
    assertFalse(parser.canParse("level=info msg=\"canonical-log-line\""));
    assertNull(parser.parse("{\"type\":\"canonical-log-line\"}"));
  }

  @Test
  void canonicalValuesAreTypedOnlyWhenTheNumberKeepsTheirValue() {
    Map<String, Object> meta =
        new CanonicalLineParser()
            .parse(
                "canonical-log-line zip=02134 n=-0 big=123456789012345678 huge=1234567890123456789"
                    + " amount=12345678901234567.89 price=19.99 small=0.000123")
            .getMeta();

    assertEquals("02134", meta.get("zip"));
    assertEquals("-0", meta.get("n"));
    assertEquals(123456789012345678L, meta.get("big"));
    assertEquals("1234567890123456789", meta.get("huge"), "beyond a long");
    assertEquals("12345678901234567.89", meta.get("amount"), "beyond a double's digits");
    assertEquals(19.99, meta.get("price"));
    assertEquals(0.000123, meta.get("small"));
  }

  @Test
  void keyValuePairsAreFoundInLinearTime() {
    // Before the possessive, lookbehind-anchored key, a run of key characters with no '=' was
    // retried from every position in it: 40,000 characters took 4.6s, 1MB would take ~45 minutes.
    String run = "a".repeat(1_000_000);
    long start = System.nanoTime();
    LogRecord canonical = new CanonicalLineParser().parse("canonical-log-line " + run + " x=1");
    LogRecord kv = new KeyValueLogParser().parse("k=v " + run + " x=1");
    long millis = (System.nanoTime() - start) / 1_000_000;

    assertEquals(1L, canonical.getMeta().get("x"));
    assertEquals("1", kv.getMeta().get("x"));
    assertTrue(millis < 2_000, "took " + millis + "ms");
  }

  @Test
  void canonicalLinesAreDetectedAndOtherFormatsAreNot() {
    ParserRegistry registry = new ParserRegistry();
    assertEquals(
        "canonical",
        registry
            .detect(
                List.of(
                    "[2019-03-18 22:48:32.999] canonical-log-line http_status=200",
                    "[2019-03-18 22:48:33.104] canonical-log-line http_status=500"))
            .parser()
            .name());
    assertEquals(
        "kv", registry.detect(List.of("level=INFO msg=ok", "level=WARN msg=slow")).parser().name());
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
  void fractionalEpochSecondsKeepTheirFraction() {
    // Fluent Bit's HTTP output with json_date_key timestamp (json_date_format double).
    LogRecord r =
        new JsonLogParser()
            .parse(
                "{\"timestamp\":1700000000.25,\"log\":\"GET /health 200\",\"stream\":\"stdout\"}");

    assertEquals(Instant.ofEpochSecond(1_700_000_000L, 250_000_000), r.getTimestamp());
    assertEquals("GET /health 200", r.getMessage());
    assertEquals("stdout", r.getMeta().get("stream"));
    assertEquals(
        Instant.ofEpochSecond(-2, 500_000_000),
        new JsonLogParser().parse("{\"ts\":-1.5,\"msg\":\"x\"}").getTimestamp());
    // Too large for seconds: read like an integer epoch (milliseconds here).
    assertEquals(
        Instant.ofEpochMilli(1_700_000_000_123L),
        new JsonLogParser().parse("{\"ts\":1700000000123.0,\"msg\":\"x\"}").getTimestamp());
    assertNull(new JsonLogParser().parse("{\"ts\":1e300,\"msg\":\"x\"}").getTimestamp());
  }

  @Test
  void aDateFieldIsAnAttributeNotTheEventTime() {
    LogRecord r = new JsonLogParser().parse("{\"msg\":\"invoice sent\",\"date\":\"2020-01-01\"}");

    assertNull(r.getTimestamp());
    assertEquals("2020-01-01", r.getMeta().get("date"));
  }

  @Test
  void keyValueDoesNotClaimAccessLinesWithQueryStrings() {
    assertFalse(
        new KeyValueLogParser()
            .canParse("10.0.0.1 - - [09/Oct/2025:01:11:40 +0000] \"GET /a?b=c HTTP/1.1\" 200 1"));
  }

  @Test
  void epochAndOffsetVariantsAreParsed() {
    assertEquals(
        Instant.ofEpochSecond(1_700_000_000L, 123_456_789),
        new KeyValueLogParser().parse("ts=\"1700000000123456789\" msg=x").getTimestamp());
    assertNull(new KeyValueLogParser().parse("ts=99999999999999999999 msg=x").getTimestamp());
    assertEquals(
        Instant.parse("2025-10-09T01:11:40Z"),
        new RawTextParser().parse("2025-10-09 01:11:40Z boom").getTimestamp());
    assertEquals(
        Instant.parse("2025-10-08T19:41:40Z"),
        new RawTextParser().parse("2025-10-09T01:11:40+0530 boom").getTimestamp());
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
  void logbackDefaultDateIsFollowedDirectlyByAnotherField() {
    LogbackPatternParser parser = new LogbackPatternParser("%d %-5level %logger %msg");

    LogRecord r = parser.parse("2024-01-01 12:00:00,000 INFO  c.Foo hi there");

    assertEquals(Instant.parse("2024-01-01T12:00:00Z"), r.getTimestamp());
    assertEquals("INFO", r.getLevel());
    assertEquals("c.Foo", r.getMeta().get("logger"));
    assertEquals("hi there", r.getMessage());
    assertEquals(
        "hi", new LogbackPatternParser("%d %m").parse("2024-01-01 12:00:00,000 hi").getMessage());
  }

  @Test
  void logbackTimeOnlyDateKeepsTheTimeAsAnAttribute() {
    LogbackPatternParser parser =
        new LogbackPatternParser("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");

    LogRecord r = parser.parse("12:00:00.123 [main] DEBUG c.e.App - started");

    assertNull(r.getTimestamp());
    assertEquals("12:00:00.123", r.getMeta().get("timestamp"));
    assertEquals("DEBUG", r.getLevel());
    assertEquals("started", r.getMessage());
    assertNull(parser.parse("not-a-time [main] DEBUG c.e.App - started"));
  }

  @Test
  void logbackDateWordsEachUseTheirOwnFormat() {
    LogbackPatternParser parser =
        new LogbackPatternParser("%d{\"HH:mm:ss\", UTC} %d{yyyy-MM-dd} %m");

    LogRecord r = parser.parse("12:00:00 2024-01-01 hi");

    assertEquals(Instant.parse("2024-01-01T00:00:00Z"), r.getTimestamp());
    assertEquals("12:00:00", r.getMeta().get("timestamp"));
  }

  @Test
  void logbackRejectsUnsupportedLayouts() {
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser(""));
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser("just text"));
    assertThrows(
        IllegalArgumentException.class, () -> new LogbackPatternParser("%replace(%m){'a','b'}"));
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser("%X %m"));
    assertThrows(IllegalArgumentException.class, () -> new LogbackPatternParser("%d{qqqq-bad} %m"));
    assertThrows(
        IllegalArgumentException.class, () -> new LogbackPatternParser("%d{\"yyyy-MM-dd} %m"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LogbackPatternParser("%d{\"yyyy-MM-dd\", Not/AZone} %m"));
    assertTrue(new LogbackPatternParser("%m").parse("anything").getMessage().equals("anything"));
  }
}
