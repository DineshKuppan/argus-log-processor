package dev.dinesh.argus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.dinesh.argus.model.LogRecord;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Rules every {@link LogParser} must follow, so parsers are interchangeable in the chain. */
class ParserContractTest {

  private static final List<String> GARBAGE =
      List.of(
          "",
          " ",
          "{",
          "}",
          "{\"a\":",
          "[]",
          "=",
          "a=",
          "\u0000\u0001\u0002",
          "��",
          "2025-",
          "x".repeat(10_000),
          "10.0.0.1 - - [99/Foo/2025:99:99:99 +0000] \"GET / HTTP/1.1\" 200 1 \"-\" \"ua\"",
          "2025-08-21T12:00:00Z stdout X body",
          "{\"timestamp\":12,\"level\":{\"nested\":true},\"message\":[1,2]}");

  /** Each parser with one line it must understand. */
  static Stream<Arguments> parsers() {
    return Stream.of(
        Arguments.of(
            new ServerAccessLogParser(),
            "10.0.0.12 - - [09/Oct/2025:01:11:40 +0000] \"POST /api/orders HTTP/1.1\" 403 1467 \"-\" \"Mozilla/5.0\""),
        Arguments.of(
            new JsonLogParser(), "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"INFO\"}"),
        Arguments.of(new CriLogParser(), "2025-08-21T12:00:00.000000000Z stdout F started"),
        Arguments.of(new KeyValueLogParser(), "level=INFO msg=\"CPU ok\" cpu=43"),
        Arguments.of(
            new CanonicalLineParser(),
            "[2019-03-18 22:48:32.999] canonical-log-line http_method=POST http_status=200"),
        Arguments.of(
            new LogbackPatternParser("%d{ISO8601} [%t] %-5p %c - %m%n"),
            "2025-10-26 18:12:00,123 [main] INFO  dev.Argus - Started"),
        Arguments.of(new RawTextParser(), "anything at all"));
  }

  @ParameterizedTest
  @MethodSource("parsers")
  void parsesItsOwnFormat(LogParser parser, String line) {
    LogRecord record = parser.parse(line);
    assertNotNull(record, parser.name());
    assertNotNull(record.getMeta(), "meta must be a mutable map");
    record.getMeta().put("argus.test", true);
  }

  @ParameterizedTest
  @MethodSource("parsers")
  void neverThrowsAndHonoursCanParse(LogParser parser, String ignored) {
    for (String line : GARBAGE) {
      LogRecord record = parser.parse(line);
      if (!parser.canParse(line)) {
        assertNull(record, parser.name() + " parsed a line its canParse rejected: " + line);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("parsers")
  void isSafeToShareBetweenThreads(LogParser parser, String line) throws Exception {
    LogRecord expected = parser.parse(line);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<LogRecord>> results =
          IntStream.range(0, 200).mapToObj(i -> pool.submit(() -> parser.parse(line))).toList();
      for (Future<LogRecord> result : results) {
        assertEquals(expected, result.get());
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
