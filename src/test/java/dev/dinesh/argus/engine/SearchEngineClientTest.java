package dev.dinesh.argus.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.dinesh.argus.engine.SearchEngineClient.Flavor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchEngineClientTest {

  private HttpServer server;
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final AtomicInteger rootCalls = new AtomicInteger();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private SearchEngineClient client(String root, String apiKey, String user, String password)
      throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          authorizations.add(
              String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] body =
              switch (exchange.getRequestURI().getPath()) {
                case "/" -> {
                  rootCalls.incrementAndGet();
                  yield root.getBytes(StandardCharsets.UTF_8);
                }
                case "/big" -> "x".repeat(100).getBytes(StandardCharsets.UTF_8);
                default -> "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
              };
          int status = exchange.getRequestURI().getPath().equals("/missing") ? 404 : 200;
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    return new SearchEngineClient(
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
        apiKey,
        user,
        password,
        Duration.ofSeconds(5),
        HttpClient.newHttpClient(),
        new ObjectMapper());
  }

  @Test
  void readsTheFlavourAndVersionOnce() throws Exception {
    SearchEngineClient os =
        client(
            "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"2.17.1\"}}",
            null,
            null,
            null);

    assertEquals(Flavor.OPENSEARCH, os.flavor());
    assertEquals(2, os.engine().major());
    assertEquals(17, os.engine().minor());
    os.engine();
    assertEquals(1, rootCalls.get(), "cached");
  }

  @Test
  void elasticsearchHasNoDistribution() throws Exception {
    SearchEngineClient es = client("{\"version\":{\"number\":\"8.15.3\"}}", null, null, null);

    assertEquals(Flavor.ELASTICSEARCH, es.flavor());
    assertEquals(8, es.engine().major());
  }

  @Test
  void sendsAnApiKeyOrBasicCredentials() throws Exception {
    client("{}", "k3y", "ignored", "ignored").get("/x");
    assertEquals("ApiKey k3y", authorizations.get(0));
    stop();
    authorizations.clear();

    SearchEngineClient basic = client("{}", " ", "elastic", "s3cret");
    basic.get("/x");
    assertEquals("Basic ZWxhc3RpYzpzM2NyZXQ=", authorizations.get(0));
    assertFalse(basic.toString().contains("s3cret"));
  }

  @Test
  void returnsStatusesAndCapsTheBody() throws Exception {
    SearchEngineClient c = client("{}", null, null, null);

    SearchEngineClient.Response missing = c.get("/missing");
    assertEquals(404, missing.status());
    assertFalse(missing.ok());
    assertFalse(missing.transientFailure());
    assertEquals(100, c.send("GET", "/big", null, null, 100).body().length());
    assertThrows(
        SearchEngineClient.ResponseTooLargeException.class,
        () -> c.send("GET", "/big", null, null, 99));
    assertTrue(new SearchEngineClient.Response(503, "").transientFailure());
  }
}
