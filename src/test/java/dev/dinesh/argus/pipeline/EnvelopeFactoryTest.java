package dev.dinesh.argus.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.ParserChain;
import dev.dinesh.argus.parser.ParserChain.Outcome;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvelopeFactoryTest {

  private static final Instant NOW = Instant.parse("2026-10-27T09:00:00Z");

  private static ParserChain.Parsed parsed(String level, Map<String, Object> meta) {
    LogRecord record =
        new LogRecord(Instant.parse("2025-10-09T01:11:40Z"), level, "body", new HashMap<>(meta));
    return new ParserChain.Parsed(record, "json", Outcome.PRIMARY);
  }

  @Test
  void buildsTheEnvelopeWithSourceAndSeverity() {
    LogEnvelope e =
        new EnvelopeFactory("default", "app", 2)
            .create(parsed("warning", Map.of("k", "v")), 512, NOW);

    assertEquals("default", e.tenantId());
    assertEquals(Instant.parse("2025-10-09T01:11:40Z"), e.timestamp());
    assertEquals(NOW, e.observedTimestamp());
    assertEquals("warning", e.severityText());
    assertEquals(13, e.severityNumber());
    assertEquals("body", e.body());
    assertEquals(Map.of("k", "v"), e.attributes());
    assertEquals(new LogEnvelope.Source("app", 2, 512, "json"), e.source());
    assertNull(e.resource());
  }

  @Test
  void liftsTraceContextAndServiceNameOutOfTheAttributes() {
    LogEnvelope e =
        new EnvelopeFactory("default", "app", 0)
            .create(
                parsed(
                    "INFO", Map.of("trace_id", "t1", "spanId", "s1", "service", "orders", "k", 1)),
                0,
                NOW);

    assertEquals("t1", e.traceId());
    assertEquals("s1", e.spanId());
    assertEquals(Map.of("service.name", "orders"), e.resource());
    assertEquals(Map.of("k", 1), e.attributes());
  }

  @Test
  void ecsServiceObjectLendsItsNameAndStaysInTheAttributes() {
    Map<String, Object> ecs = Map.of("name", "checkout", "version", "1.2");
    LogEnvelope e =
        new EnvelopeFactory("default", "app", 0)
            .create(parsed("INFO", Map.of("service", ecs, "trace_id", Map.of("x", 1))), 0, NOW);

    assertEquals(Map.of("service.name", "checkout"), e.resource());
    assertEquals(ecs, e.attributes().get("service"));
    assertNull(e.traceId());
    assertEquals(Map.of("x", 1), e.attributes().get("trace_id"));
  }

  @Test
  void eventIdIsDeterministicAndDiffersByOffsetSourceAndGeneration() {
    EnvelopeFactory app = new EnvelopeFactory("default", "app", 0);

    assertEquals(app.eventId(100), new EnvelopeFactory("default", "app", 0).eventId(100));
    assertNotEquals(app.eventId(100), app.eventId(101));
    assertNotEquals(app.eventId(100), new EnvelopeFactory("default", "other", 0).eventId(100));
    assertNotEquals(app.eventId(100), new EnvelopeFactory("default", "app", 1).eventId(100));
    assertNotEquals(app.eventId(100), new EnvelopeFactory("acme", "app", 0).eventId(100));
  }
}
