package dev.dinesh.argus.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.parser.ParserChain.Outcome;
import dev.dinesh.argus.parser.ParserChain.Parsed;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRegistryTest {

  private static final String ACCESS =
      "10.0.0.12 - - [09/Oct/2025:01:11:40 +0000] \"POST /api/orders HTTP/1.1\" 403 1467 \"-\" \"Mozilla/5.0\"";
  private static final String JSON =
      "{\"timestamp\":\"2025-09-19T22:21:35Z\",\"level\":\"ERROR\",\"message\":\"m\"}";
  private static final String CRI = "2025-08-21T12:00:00.000000000Z stdout F Application started";
  private static final String KV = "timestamp=2025-08-21T12:10:00Z level=INFO msg=\"CPU ok\"";

  private final ParserRegistry registry = new ParserRegistry();

  private String detect(String... lines) {
    return registry.detect(List.of(lines)).parser().name();
  }

  @Test
  void detectsEachBundledFormat() {
    assertEquals("nginx_access", detect(ACCESS, ACCESS));
    assertEquals("json", detect(JSON, JSON));
    assertEquals("cri", detect(CRI, CRI));
    assertEquals("kv", detect(KV, KV));
    assertEquals("raw", detect("plain text", "more plain text"));
    assertEquals("raw", detect());
  }

  @Test
  void mixedSamplePicksTheMajorityAndReportsHowManyLinesFit() {
    FormatDetector.Detection d = registry.detect(List.of(JSON, ACCESS, JSON, "garbage", JSON));

    assertEquals("json", d.parser().name());
    assertEquals(5, d.sampledLines());
    assertEquals(3, d.parsedLines());
  }

  @Test
  void chainFallsBackToOtherFormatsThenRaw() {
    ParserChain chain = registry.chain(registry.resolve("json", null).orElseThrow(), true);

    assertEquals(Outcome.PRIMARY, chain.parse(JSON).outcome());
    Parsed stray = chain.parse(ACCESS);
    assertEquals(Outcome.FALLBACK, stray.outcome());
    assertEquals("nginx_access", stray.format());
    Parsed garbage = chain.parse("garbage");
    assertEquals(Outcome.UNPARSED, garbage.outcome());
    assertEquals("raw", garbage.format());
    assertEquals("garbage", garbage.record().getMessage());
    assertEquals(true, garbage.record().getMeta().get(ParserChain.UNPARSED_ATTRIBUTE));
  }

  @Test
  void withoutKeepUnparsedUnknownLinesAreDropped() {
    ParserChain chain = registry.chain(registry.detect(List.of(ACCESS)).parser(), false);

    assertNull(chain.parse("garbage"));
    assertEquals(Outcome.PRIMARY, chain.parse(ACCESS).outcome());
  }

  @Test
  void rawPrimaryStillParsesStructuredLinesAfterAPlainTextHeader() {
    FormatDetector.Detection banner = registry.detect(List.of("Starting up", "Version 1.2"));
    ParserChain chain = registry.chain(banner.parser(), true);

    assertEquals("raw", banner.parser().name());
    assertEquals(0, banner.parsedLines());
    Parsed json = chain.parse(JSON);
    assertEquals(Outcome.FALLBACK, json.outcome());
    assertEquals("json", json.format());
    Parsed plain = chain.parse("plain");
    assertEquals(Outcome.UNPARSED, plain.outcome());
    assertEquals(true, plain.record().getMeta().get(ParserChain.UNPARSED_ATTRIBUTE));
    assertNull(registry.chain(banner.parser(), false).parse("plain"));
  }

  @Test
  void resolvesRequestedFormatsAndRejectsBadOnes() {
    assertTrue(registry.resolve(null, null).isEmpty());
    assertTrue(registry.resolve(" ", "").isEmpty());
    assertEquals("kv", registry.resolve("kv", null).orElseThrow().name());
    assertEquals("logback", registry.resolve(null, "%d %m").orElseThrow().name());
    assertEquals("logback", registry.resolve("logback", "%d %m").orElseThrow().name());

    IllegalArgumentException unknown =
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("xml", null));
    assertTrue(unknown.getMessage().contains("nginx_access"));
    assertThrows(IllegalArgumentException.class, () -> registry.resolve("logback", null));
    assertThrows(IllegalArgumentException.class, () -> registry.resolve("json", "%m"));
    assertThrows(IllegalArgumentException.class, () -> registry.resolve(null, "%replace(%m)"));
  }
}
