package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.engine.EngineContainers;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Flavor;
import dev.dinesh.argus.hot.HotDocument;
import dev.dinesh.argus.hot.HotIndices;
import dev.dinesh.argus.hot.HotTemplates;
import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.parser.ParserChain;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.pipeline.EnvelopeFactory;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/**
 * The hot tier under load ({@code P5_PLAN.md} §5): the application in its own 256MB JVM (in a 2-CPU
 * container with {@code -Dargus.load.docker-cpus=2}), pushed to over HTTP, indexing into an
 * OpenSearch 2.17 container ({@code -Dargus.load.hot-engine=elasticsearch} for Elasticsearch 8.15).
 * Run with {@code mvn test -Dgroups=load -DexcludedGroups=none -Dtest=HotTierIT}.
 */
@Tag("load")
class HotTierIT {

  private static final String HEAP = "-Xmx256m";
  private static final String KEY = "load-key";
  private static final Flavor FLAVOR =
      "elasticsearch".equalsIgnoreCase(System.getProperty("argus.load.hot-engine"))
          ? Flavor.ELASTICSEARCH
          : Flavor.OPENSEARCH;
  private static final int RATE = Integer.getInteger("argus.load.hot-rate", 4_150);
  private static final int SECONDS = Integer.getInteger("argus.load.hot-seconds", 120);
  private static final int OUTAGE_SECONDS = Integer.getInteger("argus.load.outage-seconds", 60);
  private static final int STREAMS = 4;
  private static final int BATCH_LINES = 100;
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  @TempDir Path work;

  private static String engineUrlForServer() {
    return PushLoad.inDocker()
        ? "http://host.docker.internal:" + EngineContainers.port(FLAVOR)
        : EngineContainers.url(FLAVOR);
  }

