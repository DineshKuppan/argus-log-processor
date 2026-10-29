package dev.dinesh.argus.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dinesh.argus.config.ExportProperties;
import dev.dinesh.argus.export.ExportSource.Cursor;
import dev.dinesh.argus.export.ExportSource.Page;
import dev.dinesh.argus.export.ExportSource.Query;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Coralogix source against a stub of the DataPrime query API: request shape, NDJSON answers,
 * window halving and growth, and failures.
 */
class CoralogixExportSourceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant TO = FROM.plus(Duration.ofHours(4));
  private static final int LIMIT = 10;

  private HttpServer server;
  private final List<Instant> rows = new ArrayList<>();
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  /** Status codes to answer with before answering normally. */
  private final List<Integer> failWith = new CopyOnWriteArrayList<>();

  private final AtomicInteger incomplete = new AtomicInteger();
  private volatile boolean warnInsteadOfFilling;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(CoralogixExportSource.QUERY_PATH, this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    JsonNode body = MAPPER.readTree(exchange.getRequestBody());
    requests.add(body);
    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
    if (!failWith.isEmpty()) {
      byte[] error = "{\"message\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(failWith.remove(0), error.length);
      exchange.getResponseBody().write(error);
      exchange.close();
      return;
    }
    JsonNode metadata = body.get("metadata");
    Instant start = Instant.parse(metadata.get("startDate").asText());
    Instant end = Instant.parse(metadata.get("endDate").asText());
    int limit = metadata.get("limit").asInt();
    List<Instant> matching =
        rows.stream().filter(t -> !t.isBefore(start) && t.isBefore(end)).toList();
    boolean truncated = matching.size() > limit;
    List<Instant> answer =
        truncated ? matching.subList(0, warnInsteadOfFilling ? limit - 1 : limit) : matching;
    if (truncated && warnInsteadOfFilling) {
      exchange.getResponseHeaders().add("Warning", "299 - \"NumberOfResultsLimitWarning\"");
    }
    StringBuilder ndjson = new StringBuilder("{\"queryId\":{\"queryId\":\"q-1\"}}\n");
    // Rows in batches of three, as the API streams them.
    for (int i = 0; i < answer.size(); i += 3) {
      ArrayNode results = MAPPER.createArrayNode();
      for (Instant t : answer.subList(i, Math.min(i + 3, answer.size()))) {
        results.add(row(t));
      }
      ObjectNode result = MAPPER.createObjectNode();
      result.putObject("result").set("results", results);
      ndjson.append(MAPPER.writeValueAsString(result)).append('\n');
    }
    String status =
        incomplete.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? "INCOMPLETE" : "COMPLETED";
    ndjson.append(
        "{\"statistics\":{\"status\":\""
            + status
            + "\",\"outputRowCount\":\""
            + answer.size()
            + "\"}}\n");
    byte[] bytes = ndjson.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static JsonNode row(Instant t) throws IOException {
    ObjectNode row = MAPPER.createObjectNode();
    ArrayNode metadata = row.putArray("metadata");
    metadata.addObject().put("key", "timestamp").put("value", t.toString());
    metadata.addObject().put("key", "severity").put("value", "Error");
    metadata.addObject().put("key", "logid").put("value", "id-" + t.toEpochMilli());
    ArrayNode labels = row.putArray("labels");
    labels.addObject().put("key", "applicationname").put("value", "shop");
    labels.addObject().put("key", "subsystemname").put("value", "api");
    row.put("userData", "{\"message\":\"at " + t + "\",\"user\":42}");
    return row;
  }

  private CoralogixExportSource source(String tier) {
    return new CoralogixExportSource(
        new ExportProperties.Source(
            "cx",
            "acme",
            ExportProperties.Type.CORALOGIX,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            null,
            null,
            "cx-key",
            null,
            null,
            LIMIT,
            null,
            tier,
            Duration.ofHours(1),
            Duration.ofSeconds(5)),
        HttpClient.newHttpClient(),
        MAPPER);
  }

  private List<Page> exportAll(ExportSource source, Query query) throws Exception {
    List<Page> pages = new ArrayList<>();
    Cursor cursor = null;
    for (int i = 0; i < 1_000; i++) {
      Page page = source.fetch(query, cursor);
      pages.add(page);
      if (page.last()) {
        return pages;
      }
      cursor = page.next();
    }
    throw new AssertionError("no last page");
  }

  private static List<String> messages(List<Page> pages) throws IOException {
    List<String> out = new ArrayList<>();
    for (Page page : pages) {
      for (String line : page.lines()) {
        out.add(MAPPER.readTree(line).get("message").asText());
      }
    }
    return out;
  }

  @Test
  void exportsEveryRowOnceHalvingFullWindowsAndGrowingSparseOnes() throws Exception {
    // Sparse first hour, a dense burst of 35 rows in one minute, then sparse again.
    for (int m = 0; m < 240; m += 20) {
      rows.add(FROM.plus(Duration.ofMinutes(m)));
    }
    Instant burst = FROM.plus(Duration.ofMinutes(90));
    for (int s = 0; s < 35; s++) {
      rows.add(burst.plusSeconds(s));
    }

    List<Page> pages = exportAll(source(null), new Query(null, FROM, TO));

    List<String> messages = messages(pages);
    assertEquals(rows.size(), messages.size(), "every row exactly once");
    assertEquals(rows.size(), new HashSet<>(messages).size());
    for (int i = 1; i < pages.size(); i++) {
      String previousEnd = pages.get(i - 1).pageId().split("/")[1];
      assertEquals(previousEnd, pages.get(i).pageId().split("/")[0], "windows tile the range");
    }
    assertEquals(FROM.toString(), pages.get(0).pageId().split("/")[0]);
    assertEquals(TO.toString(), pages.get(pages.size() - 1).pageId().split("/")[1]);
    assertTrue(pages.stream().allMatch(p -> p.lines().size() < LIMIT), "no page is cut short");
    long longest =
        pages.stream()
            .mapToLong(
                p ->
                    Duration.between(
                            Instant.parse(p.pageId().split("/")[0]),
                            Instant.parse(p.pageId().split("/")[1]))
                        .toMillis())
            .max()
            .orElseThrow();
    assertEquals(Duration.ofHours(1).toMillis(), longest, "sparse windows grow back to 1h");
    assertTrue(requests.size() > pages.size(), "full windows were queried again, halved");
  }

  @Test
  void aResultLimitWarningAlsoHalvesTheWindow() throws Exception {
    warnInsteadOfFilling = true;
    for (int s = 0; s < 25; s++) {
      rows.add(FROM.plusSeconds(s));
    }

    List<String> messages =
        messages(exportAll(source(null), new Query(null, FROM, FROM.plusSeconds(60))));

    assertEquals(25, new HashSet<>(messages).size());
    assertEquals(25, messages.size());
  }

  @Test
  void anIncompleteQueryIsRetriedOnAHalfWindow() throws Exception {
    rows.add(FROM.plusSeconds(1));
    incomplete.set(1);

    List<Page> pages = exportAll(source(null), new Query(null, FROM, FROM.plusSeconds(60)));

    assertEquals(List.of("at " + FROM.plusSeconds(1)), messages(pages));
    assertEquals(
        "2026-01-01T00:00:30Z",
        requests.get(1).path("metadata").path("endDate").asText(),
        "second query covers half of the first");
  }

  @Test
  void theRequestCarriesTheKeyTierSyntaxRangeAndLimit() throws Exception {
    source("archive")
        .fetch(
            new Query(TextNode.valueOf("source logs | filter $l.subsystemname == 'api'"), FROM, TO),
            null);

    JsonNode request = requests.get(0);
    assertEquals("Bearer cx-key", authorizations.get(0));
    assertEquals("source logs | filter $l.subsystemname == 'api'", request.get("query").asText());
    JsonNode metadata = request.get("metadata");
    assertEquals("TIER_ARCHIVE", metadata.get("tier").asText());
    assertEquals("QUERY_SYNTAX_DATAPRIME", metadata.get("syntax").asText());
    assertEquals(FROM.toString(), metadata.get("startDate").asText());
    assertEquals(FROM.plus(Duration.ofHours(1)).toString(), metadata.get("endDate").asText());
    assertEquals(LIMIT, metadata.get("limit").asInt());

    source(null).fetch(new Query(null, FROM, TO), null);
    assertEquals("TIER_FREQUENT_SEARCH", requests.get(1).path("metadata").path("tier").asText());
    assertEquals(CoralogixExportSource.DEFAULT_QUERY, requests.get(1).get("query").asText());
  }

  @Test
  void aRowBecomesAJsonLineWithItsMetadataAndLabels() throws Exception {
    rows.add(FROM.plusSeconds(5));

    Page page = source(null).fetch(new Query(null, FROM, TO), null);

    JsonNode line = MAPPER.readTree(page.lines().get(0));
    assertEquals("at " + FROM.plusSeconds(5), line.get("message").asText());
    assertEquals(42, line.get("user").asInt());
    assertEquals(FROM.plusSeconds(5).toString(), line.get("timestamp").asText());
    assertEquals("Error", line.get("severity").asText());
    assertEquals("shop", line.get("coralogix.applicationname").asText());
    assertEquals("api", line.get("coralogix.subsystemname").asText());
    assertEquals("id-" + FROM.plusSeconds(5).toEpochMilli(), line.get("coralogix.logid").asText());
  }

  @Test
  void theSameCursorQueriesTheSameWindow() throws Exception {
    rows.add(FROM.plus(Duration.ofMinutes(70)));
    CoralogixExportSource source = source(null);
    Query query = new Query(null, FROM, TO);
    Page first = source.fetch(query, null);

    Page second = source.fetch(query, first.next());
    Page again = source.fetch(query, first.next());

    assertEquals(second.pageId(), again.pageId());
    assertEquals(second.lines(), again.lines());
    assertFalse(second.lines().isEmpty());
  }

  @Test
  void moreRowsThanTheLimitInOneMillisecondFailsPermanently() {
    for (int i = 0; i < LIMIT + 1; i++) {
      rows.add(FROM);
    }

    PermanentExportException e =
        assertThrows(
            PermanentExportException.class,
            () -> source(null).fetch(new Query(null, FROM, TO), null));
    assertTrue(e.getMessage().contains("raise page-size"), e.getMessage());
  }

  @Test
  void clientErrorsArePermanentAndServerErrorsAreNot() {
    failWith.add(403);
    assertThrows(
        PermanentExportException.class, () -> source(null).fetch(new Query(null, FROM, TO), null));

    failWith.add(503);
    IOException transientFailure =
        assertThrows(IOException.class, () -> source(null).fetch(new Query(null, FROM, TO), null));
    assertFalse(transientFailure instanceof PermanentExportException);

    failWith.add(429);
    IOException throttled =
        assertThrows(IOException.class, () -> source(null).fetch(new Query(null, FROM, TO), null));
    assertFalse(throttled instanceof PermanentExportException);
  }

  @Test
  void onlyDataPrimeTextIsAValidQuery() throws Exception {
    CoralogixExportSource source = source(null);
    source.validate(null);
    source.validate(TextNode.valueOf("source logs"));
    assertThrows(
        IllegalArgumentException.class, () -> source.validate(MAPPER.readTree("{\"match\":{}}")));
    assertThrows(IllegalArgumentException.class, () -> source.validate(TextNode.valueOf(" ")));
  }
}
