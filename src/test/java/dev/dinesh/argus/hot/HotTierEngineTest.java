package dev.dinesh.argus.hot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.engine.EngineContainers;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Flavor;
import dev.dinesh.argus.model.LogEnvelope;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The hot tier against real engines (OpenSearch 2.17 and Elasticsearch 8.15, Testcontainers;
 * skipped without Docker). Each test uses its own index prefix.
 */
class HotTierEngineTest {

  static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  static HotProperties props(Flavor flavor, String prefix, List<String> dedicated) {
    return new HotProperties(
        flavor == Flavor.OPENSEARCH
            ? HotProperties.Store.OPENSEARCH
            : HotProperties.Store.ELASTICSEARCH,
        URI.create(EngineContainers.url(flavor)),
        null,
        null,
        null,
        prefix,
        dedicated,
        Duration.ofDays(14),
        1,
        0,
        Duration.ofMillis(200),
        null,
        Duration.ofMillis(100),
        0,
        null,
        Duration.ofMillis(50),
        Duration.ofMillis(200),
        null);
  }

  static String prefix() {
    return "t" + UUID.randomUUID().toString().substring(0, 8);
  }

  static LogEnvelope line(
      String tenant, String id, Instant at, String body, Map<String, Object> attrs) {
    return new LogEnvelope(
        id,
        tenant,
        at,
        NOW,
        "ERROR",
        17,
        body,
        null,
        null,
        Map.of("service.name", "api"),
        attrs,
        new LogEnvelope.Source("app", 0, 0, "json"));
  }

  /** Indexes {@code lines} with {@code _bulk}, as the indexer does, and refreshes. */
  static JsonNode bulk(SearchEngineClient client, HotIndices indices, List<LogEnvelope> lines)
      throws Exception {
    StringBuilder body = new StringBuilder();
    for (LogEnvelope e : lines) {
      var action = client.mapper().createObjectNode();
      var meta = action.putObject("index");
      meta.put("_index", indices.index(e.tenantId(), HotIndices.day(e, NOW)));
      meta.put("_id", e.eventId());
      if (indices.routing(e.tenantId()) != null) {
        meta.put("routing", indices.routing(e.tenantId()));
      }
      body.append(action).append('\n').append(HotDocument.of(e, NOW, client.mapper())).append('\n');
    }
    var response =
        client.send(
            "POST",
            "/_bulk?refresh=true",
            body.toString().getBytes(StandardCharsets.UTF_8),
            SearchEngineClient.NDJSON);
    assertTrue(response.ok(), response.body());
    return client.mapper().readTree(response.body());
  }

  static long count(SearchEngineClient client, String indices, String query) throws Exception {
    var response =
        client.send(
            "POST",
            "/" + indices + "/_count?ignore_unavailable=true",
            query.getBytes(StandardCharsets.UTF_8),
            SearchEngineClient.JSON);
    assertTrue(response.ok(), response.body());
    return client.mapper().readTree(response.body()).get("count").asLong();
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void theTemplateIsInstalledOnceAndRejectsNothingArgusWrites(Flavor flavor) throws Exception {
    SearchEngineClient client = EngineContainers.client(flavor);
    HotProperties props = props(flavor, prefix(), List.of());
    HotIndices indices = new HotIndices(props);
    HotTemplates templates = new HotTemplates(client, indices, props);
    HotTemplates.requireSupported(client.engine(), props.store());

    templates.install();
    templates.install();

    JsonNode installed =
        client.mapper().readTree(client.get("/_index_template/" + indices.prefix()).body());
    JsonNode properties =
        installed.at("/index_templates/0/index_template/template/mappings/properties");
    assertEquals(
        flavor == Flavor.OPENSEARCH ? "flat_object" : "flattened",
        properties.at("/attributes/type").asText());
    // The same attribute as a number, text and an object: dynamic mapping would reject two of them.
    JsonNode result =
        bulk(
            client,
            indices,
            List.of(
                line("acme", "a", NOW, "disk full", Map.of("status", 200)),
                line("acme", "b", NOW, "disk ok", Map.of("status", "ok")),
                line("acme", "c", NOW, "nested", Map.of("status", Map.of("code", 1)))));
    assertFalse(result.get("errors").asBoolean(), result.toString());
    String index = indices.index("acme", LocalDate.of(2026, 11, 1));
    assertEquals(1, count(client, index, "{\"query\":{\"term\":{\"attributes.status\":\"ok\"}}}"));
    assertEquals(1, count(client, index, "{\"query\":{\"term\":{\"attributes.status\":\"200\"}}}"));
    assertEquals(2, count(client, index, "{\"query\":{\"match\":{\"body\":\"disk\"}}}"));
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void theConfiguredStoreMustMatchTheCluster(Flavor flavor) throws Exception {
    SearchEngineClient client = EngineContainers.client(flavor);
    HotProperties.Store other =
        flavor == Flavor.OPENSEARCH
            ? HotProperties.Store.ELASTICSEARCH
            : HotProperties.Store.OPENSEARCH;

    assertThrows(
        IllegalStateException.class, () -> HotTemplates.requireSupported(client.engine(), other));
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void retentionDeletesOnlyItsOwnExpiredIndices(Flavor flavor) throws Exception {
    SearchEngineClient client = EngineContainers.client(flavor);
    HotProperties props = props(flavor, prefix(), List.of("bigco"));
    HotIndices indices = new HotIndices(props);
    new HotTemplates(client, indices, props).install();
    String p = indices.prefix();
    List<String> names =
        List.of(
            p + "-2026.10.01-shared",
            p + "-2026.10.01-t-bigco",
            p + "-2026.10.18-shared",
            p + "-2026.10.01-shared-copy");
    for (String name : names) {
      assertTrue(client.send("PUT", "/" + name, null, null).ok(), name);
    }

    List<String> deleted = new HotRetention(client, indices).runOnce(NOW);

    assertEquals(
        List.of(p + "-2026.10.01-shared", p + "-2026.10.01-t-bigco"),
        deleted.stream().sorted().toList());
    assertEquals(404, client.get("/" + p + "-2026.10.01-shared").status());
    assertTrue(client.get("/" + p + "-2026.10.18-shared").ok(), "the oldest day kept");
    assertTrue(client.get("/" + p + "-2026.10.01-shared-copy").ok(), "not a name it parses");
    assertEquals(List.of(), new HotRetention(client, indices).runOnce(NOW));
  }
}
