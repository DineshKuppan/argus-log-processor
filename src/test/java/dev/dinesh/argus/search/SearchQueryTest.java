package dev.dinesh.argus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.config.SearchProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.hot.HotIndices;
import dev.dinesh.argus.metrics.ArgusMetrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchQueryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant OLDEST = Instant.parse("2026-10-18T00:00:00Z");

  private static SearchQuery query(String tenant, String q, List<String> attr) {
    return SearchQuery.parse(
        tenant,
        "2026-11-01T09:00:00Z",
        "2026-11-01T10:00:00Z",
        q,
        "warn",
        null,
        "api",
        attr,
        null,
        OLDEST,
        SearchProperties.defaults());
  }

  private static SearchService service() {
    HotProperties hot =
        new HotProperties(
            HotProperties.Store.OPENSEARCH,
            URI.create("http://localhost:9200"),
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            0,
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null);
    SearchEngineClient client =
        new SearchEngineClient(
            hot.url(), null, null, null, Duration.ofSeconds(1), HttpClient.newHttpClient(), MAPPER);
    return new SearchService(
        client,
        new HotIndices(hot),
        SearchProperties.defaults(),
        ArgusMetrics.noop(),
        Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void theTenantFilterIsAlwaysThereAndTheUsersTextOnlyInItsOwnClause() {
    SearchQuery q = query("acme", "tenant_id:globex OR *", List.of("tenant_id:globex"));

    JsonNode bool = service().body(q, null).get("query").get("bool");

    assertEquals("acme", bool.get("filter").get(0).get("term").get("tenant_id").asText());
    JsonNode text = bool.get("must").get(0).get("simple_query_string");
    assertEquals("tenant_id:globex OR *", text.get("query").asText());
    assertEquals(List.of("body"), List.of(text.get("fields").get(0).asText()), "body only");
    assertFalse(text.get("flags").asText().contains("REGEXP"));
    assertFalse(text.get("flags").asText().contains("FUZZY"));
    // An attribute filter named like the tenant field stays under attributes.
    assertTrue(bool.get("filter").toString().contains("\"attributes.tenant_id\":\"globex\""));
    assertEquals(
        13, bool.get("filter").get(2).get("range").get("severity_number").get("gte").asInt());
  }

  @Test
  void aCursorOnlyContinuesTheSearchItCameFrom() throws Exception {
    SearchQuery first = query("acme", "disk", List.of());
    ArrayNode sort = MAPPER.createArrayNode().add(1761987600000L).add("ev-9");
    String cursor = SearchCursor.encode(first, sort, MAPPER);

    assertEquals(sort, SearchCursor.decode(cursor, first, MAPPER));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchCursor.decode(cursor, query("globex", "disk", List.of()), MAPPER));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchCursor.decode(cursor, query("acme", "other", List.of()), MAPPER));
    assertThrows(IllegalArgumentException.class, () -> SearchCursor.decode("!!", first, MAPPER));
  }

  @Test
  void boundsAreChecked() {
    assertEquals(100, query("acme", null, null).size());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            query(
                "acme",
                null,
                List.of(
                    "a:1", "b:1", "c:1", "d:1", "e:1", "f:1", "g:1", "h:1", "i:1", "j:1", "k:1")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SearchQuery.parse(
                "acme",
                "2026-10-20T00:00:00Z",
                "2026-11-04T00:00:00Z",
                null,
                null,
                null,
                null,
                null,
                null,
                OLDEST,
                SearchProperties.defaults()));
  }
}
