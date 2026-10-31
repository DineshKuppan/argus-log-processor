package dev.dinesh.argus.hot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.wal.WalFixture;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The indexer against a protocol stub of {@code _bulk} and the calls around it. */
class HotIndexerTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant T = Instant.parse("2026-10-31T12:00:00Z");

  @TempDir Path root;

  private HttpServer server;
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** The ids of each {@code _bulk} request, in order. */
  private final List<List<String>> bulks = new CopyOnWriteArrayList<>();

  /** Answers for the next bulks (by their ids); then every item is created. */
  private final ConcurrentLinkedQueue<Function<List<String>, String>> answers =
      new ConcurrentLinkedQueue<>();

  private WalFixture fixture;
  private WalSegments wal;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    fixture = new WalFixture(root);
    wal = new WalSegments(root, Set.of(WalSink.HOT), true);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    int status = 200;
    String body = "{}";
    if (path.equals("/")) {
      body = "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"2.17.1\"}}";
    } else if (path.startsWith("/_index_template/") && method.equals("GET")) {
      status = 404;
    } else if (path.startsWith("/_cat/indices")) {
      body = "[]";
    } else if (path.equals("/_bulk")) {
      List<String> ids = new ArrayList<>();
      String[] lines =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("\n");
      for (int i = 0; i < lines.length; i += 2) {
        ids.add(WalFixture.MAPPER.readTree(lines[i]).get("index").get("_id").asText());
      }
      bulks.add(ids);
      Function<List<String>, String> answer = answers.poll();
      body = answer != null ? answer.apply(ids) : items(ids, id -> 201);
      if (body.startsWith("HTTP ")) {
        status = Integer.parseInt(body.substring(5, 8));
        body = "{\"error\":\"down\"}";
      }
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /** A filtered {@code _bulk} response with one status per id. */
  private static String items(List<String> ids, Function<String, Integer> status) {
    ObjectNode body = WalFixture.MAPPER.createObjectNode();
    ArrayNode items = body.putArray("items");
    boolean errors = false;
    for (String id : ids) {
      int s = status.apply(id);
      ObjectNode result = items.addObject().putObject("index").put("status", s);
      if (s >= 300) {
        errors = true;
        result
            .putObject("error")
            .put("type", s == 429 ? "es_rejected_execution_exception" : "mapper_parsing_exception")
            .put("reason", "test");
      }
    }
    body.put("errors", errors);
    return body.toString();
  }

  private HotProperties props(int bulkMaxDocs) {
    return new HotProperties(
        HotProperties.Store.OPENSEARCH,
        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
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
        bulkMaxDocs,
        null,
        null,
        null,
        null);
  }

  private HotIndexer indexer(int bulkMaxDocs) {
    HotProperties props = props(bulkMaxDocs);
    SearchEngineClient client =
        new SearchEngineClient(
            props.url(),
            null,
            null,
            null,
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            WalFixture.MAPPER);
    return new HotIndexer(
        client,
        wal,
        new HotIndices(props),
        props,
        new ArgusMetrics(meters),
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofMillis(1));
  }

  private int documents() {
    return bulks.stream().mapToInt(List::size).sum();
  }

  @Test
  void indexesEverySegmentInBulksAndAcknowledgesIt() throws Exception {
    Path a = fixture.segment("acme", "app", 0, 0, 12, T);
    Path b = fixture.segment("globex", "web", 0, 0, 3, T);
    HotIndexer indexer = indexer(5);

    assertTrue(indexer.runOnce());

    assertEquals(List.of(5, 5, 2, 3), bulks.stream().map(List::size).toList());
    assertFalse(Files.exists(a));
    assertFalse(Files.exists(b));
    assertEquals(List.of(), wal.pending(WalSink.HOT));
    assertEquals(
        12,
        meters
            .get("argus.hot.documents")
            .tag("tenant", "acme")
            .tag("outcome", "indexed")
            .counter()
            .count());
  }

  @Test
  void itemsTheClusterIsTooBusyForAreRetried() throws Exception {
    fixture.segment("acme", "app", 0, 0, 4, T);
    answers.add(ids -> items(ids, id -> id.endsWith("-10") || id.endsWith("-30") ? 429 : 201));

    assertTrue(indexer(10).runOnce());

    assertEquals(2, bulks.size());
    assertEquals(List.of("acme-app-0-10", "acme-app-0-30"), bulks.get(1), "only the 429s again");
    assertEquals(List.of(), wal.pending(WalSink.HOT));
  }

  @Test
  void aDocumentTheMappingRejectsIsCountedAndTheSegmentStillAcknowledged() throws Exception {
    fixture.segment("acme", "app", 0, 0, 3, T);
    answers.add(ids -> items(ids, id -> id.endsWith("-10") ? 400 : 201));

    assertTrue(indexer(10).runOnce());

    assertEquals(1, bulks.size(), "not retried");
    assertEquals(List.of(), wal.pending(WalSink.HOT));
    assertEquals(1, meters.get("argus.hot.documents").tag("outcome", "rejected").counter().count());
  }

  @Test
  void aFailedRequestIsNotAcknowledgedAndTheRetryResumesAtTheLastGoodBulk() throws Exception {
    Path segment = fixture.segment("acme", "app", 0, 0, 10, T);
    answers.add(ids -> items(ids, id -> 201));
    answers.add(ids -> "HTTP 503");
    HotIndexer indexer = indexer(4);

    assertFalse(indexer.runOnce());
    assertTrue(Files.exists(segment));
    assertEquals(1, wal.pending(WalSink.HOT).size());

    assertTrue(indexer.runOnce());

    assertEquals(
        List.of("acme-app-0-40", "acme-app-0-50", "acme-app-0-60", "acme-app-0-70"),
        bulks.get(2),
        "the first bulk isn't sent again");
    assertEquals(4 + 4 + 4 + 2, documents());
    assertFalse(Files.exists(segment));
  }

  @Test
  void itemsStillBusyAfterTheRetriesFailThePass() throws Exception {
    fixture.segment("acme", "app", 0, 0, 2, T);
    for (int i = 0; i < HotIndexer.ITEM_ATTEMPTS; i++) {
      answers.add(ids -> items(ids, id -> 429));
    }

    assertFalse(indexer(10).runOnce());

    assertEquals(HotIndexer.ITEM_ATTEMPTS, bulks.size());
    assertEquals(1, wal.pending(WalSink.HOT).size());
  }

  @Test
  void linesOlderThanTheRetentionAreSkipped() throws Exception {
    fixture.segment(
        "acme",
        "app",
        0,
        0,
        List.of(Instant.parse("2026-10-01T00:00:00Z"), T, Instant.parse("2026-10-17T23:59:59Z")));

    assertTrue(indexer(10).runOnce());

    assertEquals(List.of(List.of("acme-app-0-10")), bulks);
    assertEquals(List.of(), wal.pending(WalSink.HOT));
    assertEquals(
        2, meters.get("argus.hot.documents").tag("outcome", "outside_retention").counter().count());
  }

  @Test
  void anUnreadableSegmentIsSetAside() throws Exception {
    Path segment = fixture.segment("acme", "app", 0, 0, 1, T);
    Files.writeString(segment, "{not json\n");

    assertTrue(indexer(10).runOnce());

    assertTrue(Files.exists(Path.of(segment + WalSegments.UNREADABLE_SUFFIX)));
    assertEquals(List.of(), wal.pending());
    assertEquals(List.of(), bulks);
  }

  @Test
  void theIndexActionsCarryTheDayIndexIdAndRouting() throws Exception {
    fixture.segment("acme", "app", 0, 0, 1, T);
    List<JsonNode> actions = new CopyOnWriteArrayList<>();
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          if (exchange.getRequestURI().getPath().equals("/_bulk")) {
            String text =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            actions.add(WalFixture.MAPPER.readTree(text.split("\n")[0]));
            byte[] ok = "{\"errors\":false,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
          } else {
            handle(exchange);
          }
        });

    assertTrue(indexer(10).runOnce());

    JsonNode meta = actions.get(0).get("index");
    assertEquals("argus-logs-2026.10.31-shared", meta.get("_index").asText());
    assertEquals("acme-app-0-0", meta.get("_id").asText());
    assertEquals("acme", meta.get("routing").asText());
  }
}
