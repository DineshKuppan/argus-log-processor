package dev.dinesh.argus.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.ExportProperties;
import dev.dinesh.argus.export.ElasticsearchExportSource.Flavor;
import dev.dinesh.argus.export.ExportSource.Cursor;
import dev.dinesh.argus.export.ExportSource.Page;
import dev.dinesh.argus.export.ExportSource.Query;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;

/**
 * The Elasticsearch export source against real Elasticsearch 8 and OpenSearch 2 (Testcontainers;
 * skipped without Docker). Hits share timestamps across page boundaries, so the tiebreakers matter.
 */
class ElasticsearchExportSourceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final String INDEX = "logs-app";
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

  /** 30 hits in range: three per second over 10s; plus one before and one at {@code TO}. */
  private static final int IN_RANGE = 30;

  private static final Instant TO = FROM.plusSeconds(10);
  private static final int PAGE_SIZE = 4;

  private static final Map<Flavor, GenericContainer<?>> CONTAINERS =
      Map.of(
          Flavor.ELASTICSEARCH,
          new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.15.3")
              .withEnv("discovery.type", "single-node")
              .withEnv("xpack.security.enabled", "false")
              .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
              .withExposedPorts(9200)
              .waitingFor(
                  Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3))),
          Flavor.OPENSEARCH,
          new GenericContainer<>("opensearchproject/opensearch:2.17.1")
              .withEnv("discovery.type", "single-node")
              .withEnv("DISABLE_SECURITY_PLUGIN", "true")
              .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
              .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
              .withExposedPorts(9200)
              .waitingFor(
                  Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3))));

  @BeforeAll
  static void start() throws Exception {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    Startables.deepStart(CONTAINERS.values().stream()).join();
    for (Flavor flavor : Flavor.values()) {
      seed(url(flavor));
    }
  }

  @AfterAll
  static void stop() {
    CONTAINERS.values().forEach(GenericContainer::stop);
  }

  private static String url(Flavor flavor) {
    GenericContainer<?> c = CONTAINERS.get(flavor);
    return "http://" + c.getHost() + ":" + c.getMappedPort(9200);
  }

  private static void seed(String url) throws Exception {
    call(
        url,
        "PUT",
        "/" + INDEX,
        "{\"settings\":{\"number_of_shards\":3,\"number_of_replicas\":0},"
            + "\"mappings\":{\"properties\":{\"@timestamp\":{\"type\":\"date\"},"
            + "\"service\":{\"type\":\"keyword\"}}}}");
    StringBuilder bulk = new StringBuilder();
    for (int i = 0; i < IN_RANGE; i++) {
      doc(bulk, FROM.plusSeconds(i / 3), "m" + i, i % 2 == 0 ? "api" : "web");
    }
    doc(bulk, FROM.minusMillis(1), "before", "api");
    doc(bulk, TO, "at-to", "api");
    call(url, "POST", "/_bulk?refresh=true", bulk.toString());
  }

  private static void doc(StringBuilder bulk, Instant at, String message, String service) {
    bulk.append("{\"index\":{\"_index\":\"" + INDEX + "\"}}\n")
        .append(
            "{\"@timestamp\":\""
                + at
                + "\",\"message\":\""
                + message
                + "\",\"service\":\""
                + service
                + "\"}\n");
  }

  private static void call(String url, String method, String path, String body) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url + path))
                .header("Content-Type", "application/x-ndjson")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 300 || response.body().contains("\"errors\":true")) {
      throw new IOException(method + " " + path + ": " + response.body());
    }
  }

  private static ElasticsearchExportSource source(Flavor flavor, String index) {
    return new ElasticsearchExportSource(
        new ExportProperties.Source(
            "es",
            "acme",
            ExportProperties.Type.ELASTICSEARCH,
            URI.create(url(flavor)),
            index,
            null,
            null,
            null,
            null,
            PAGE_SIZE,
            null,
            null,
            null,
            Duration.ofSeconds(30)),
        HTTP,
        MAPPER);
  }

  private static List<JsonNode> exportAll(ExportSource source, Query query) throws Exception {
    List<JsonNode> hits = new ArrayList<>();
    Cursor cursor = null;
    for (int pages = 0; pages < 100; pages++) {
      Page page = source.fetch(query, cursor);
      for (String line : page.lines()) {
        hits.add(MAPPER.readTree(line));
      }
      cursor = page.next();
      if (page.last()) {
        source.release(cursor);
        return hits;
      }
    }
    throw new AssertionError("no last page");
  }

  private static List<String> messages(List<JsonNode> hits) {
    return hits.stream().map(h -> h.get("message").asText()).toList();
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void detectsTheFlavour(Flavor flavor) throws Exception {
    assertEquals(flavor, source(flavor, INDEX).flavor());
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void exportsEveryHitInTheRangeOnceInTimestampOrder(Flavor flavor) throws Exception {
    List<JsonNode> hits = exportAll(source(flavor, INDEX), new Query(null, FROM, TO));

    List<String> messages = messages(hits);
    assertEquals(IN_RANGE, messages.size(), messages.toString());
    assertEquals(IN_RANGE, new HashSet<>(messages).size(), "no hit twice: " + messages);
    assertFalse(messages.contains("before"));
    assertFalse(messages.contains("at-to"), "to is exclusive");
    List<String> times = hits.stream().map(h -> h.get("@timestamp").asText()).toList();
    assertEquals(times.stream().sorted().toList(), times);
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void theQueryNarrowsTheExport(Flavor flavor) throws Exception {
    List<JsonNode> hits =
        exportAll(
            source(flavor, INDEX),
            new Query(MAPPER.readTree("{\"term\":{\"service\":\"api\"}}"), FROM, TO));

    assertEquals(IN_RANGE / 2, hits.size());
    assertTrue(hits.stream().allMatch(h -> h.get("service").asText().equals("api")));
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void theSameCursorFetchesTheSamePage(Flavor flavor) throws Exception {
    ExportSource source = source(flavor, INDEX);
    Query query = new Query(null, FROM, TO);
    Page first = source.fetch(query, null);

    Page second = source.fetch(query, first.next());
    Page again = source.fetch(query, first.next());

    assertEquals(second.pageId(), again.pageId());
    assertEquals(second.lines(), again.lines());
    source.release(again.next());
  }

  /**
   * The point in time is gone (expired while the job paused): OpenSearch continues exactly;
   * Elasticsearch continues from the last timestamp, so only hits sharing it can come twice.
   */
  @ParameterizedTest
  @EnumSource(Flavor.class)
  void anExpiredPointInTimeIsReplacedWithoutMissingAHit(Flavor flavor) throws Exception {
    ExportSource source = source(flavor, INDEX);
    Query query = new Query(null, FROM, TO);
    List<JsonNode> hits = new ArrayList<>();
    Cursor cursor = null;
    for (int pages = 0; pages < 100; pages++) {
      if (cursor != null && pages % 2 == 0) {
        source.release(cursor); // as if keep-alive ran out
      }
      Page page = source.fetch(query, cursor);
      for (String line : page.lines()) {
        hits.add(MAPPER.readTree(line));
      }
      cursor = page.next();
      if (page.last()) {
        break;
      }
    }
    source.release(cursor);

    List<String> messages = messages(hits);
    Set<String> distinct = new HashSet<>(messages);
    assertEquals(IN_RANGE, distinct.size(), "every hit exported: " + messages);
    if (flavor == Flavor.OPENSEARCH) {
      assertEquals(IN_RANGE, messages.size(), "exactly once: " + messages);
    } else {
      // At most the two others sharing a page's last timestamp, for each replaced point in time.
      assertTrue(
          messages.size() <= IN_RANGE + 2 * (messages.size() / PAGE_SIZE), messages.toString());
    }
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void aMissingIndexOrABadQueryFailsPermanently(Flavor flavor) {
    assertThrows(
        PermanentExportException.class,
        () -> source(flavor, "no-such-index").fetch(new Query(null, FROM, TO), null));
    assertThrows(
        PermanentExportException.class,
        () ->
            source(flavor, INDEX)
                .fetch(new Query(MAPPER.readTree("{\"no_such_query\":{}}"), FROM, TO), null));
  }

  @ParameterizedTest
  @EnumSource(Flavor.class)
  void anUnreachableClusterIsATransientFailure(Flavor flavor) {
    ElasticsearchExportSource unreachable =
        new ElasticsearchExportSource(
            new ExportProperties.Source(
                "es",
                "acme",
                ExportProperties.Type.ELASTICSEARCH,
                URI.create("http://127.0.0.1:1"),
                INDEX,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                Duration.ofSeconds(2)),
            HTTP,
            MAPPER);
    IOException e =
        assertThrows(IOException.class, () -> unreachable.fetch(new Query(null, FROM, TO), null));
    assertFalse(e instanceof PermanentExportException);
  }

  static Stream<Flavor> flavors() {
    return Stream.of(Flavor.values());
  }
}
