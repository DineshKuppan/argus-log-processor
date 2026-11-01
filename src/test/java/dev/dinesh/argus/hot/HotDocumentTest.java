package dev.dinesh.argus.hot;

import static dev.dinesh.argus.hot.HotIndicesTest.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dinesh.argus.model.LogEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HotDocumentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void mapsEveryEnvelopeFieldToItsColumn() {
    LogEnvelope e =
        new LogEnvelope(
            "ev-1",
            "acme",
            Instant.parse("2026-11-01T09:59:58.123456789Z"),
            Instant.parse("2026-11-01T09:59:59Z"),
            "warn",
            13,
            "disk almost full",
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            Map.of("service.name", "api"),
            Map.of("http_status", 507, "nested", Map.of("k", List.of("v"))),
            new LogEnvelope.Source("app", 3, 1024, "json", "argus-2"));

    ObjectNode doc = HotDocument.of(e, NOW, MAPPER);

    assertEquals("2026-11-01T09:59:58.123Z", doc.get("@timestamp").asText(), "milliseconds");
    assertEquals("2026-11-01T09:59:59Z", doc.get("observed_timestamp").asText());
    assertFalse(doc.has("event_time_missing"));
    assertEquals("acme", doc.get("tenant_id").asText());
    assertEquals("ev-1", doc.get("event_id").asText());
    assertEquals(13, doc.get("severity_number").asInt());
    assertEquals("api", doc.get("service_name").asText());
    assertEquals(507, doc.get("attributes").get("http_status").asInt(), "types kept in _source");
    assertEquals("v", doc.get("attributes").get("nested").get("k").get(0).asText());
    assertEquals(1024, doc.get("source_offset").asLong());
    assertEquals("json", doc.get("source_format").asText());
    assertEquals("argus-2", doc.get("source_instance").asText());
  }

  @Test
  void aLineWithoutAnEventTimeUsesTheObservedOneAndSaysSo() {
    LogEnvelope e = HotIndicesTest.envelope(null, Instant.parse("2026-11-01T09:00:00Z"));

    ObjectNode doc = HotDocument.of(e, NOW, MAPPER);

    assertEquals("2026-11-01T09:00:00Z", doc.get("@timestamp").asText());
    assertTrue(doc.get("event_time_missing").asBoolean());
    assertFalse(doc.has("attributes"));
    assertFalse(doc.has("source_instance"));
  }

  @Test
  void longAttributeValuesAreCutSoTheDocumentIsntRejected() {
    LogEnvelope e =
        new LogEnvelope(
            "ev",
            "acme",
            NOW,
            NOW,
            null,
            0,
            null,
            null,
            null,
            null,
            Map.of("big", "x".repeat(40_000), "list", List.of("y".repeat(9_000))),
            new LogEnvelope.Source("app", 0, 0, "json"));

    ObjectNode doc = HotDocument.of(e, NOW, MAPPER);

    assertEquals(HotDocument.MAX_LEAF_CHARS, doc.get("attributes").get("big").asText().length());
    assertEquals(
        HotDocument.MAX_LEAF_CHARS, doc.get("attributes").get("list").get(0).asText().length());
  }
}