  private static String prefix() {
    return "load" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** The P3a server arguments with a hot store and the production push flush (1m). */
  private List<String> args(int port, String prefix, String... extra) {
    List<String> args = new ArrayList<>();
    for (String a : PushLoad.serverArgs(work, port, Map.of("load", KEY))) {
      if (!a.startsWith("--argus.push.flush-interval=")) {
        args.add(a);
      }
    }
    args.addAll(
        List.of(
            "--argus.push.flush-interval=1m",
            "--argus.hot.store=" + FLAVOR.name().toLowerCase(),
            "--argus.hot.url=" + engineUrlForServer(),
            "--argus.hot.index-prefix=" + prefix,
            "--argus.hot.replicas=0",
            "--argus.sink.local.compression=zstd"));
    args.addAll(List.of(extra));
    return args;
  }

  /** {@code lines} NDJSON lines stamped now, unique per (stream, batch, line). */
  static byte[] batch(String stream, int batch, int lines) {
    StringBuilder sb = new StringBuilder(lines * 200);
    String now = Instant.now().toString();
    for (int l = 0; l < lines; l++) {
      int n = batch * lines + l;
      sb.append("{\"timestamp\":\"")
          .append(now)
          .append("\",\"level\":\"")
          .append(n % 50 == 0 ? "ERROR" : "INFO")
          .append("\",\"service\":\"checkout\",\"msg\":\"")
          .append(stream)
          .append(" b")
          .append(batch)
          .append(" l")
          .append(l)
          .append(" order placed user=")
          .append(n * 7919 % 100_000)
          .append("\",\"http_status\":")
          .append(n % 20 == 0 ? 500 : 200)
          .append(",\"trace_id\":\"")
          .append(String.format("%032x", (long) n * 2654435761L))
          .append("\"}\n");
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Pushes about {@code rate} lines a second from {@link #STREAMS} clients until stopped. */
  private static List<Future<?>> load(
      ExecutorService pool, Client client, int rate, AtomicBoolean stop, LongAdder acked) {
    List<Future<?>> clients = new ArrayList<>();
    long intervalNanos = TimeUnit.SECONDS.toNanos(1) * BATCH_LINES * STREAMS / rate;
    for (int s = 0; s < STREAMS; s++) {
      String stream = "s" + s;
      clients.add(
          pool.submit(
              () -> {
                long next = System.nanoTime();
                for (int b = 0; !stop.get(); b++) {
                  byte[] body = PushLoad.gzip(batch(stream, b, BATCH_LINES));
                  pushUntilAccepted(client, stream, body, stream + "-" + b);
                  acked.add(BATCH_LINES);
                  next += intervalNanos;
                  long wait = next - System.nanoTime();
                  if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                  }
                }
                return null;
              }));
    }
    return clients;
  }

  /** With the same key through 429s and restarts: each batch is ingested exactly once. */
  private static void pushUntilAccepted(Client client, String stream, byte[] body, String key)
      throws InterruptedException {
    while (true) {
      try {
        Client.Response r = client.push(stream, body, key);
        if (r.status() == 202 || r.status() == 200) {
          return;
        }
        if (r.status() != 429) {
          throw new IllegalStateException("unexpected " + r.status() + ": " + r.body());
        }
        Thread.sleep(Math.min(1000, Math.max(100, r.retryAfterSeconds() * 1000)));
      } catch (IOException e) {
        Thread.sleep(200);
      }
    }
  }

  private static long count(SearchEngineClient engine, String prefix) throws Exception {
    engine.send("POST", "/" + prefix + "-*/_refresh", null, null);
    SearchEngineClient.Response r =
        engine.send("GET", "/" + prefix + "-*/_count?ignore_unavailable=true", null, null);
    return r.ok() ? engine.mapper().readTree(r.body()).get("count").asLong() : -1;
  }

  private static long awaitCount(
      SearchEngineClient engine, String prefix, long expected, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    long count;
    while ((count = count(engine, prefix)) < expected && System.nanoTime() < deadline) {
      Thread.sleep(500);
    }
    return count;
  }

  /** T5: p99 from a pushed line's 202 to it being found by the search API. */
  @Test
  void pushedLinesAreSearchableWithinThirtySeconds() throws Exception {
    EngineContainers.assumeDocker();
    int port = PushLoad.freePort();
    String prefix = prefix();
    Server server = Server.start(work, HEAP, args(port, prefix));
    Client client = new Client(port, KEY);
    HttpClient http = HttpClient.newHttpClient();
    Instant from = Instant.now().minusSeconds(60);
    AtomicBoolean stop = new AtomicBoolean();
    LongAdder acked = new LongAdder();
    ExecutorService pool = Executors.newFixedThreadPool(STREAMS + 2);
    List<Future<?>> clients = load(pool, client, RATE, stop, acked);

    Map<Integer, Long> sentAt = new ConcurrentHashMap<>();
    Map<Integer, Long> foundAt = new ConcurrentHashMap<>();
    AtomicBoolean probing = new AtomicBoolean(true);
    Future<?> poller =
        pool.submit(
            () -> {
              while (probing.get() || foundAt.size() < sentAt.size()) {
                String url =
                    "http://127.0.0.1:"
                        + port
                        + "/api/v2/search?q=probe&source=probe&size=500&from="
                        + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
                        + "&to="
                        + URLEncoder.encode(
                            from.plus(Duration.ofDays(1)).toString(), StandardCharsets.UTF_8);
                HttpResponse<String> r =
                    http.send(
                        HttpRequest.newBuilder(URI.create(url))
                            .header("X-Argus-Api-Key", KEY)
                            .build(),
                        HttpResponse.BodyHandlers.ofString());
                long now = System.nanoTime();
                if (r.statusCode() == 200) {
                  for (JsonNode hit : JSON.readTree(r.body()).get("hits")) {
                    String[] words = hit.get("body").asText().split(" ");
                    foundAt.putIfAbsent(Integer.parseInt(words[1]), now);
                  }
                }
                Thread.sleep(250);
                if (!probing.get() && System.nanoTime() > now + TimeUnit.SECONDS.toNanos(120)) {
                  return null;
                }
              }
              return null;
            });
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
    for (int n = 0; System.nanoTime() < end; n++) {
      byte[] probe =
          ("{\"timestamp\":\""
                  + Instant.now()
                  + "\",\"level\":\"info\",\"msg\":\"probe "
                  + n
                  + " "
                  + UUID.randomUUID()
                  + "\"}\n")
              .getBytes(StandardCharsets.UTF_8);
      Client.Response r = client.push("probe", PushLoad.gzip(probe), "probe-" + n);
      if (r.status() == 202) {
        sentAt.put(n, System.nanoTime());
      }
      Thread.sleep(1000);
    }
    stop.set(true);
    probing.set(false);
    for (Future<?> f : clients) {
      f.get(2, TimeUnit.MINUTES);
    }
    poller.get(5, TimeUnit.MINUTES);
    pool.shutdown();
    Map<String, String> heap = server.stop();

    long[] latencies =
        sentAt.entrySet().stream()
            .filter(e -> foundAt.containsKey(e.getKey()))
            .mapToLong(e -> TimeUnit.NANOSECONDS.toMillis(foundAt.get(e.getKey()) - e.getValue()))
            .toArray();
    double seconds = SECONDS;
    System.out.printf(
        "Hot tier freshness (T5): engine=%s cpus=%s rate=%d seconds=%d pushedLines=%d"
            + " linesPerSecond=%.0f probes=%d found=%d p50ms=%d p99ms=%d maxms=%d peakHeapMB=%s"
            + " maxHeapMB=%s%n",
        FLAVOR,
        PushLoad.inDocker() ? PushLoad.DOCKER_CPUS : "host",
        RATE,
        SECONDS,
        acked.sum(),
        acked.sum() / seconds,
        sentAt.size(),
        latencies.length,
        PushLoad.percentile(latencies.clone(), 50),
        PushLoad.percentile(latencies.clone(), 99),
        PushLoad.percentile(latencies.clone(), 100),
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertEquals(sentAt.size(), latencies.length, "every probe was found");
    assertTrue(PushLoad.percentile(latencies, 99) <= 30_000, "T5: p99 ≤ 30s");
  }

  /**
   * The engine paused under push load: ingest and archiving go on (ADR-7), the WAL holds the lines,
   * and afterwards the index holds exactly the acknowledged lines.
   */
  @Test
  void aHotTierOutageLosesAndDuplicatesNothing() throws Exception {
    EngineContainers.assumeDocker();
    int port = PushLoad.freePort();
    String prefix = prefix();
    Server server =
        Server.start(
            work,
            HEAP,
            args(
                port,
                prefix,
                "--argus.archive.store=filesystem",
                "--argus.archive.filesystem.root=" + work.resolve("archive"),
                "--argus.archive.scan-interval=1s",
                "--argus.hot.request-timeout=5s",
                "--argus.hot.retry-max=5s",
                "--argus.ingest.segment-bytes=4MB"));
    Client client = new Client(port, KEY);
    SearchEngineClient engine = EngineContainers.client(FLAVOR);
    AtomicBoolean stop = new AtomicBoolean();
    LongAdder acked = new LongAdder();
    ExecutorService pool = Executors.newFixedThreadPool(STREAMS);
    int rate = Math.min(RATE, 2_000);
    List<Future<?>> clients = load(pool, client, rate, stop, acked);
    String id = EngineContainers.containerId(FLAVOR);
    var docker = DockerClientFactory.instance().client();

    Thread.sleep(20_000);
    long archivedBefore = server.countLogLines("outcome=ARCHIVED");
    long ackedBefore = acked.sum();
    docker.pauseContainerCmd(id).exec();
    long unpausedAt;
    long archivedDuring;
    long ackedDuring;
    try {
      Thread.sleep(TimeUnit.SECONDS.toMillis(OUTAGE_SECONDS));
      archivedDuring = server.countLogLines("outcome=ARCHIVED") - archivedBefore;
      ackedDuring = acked.sum() - ackedBefore;
    } finally {
      docker.unpauseContainerCmd(id).exec();
      unpausedAt = System.nanoTime();
    }
    Thread.sleep(20_000);
    stop.set(true);
    for (Future<?> f : clients) {
      f.get(2, TimeUnit.MINUTES);
    }
    pool.shutdown();
    client.awaitIngested(Duration.ofMinutes(5));
    long indexed = awaitCount(engine, prefix, acked.sum(), Duration.ofMinutes(10));
    double catchUpSeconds = (System.nanoTime() - unpausedAt) / 1e9;
    Thread.sleep(3_000);
    long settled = count(engine, prefix);
    Map<String, String> heap = server.stop();

    System.out.printf(
        "Hot tier outage: engine=%s cpus=%s rate=%d outageSeconds=%d linesAcked=%d"
            + " ackedDuringOutage=%d segmentsArchivedDuringOutage=%d indexed=%d"
            + " caughtUpSecondsAfterOutage=%.1f peakHeapMB=%s maxHeapMB=%s%n",
        FLAVOR,
        PushLoad.inDocker() ? PushLoad.DOCKER_CPUS : "host",
        rate,
        OUTAGE_SECONDS,
        acked.sum(),
        ackedDuring,
        archivedDuring,
        settled,
        catchUpSeconds,
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertTrue(ackedDuring > 0, "push went on during the outage");
    assertTrue(archivedDuring > 0, "archiving went on during the outage");
    assertEquals(acked.sum(), indexed);
    assertEquals(acked.sum(), settled, "no duplicates arrived later");
  }

  /** {@code kill -9} while indexing: after a restart the index holds each line exactly once. */
  @Test
  void aKillWhileIndexingDuplicatesNothing() throws Exception {
    EngineContainers.assumeDocker();
    // A restarted container would get a new data volume; the WAL must survive the kill.
    Assumptions.assumeFalse(PushLoad.inDocker(), "runs on the host only");
    int port = PushLoad.freePort();
    String prefix = prefix();
    List<String> args = args(port, prefix, "--argus.ingest.segment-bytes=2MB");
    Server server = Server.start(work, HEAP, args);
    Client client = new Client(port, KEY);
    SearchEngineClient engine = EngineContainers.client(FLAVOR);
    int batches = 400;
    LongAdder acked = new LongAdder();
    ExecutorService pool = Executors.newFixedThreadPool(STREAMS);
    List<Future<?>> clients = new ArrayList<>();
    for (int s = 0; s < STREAMS; s++) {
      String stream = "s" + s;
      clients.add(
          pool.submit(
              () -> {
                for (int b = 0; b < batches; b++) {
                  pushUntilAccepted(
                      client,
                      stream,
                      PushLoad.gzip(batch(stream, b, BATCH_LINES)),
                      stream + "-" + b);
                  acked.add(BATCH_LINES);
                }
                return null;
              }));
    }
    long expected = (long) STREAMS * batches * BATCH_LINES;
    while (count(engine, prefix) < expected / 4) {
      Thread.sleep(200);
    }
    long indexedAtKill = count(engine, prefix);
    server.kill();
    Server restarted = Server.start(work, HEAP, args);
    for (Future<?> f : clients) {
      f.get(10, TimeUnit.MINUTES);
    }
    pool.shutdown();
    client.awaitIngested(Duration.ofMinutes(5));
    long indexed = awaitCount(engine, prefix, expected, Duration.ofMinutes(5));
    Thread.sleep(3_000);
    long settled = count(engine, prefix);
    restarted.stop();

    System.out.printf(
        "Hot tier crash: engine=%s lines=%d indexedAtKill=%d indexed=%d%n",
        FLAVOR, expected, indexedAtKill, settled);
    assertEquals(expected, indexed);
    assertEquals(expected, settled, "a re-indexed line overwrote itself");
  }

  /**
   * The mapping's disk saving (D5): the same lines with the engine's default dynamic mapping and
   * with the hot tier's, store size after a force-merge. The design's "~35%" is measured here.
   */
  @Test
  void measuresTheMappingsDiskSaving() throws Exception {
    EngineContainers.assumeDocker();
    SearchEngineClient engine = EngineContainers.client(FLAVOR);
    String prefix = prefix();
    HotProperties props =
        new HotProperties(
            FLAVOR == Flavor.OPENSEARCH
                ? HotProperties.Store.OPENSEARCH
                : HotProperties.Store.ELASTICSEARCH,
            URI.create(EngineContainers.url(FLAVOR)),
            null,
            null,
            null,
            prefix,
            null,
            null,
            1,
            0,
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null);
    HotIndices indices = new HotIndices(props);
    new HotTemplates(engine, indices, props).install();
    String tuned = indices.index("acme", java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    String dynamic = "dyn" + prefix;
    assertTrue(
        engine
            .json(
                "PUT",
                "/" + dynamic,
                JSON.readTree("{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0}}"))
            .ok());
    int lines = Integer.getInteger("argus.load.mapping-lines", 200_000);
    ParserRegistry parsers = new ParserRegistry();
    ParserChain chain = parsers.chain(parsers.resolve("json", null).orElseThrow(), true);
    EnvelopeFactory envelopes = new EnvelopeFactory("acme", "app", 0);
    Instant now = Instant.now();
    StringBuilder tunedBulk = new StringBuilder();
    StringBuilder dynamicBulk = new StringBuilder();
    long offset = 0;
    for (int b = 0; b * 1000 < lines; b++) {
      for (String line : new String(batch("s0", b, 1000), StandardCharsets.UTF_8).split("\n")) {
        LogEnvelope e = envelopes.create(chain.parse(line), offset, now);
        offset += line.length() + 1;
        tunedBulk
            .append("{\"index\":{\"_index\":\"")
            .append(tuned)
            .append("\",\"_id\":\"")
            .append(e.eventId())
            .append("\"}}\n")
            .append(HotDocument.of(e, now, engine.mapper()))
            .append('\n');
        ObjectNode plain = engine.mapper().valueToTree(e);
        dynamicBulk
            .append("{\"index\":{\"_index\":\"")
            .append(dynamic)
            .append("\",\"_id\":\"")
            .append(e.eventId())
            .append("\"}}\n")
            .append(plain)
            .append('\n');
      }
      send(engine, tunedBulk);
      send(engine, dynamicBulk);
    }
    long tunedBytes = storeBytes(engine, tuned);
    long dynamicBytes = storeBytes(engine, dynamic);
    System.out.printf(
        "Hot tier mapping (D5): engine=%s lines=%d defaultMappingBytes=%d hotMappingBytes=%d"
            + " savingPercent=%.1f%n",
        FLAVOR,
        lines,
        dynamicBytes,
        tunedBytes,
        100.0 * (dynamicBytes - tunedBytes) / dynamicBytes);
    assertTrue(tunedBytes > 0 && dynamicBytes > 0);
  }

  private static void send(SearchEngineClient engine, StringBuilder bulk) throws Exception {
    SearchEngineClient.Response r =
        engine.send(
            "POST",
            "/_bulk",
            bulk.toString().getBytes(StandardCharsets.UTF_8),
            SearchEngineClient.NDJSON);
    assertTrue(
        r.ok() && !engine.mapper().readTree(r.body()).get("errors").asBoolean(), r.excerpt(500));
    bulk.setLength(0);
  }

  private static long storeBytes(SearchEngineClient engine, String index) throws Exception {
    engine.send("POST", "/" + index + "/_refresh", null, null);
    engine.send("POST", "/" + index + "/_forcemerge?max_num_segments=1", null, null);
    engine.send("POST", "/" + index + "/_refresh", null, null);
    SearchEngineClient.Response r =
        engine.get(
            "/_cat/indices/" + index + "?format=json&bytes=b&h=index,pri.store.size,docs.count");
    JsonNode row = engine.mapper().readTree(r.body()).get(0);
    return row.get("pri.store.size").asLong();
  }
}
