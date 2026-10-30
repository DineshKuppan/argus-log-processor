package dev.dinesh.argus.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.tail.TailHub;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The SSE endpoint through the real server: tenancy, filters, limits and a pushed line arriving.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "argus.tail.max-connections-per-tenant=2",
      "argus.tail.heartbeat=200ms",
      "argus.push.suppress=false"
    })
class TailControllerTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @TempDir static Path work;

  @LocalServerPort int port;
  @Autowired TailHub hub;

  private final List<Stream<String>> open = new ArrayList<>();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("argus.ingest.checkpoint-dir", () -> work.resolve("cp").toString());
    registry.add("argus.sink.local.root", () -> work.resolve("out").toString());
    registry.add("argus.sink.local.min-free-bytes", () -> "0B");
    registry.add("argus.push.spool-dir", () -> work.resolve("spool").toString());
    registry.add("argus.tenancy.api-keys[0].tenant", () -> "acme");
    registry.add("argus.tenancy.api-keys[0].key-sha256", () -> sha256("acme-key"));
    registry.add("argus.tenancy.api-keys[1].tenant", () -> "globex");
    registry.add("argus.tenancy.api-keys[1].key-sha256", () -> sha256("globex-key"));
  }

  private static String sha256(String key) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A closed client is noticed at the next write: a heartbeat, every 200ms here. */
  @AfterEach
  void closeStreams() throws InterruptedException {
    open.forEach(Stream::close);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (hub.connections() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(0, hub.connections(), "every tail connection noticed its client left");
  }

  private HttpRequest.Builder request(String path, String key) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    return key == null ? builder : builder.header("X-Argus-Api-Key", key);
  }

  /** As an SSE client asks: {@code EventSource} sends exactly this {@code Accept}. */
  private HttpRequest.Builder tailRequest(String path, String key) {
    return request(path, key).header("Accept", "text/event-stream");
  }

  /** An open tail whose lines arrive in a queue. */
  private record Tail(HttpResponse<Stream<String>> response, LinkedBlockingQueue<String> lines) {
    String awaitLineContaining(String text) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (true) {
        String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (line == null) {
          throw new AssertionError("no line containing " + text);
        }
        if (line.contains(text)) {
          return line;
        }
      }
    }
  }

  private Tail tail(String key, String query) throws Exception {
    HttpResponse<Stream<String>> response =
        HTTP.send(
            tailRequest("/api/v2/tail" + query, key).GET().build(),
            HttpResponse.BodyHandlers.ofLines());
    open.add(response.body());
    LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader = new Thread(() -> response.body().forEach(lines::add));
    reader.setDaemon(true);
    reader.start();
    return new Tail(response, lines);
  }

  private void push(String key, String body) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            request("/api/v2/ingest/streams/app", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, response.statusCode(), response.body());
  }

  @Test
  void aTailNeedsAnApiKeyAndAValidFilter() throws Exception {
    assertEquals(
        401,
        HTTP.send(tailRequest("/api/v2/tail", null).build(), HttpResponse.BodyHandlers.ofString())
            .statusCode());
    HttpResponse<String> bad =
        HTTP.send(
            tailRequest("/api/v2/tail?level=loud", "acme-key").build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(400, bad.statusCode());
    assertTrue(bad.body().contains("Unknown level"), bad.body());
  }

  @Test
  void aPushedLineReachesItsTenantsTailOnly() throws Exception {
    Tail acme = tail("acme-key", "?level=warn");
    assertEquals(200, acme.response().statusCode());
    assertTrue(
        acme.response()
            .headers()
            .firstValue("Content-Type")
            .orElse("")
            .startsWith("text/event-stream"));
    assertEquals("no", acme.response().headers().firstValue("X-Accel-Buffering").orElse(null));
    acme.awaitLineContaining(": connected");

    push("globex-key", "{\"level\":\"error\",\"msg\":\"globex secret\"}\n");
    push("acme-key", "{\"level\":\"info\",\"msg\":\"too quiet\"}\n");
    push("acme-key", "{\"level\":\"error\",\"msg\":\"disk full\"}\n");

    String data = acme.awaitLineContaining("disk full");
    assertTrue(data.startsWith("data: {"), data);
    assertFalse(acme.lines().stream().anyMatch(l -> l.contains("globex secret")));
    assertFalse(acme.lines().stream().anyMatch(l -> l.contains("too quiet")));
  }

  @Test
  void aTenantOverItsConnectionLimitGets429() throws Exception {
    tail("acme-key", "").awaitLineContaining(": connected");
    tail("acme-key", "").awaitLineContaining(": connected");

    HttpResponse<String> third =
        HTTP.send(
            tailRequest("/api/v2/tail", "acme-key").build(), HttpResponse.BodyHandlers.ofString());

    assertEquals(429, third.statusCode());
    assertEquals("30", third.headers().firstValue("Retry-After").orElse(null));
    assertTrue(third.body().contains("max-connections-per-tenant"), third.body());
    tail("globex-key", "").awaitLineContaining(": connected");
  }
}
