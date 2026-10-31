package dev.dinesh.argus.hot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.config.SearchProperties;
import dev.dinesh.argus.engine.EngineContainers;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Flavor;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.search.SearchHit;
import dev.dinesh.argus.search.SearchQuery;
import dev.dinesh.argus.search.SearchResult;
import dev.dinesh.argus.search.SearchService;
import dev.dinesh.argus.wal.WalFixture;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
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

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void aSegmentIndexedTwiceIsStoredOnce(Flavor flavor, @TempDir Path wal) throws Exception {
    SearchEngineClient client = EngineContainers.client(flavor);
    HotProperties props = props(flavor, prefix(), List.of());
    HotIndices indices = new HotIndices(props);
    Path segment =
        new WalFixture(wal).segment("acme", "app", 0, 0, 50, Instant.parse("2026-10-31T12:00:00Z"));
    WalSegments segments = new WalSegments(wal, Set.of(WalSink.HOT), false);
    HotIndexer indexer =
        new HotIndexer(
            client,
            segments,
            indices,
            props,
            ArgusMetrics.noop(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMillis(10));

    assertTrue(indexer.runOnce());
    Files.delete(Path.of(segment + WalSink.HOT.markerSuffix())); // as if the marker was lost
    assertTrue(indexer.runOnce());

    client.send("POST", "/" + indices.pattern() + "/_refresh", null, null);
    assertEquals(50, count(client, indices.pattern(), "{\"query\":{\"match_all\":{}}}"));
    assertEquals(
        50, count(client, indices.pattern(), "{\"query\":{\"term\":{\"tenant_id\":\"acme\"}}}"));
  }

  private static SearchService search(SearchEngineClient client, HotIndices indices) {
    return new SearchService(
        client,
        indices,
        SearchProperties.defaults(),
        ArgusMetrics.noop(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static SearchQuery query(
      String tenant, String q, String level, List<String> attr, Integer size) {
    return SearchQuery.parse(
        tenant,
        "2026-10-31T00:00:00Z",
        "2026-11-01T11:00:00Z",
        q,
        level,
        null,
        null,
        attr,
        size,
        Instant.parse("2026-10-18T00:00:00Z"),
        SearchProperties.defaults());
  }

  private static List<String> ids(SearchResult result) {
    return result.hits().stream().map(SearchHit::eventId).toList();
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void aTenantNeverFindsAnotherTenantsLines(Flavor flavor) throws Exception {
    SearchEngineClient client = EngineContainers.client(flavor);
    HotProperties props = props(flavor, prefix(), List.of());
    HotIndices indices = new HotIndices(props);
    new HotTemplates(client, indices, props).install();
    bulk(
        client,
        indices,
        List.of(
            line("acme", "a1", NOW.minusSeconds(60), "payment failed", Map.of("order", "o-1")),
            line(
                "globex",
                "g1",
                NOW.minusSeconds(30),
                "payment failed secret",
                Map.of("order", "o-1")),
            line(
                "globex",
                "g2",
                NOW.minusSeconds(20),
                "tenant_id acme",
                Map.of("tenant_id", "acme"))));
    SearchService search = search(client, indices);

    assertEquals(
        List.of("a1"), ids(search.search(query("acme", "payment", null, null, null), null)));
    assertEquals(List.of(), ids(search.search(query("acme", "secret", null, null, null), null)));
    assertEquals(
        List.of("a1"),
        ids(search.search(query("acme", null, null, List.of("order:o-1"), null), null)));
    assertEquals(
        List.of(),
        ids(search.search(query("acme", null, null, List.of("tenant_id:acme"), null), null)),
        "an attribute named tenant_id is globex's own attribute");
    assertEquals(
        List.of(),
        ids(search.search(query("acme", "tenant_id:globex | *", null, null, null), null)));
    assertEquals(
        List.of("g2", "g1"), ids(search.search(query("globex", null, null, null, null), null)));
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void pagesCoverLinesSharingATimestampExactlyOnce(Flavor flavor) throws Exception {
    SearchEngineClient client = EngineContainers.client(flavor);
    HotProperties props = props(flavor, prefix(), List.of());
    HotIndices indices = new HotIndices(props);
    new HotTemplates(client, indices, props).install();
    List<LogEnvelope> lines = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) {
      lines.add(
          line("acme", "e" + i, NOW.minusSeconds(i < 5 ? 10 : i), "same second " + i, Map.of()));
    }
    bulk(client, indices, lines);
    SearchService search = search(client, indices);
    SearchQuery query = query("acme", null, null, null, 3);

    List<String> seen = new java.util.ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      SearchResult page = search.search(query, cursor);
      seen.addAll(ids(page));
      cursor = page.next();
      pages++;
    } while (cursor != null && pages < 10);

    assertEquals(7, seen.size(), seen.toString());
    assertEquals(7, seen.stream().distinct().count());
    assertEquals(
        List.of("e5", "e6", "e4", "e3", "e2", "e1", "e0"),
        seen,
        "newest first, ties by event_id desc");
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void filtersAndADedicatedTenantsOwnIndices(Flavor flavor) throws Exception {
    SearchEngineClient client = EngineContainers.client(flavor);
    String prefix = prefix();
    HotProperties shared = props(flavor, prefix, List.of());
    HotProperties dedicated = props(flavor, prefix, List.of("bigco"));
    new HotTemplates(client, new HotIndices(shared), shared).install();
    // Yesterday bigco shared the index; today it has its own.
    bulk(
        client,
        new HotIndices(shared),
        List.of(line("bigco", "old", Instant.parse("2026-10-31T12:00:00Z"), "warm up", Map.of())));
    LogEnvelope info =
        new LogEnvelope(
            "info",
            "bigco",
            NOW.minusSeconds(5),
            NOW,
            "INFO",
            9,
            "all good",
            null,
            null,
            null,
            null,
            new LogEnvelope.Source("web", 0, 0, "json"));
    bulk(
        client,
        new HotIndices(dedicated),
        List.of(
            line("bigco", "new", NOW.minusSeconds(10), "disk full", Map.of("http_status", 507)),
            info));
    assertTrue(client.get("/" + prefix + "-2026.11.01-t-bigco").ok());
    SearchService search = search(client, new HotIndices(dedicated));

    assertEquals(
        List.of("info", "new", "old"),
        ids(search.search(query("bigco", null, null, null, null), null)));
    assertEquals(
        List.of("new", "old"), ids(search.search(query("bigco", null, "error", null, null), null)));
    assertEquals(
        List.of("new"),
        ids(search.search(query("bigco", "disk", null, List.of("http_status:507"), null), null)));
    SearchHit hit = search.search(query("bigco", "disk", null, null, null), null).hits().get(0);
    assertEquals(507, hit.attributes().get("http_status"), "types from _source");
    assertEquals("api", hit.resource().get("service.name"));
  }
}
