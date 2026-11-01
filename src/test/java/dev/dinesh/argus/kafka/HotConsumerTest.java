package dev.dinesh.argus.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.config.KafkaProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.hot.HotBulkWriter;
import dev.dinesh.argus.hot.HotIndices;
import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.wal.WalFixture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The hot consumer on Apache Kafka and Redpanda, indexing into a protocol stub of {@code _bulk}.
 */
class HotConsumerTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant T = Instant.parse("2026-10-31T12:00:00Z");

  @TempDir Path dir;

  private HttpServer server;

  /** Document ids the stub has stored, and how many times each was sent. */
  private final ConcurrentHashMap<String, AtomicInteger> stored = new ConcurrentHashMap<>();

  /** Bulks to fail with 503 before answering normally. */
  private final AtomicInteger failNext = new AtomicInteger();

  private final AtomicInteger bulks = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    int status = 200;
    String body = "{}";
    if (path.equals("/")) {
      body = "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"2.17.1\"}}";
    } else if (path.startsWith("/_index_template/") && exchange.getRequestMethod().equals("GET")) {
      status = 404;
    } else if (path.startsWith("/_cat/indices")) {
      body = "[]";
    } else if (path.equals("/_bulk")) {
      bulks.incrementAndGet();
      String[] lines =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("\n");
      if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
        status = 503;
        body = "{\"error\":\"down\"}";
      } else {
        StringBuilder items = new StringBuilder("{\"errors\":false,\"items\":[");
        for (int i = 0; i < lines.length; i += 2) {
          String id = WalFixture.MAPPER.readTree(lines[i]).get("index").get("_id").asText();
          stored.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
          items.append(i == 0 ? "" : ",").append("{\"index\":{\"status\":201}}");
        }
        body = items.append("]}").toString();
      }
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private HotConsumer consumer(KafkaWal wal) {
    HotProperties hot =
        new HotProperties(
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
            4,
            null,
            null,
            null,
            null);
    SearchEngineClient client =
        new SearchEngineClient(
            hot.url(),
            null,
            null,
            null,
            Duration.ofSeconds(5),
            HttpClient.newHttpClient(),
            WalFixture.MAPPER);
    HotBulkWriter writer =
        new HotBulkWriter(
            client,
            new HotIndices(hot),
            hot,
            ArgusMetrics.noop(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMillis(1));
    return new HotConsumer(wal, wal.props(), writer, ArgusMetrics.noop());
  }

  private static void await(String what, BooleanSupplier done) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (!done.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      Thread.sleep(50);
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void indexesEveryLineAndSkipsMarkers(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir);
    try (KafkaWal wal = new KafkaWal(props)) {
      WalFixture fixture =
          new WalFixture(new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop()));
      fixture.write("acme", "app", 0, 0, 10, T);
      fixture.write("globex", "web", 0, 0, 3, T);

      HotConsumer consumer = consumer(wal);
      consumer.start();
      try {
        await("13 documents", () -> stored.size() == 13);
      } finally {
        consumer.stop();
      }
      assertTrue(stored.containsKey("acme-app-0-90"));
      assertTrue(stored.containsKey("globex-web-0-20"));
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aFailedBulkIsSentAgainAndNothingIsLostAcrossARestart(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir);
    try (KafkaWal wal = new KafkaWal(props)) {
      WalFixture fixture =
          new WalFixture(new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop()));
      fixture.write("acme", "app", 0, 0, 10, T);
      failNext.set(2);

      HotConsumer first = consumer(wal);
      first.start();
      try {
        await("10 documents after two failed bulks", () -> stored.size() == 10);
      } finally {
        first.stop();
      }
      assertTrue(bulks.get() >= 2 + 3, "two failures, then at least 3 bulks of ≤4: " + bulks.get());

      // Stopped after committing: a new member of the group starts after what was indexed.
      fixture.write("acme", "app", 0, 100, 5, T);
      HotConsumer second = consumer(wal);
      second.start();
      try {
        await("15 documents", () -> stored.size() == 15);
        Thread.sleep(500);
      } finally {
        second.stop();
      }
      Set<Integer> counts = Set.copyOf(stored.values().stream().map(AtomicInteger::get).toList());
      assertEquals(Set.of(1), counts, "nothing sent again after the restart: " + stored);
    }
  }
}
