package dev.dinesh.argus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.config.SearchProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.hot.HotIndices;
import dev.dinesh.argus.metrics.ArgusMetrics;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How the engine's answer becomes a page, against a stub of {@code _search}. */
class SearchServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private HttpServer server;
  private volatile String answer;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private SearchResult search(String engineAnswer) {
    answer = engineAnswer;
    URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    HotProperties hot =
        new HotProperties(
            HotProperties.Store.OPENSEARCH,
            url,
            null,
            null,
            null,
            null,
            null,
            Duration.ofDays(14),
            0,
            0,
            null,
            null,
            null,
            5000,
            null,
            null,
            null,
            null);
    SearchEngineClient client =
        new SearchEngineClient(
            url,
            null,
            null,
            null,
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    SearchService service =
        new SearchService(
            client,
            new HotIndices(hot),
            SearchProperties.defaults(),
            ArgusMetrics.noop(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    SearchQuery query =
        SearchQuery.parse(
            "acme",
            "2026-11-01T09:00:00Z",
            "2026-11-01T10:00:00Z",
            null,
            null,
            null,
            null,
            List.of(),
            10,
            service.oldest(),
            SearchProperties.defaults());
    return service.search(query, null);
  }

  private static String hits(String shards, boolean timedOut) {
    return "{\"took\":3,\"timed_out\":"
        + timedOut
        + ",\"_shards\":"
        + shards
        + ",\"hits\":{\"hits\":[{\"_source\":{\"event_id\":\"e1\",\"@timestamp\":"
        + "\"2026-11-01T09:30:00Z\",\"body\":\"disk full\",\"severity_number\":17},"
        + "\"sort\":[1793525400000,\"e1\"]}]}}";
  }

  @Test
  void aCompleteAnswerIsNotPartial() {
    SearchResult result = search(hits("{\"total\":2,\"successful\":2,\"failed\":0}", false));

    assertFalse(result.partial());
    assertEquals(1, result.hits().size());
    assertEquals("disk full", result.hits().get(0).body());
  }

  @Test
  void aFailedShardOrATimeoutMakesThePagePartial() {
    assertTrue(search(hits("{\"total\":2,\"successful\":1,\"failed\":1}", false)).partial());
    assertTrue(search(hits("{\"total\":2,\"successful\":2,\"failed\":0}", true)).partial());
  }
}
