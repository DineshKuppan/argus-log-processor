package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.engine.EngineContainers;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Flavor;
import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import dev.dinesh.argus.kafka.KafkaContainers;
import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Several instances ({@code P6_PLAN.md} §5, P6b): three server JVMs (256MB each) with their own
 * volumes and {@code argus.instance.id}, one Kafka topic and tail topic, one archive store and one
 * OpenSearch cluster. Clients push each stream to one instance, as the ingress's hash would. Host
 * only, like {@code KafkaWalIT}; {@code -Dargus.load.kafka-broker=redpanda} for Redpanda.
 */
@Tag("load")
class MultiInstanceIT {

  private static final String HEAP = "-Xmx256m";
  private static final String KEY = "load-key";
  private static final int INSTANCES = 3;
  private static final int CLIENTS = 6;
  private static final int BATCH_LINES = 1000;
  private static final int BATCHES = Integer.getInteger("argus.load.multi-batches", 50);
  private static final Broker BROKER = KafkaWalIT.BROKER;

  @TempDir Path work;

  private final String topic = KafkaContainers.topic();
  private final String tailTopic = "tail-" + KafkaContainers.topic();
  private final String prefix = "multi" + UUID.randomUUID().toString().substring(0, 8);
  private final int[] ports = new int[INSTANCES];
  private final Server[] servers = new Server[INSTANCES];

  @BeforeEach
  void hostOnly() throws IOException {
    KafkaContainers.assumeDocker();
    Assumptions.assumeFalse(PushLoad.inDocker(), "runs on the host only");
    for (int i = 0; i < INSTANCES; i++) {
      ports[i] = PushLoad.freePort();
    }
  }

  @AfterEach
  void stopAll() {
    for (Server s : servers) {
      if (s != null) {
        try {
          s.stop();
        } catch (Exception e) {
          // already gone
        }
      }
    }
  }

  /** Instance {@code i}'s arguments: its own volume and id, the shared topic, store and engine. */
  private List<String> args(int i, boolean sinks) {
    Path dir = work.resolve("argus-" + i);
    List<String> args =
        new ArrayList<>(
            PushLoad.serverArgs(
                dir,
                ports[i],
                Map.of("load", KEY),
                "--argus.instance.id=argus-" + i,
                "--argus.wal.store=kafka",
                "--argus.kafka.bootstrap-servers=" + KafkaContainers.bootstrap(BROKER),
                "--argus.kafka.topic=" + topic,
                "--argus.kafka.tail-topic=" + tailTopic,
                "--argus.kafka.create-topics=true",
                "--argus.kafka.replication-factor=1",
                "--argus.kafka.partitions=6",
                "--argus.kafka.staging-dir=" + dir.resolve("kafka-staging"),
                "--argus.kafka.retry-initial=100ms",
                "--argus.kafka.retry-max=1s"));
    if (sinks) {
      args.addAll(
          List.of(
              "--argus.ingest.segment-bytes=2MB",
              "--argus.archive.store=filesystem",
              "--argus.archive.filesystem.root=" + work.resolve("archive"),
              "--argus.archive.staging-dir=" + dir.resolve("parquet"),
              "--argus.hot.store=opensearch",
              "--argus.hot.url=" + EngineContainers.url(Flavor.OPENSEARCH),
              "--argus.hot.index-prefix=" + prefix,
              "--argus.hot.replicas=0"));
    }
    return args;
  }

  private Server start(int i, boolean sinks) throws Exception {
    Path dir = Files.createDirectories(work.resolve("argus-" + i));
    servers[i] = Server.start(dir, HEAP, args(i, sinks));
    return servers[i];
  }

  /** Keyed pushes of {@link #BATCHES} batches per client, each stream to one instance. */
  private Future<Long> pushAll(ExecutorService pool) {
    return pool.submit(
        () -> {
          ExecutorService clients = Executors.newFixedThreadPool(CLIENTS);
          LongAdder acked = new LongAdder();
          List<Future<?>> futures = new ArrayList<>();
          for (int c = 0; c < CLIENTS; c++) {
            int stream = c;
            Client client = new Client(ports[c % INSTANCES], KEY);
            futures.add(
                clients.submit(
                    () -> {
                      for (int b = 0; b < BATCHES; b++) {
                        byte[] body = PushLoad.gzip(HotTierIT.batch("s" + stream, b, BATCH_LINES));
                        push(client, "s" + stream, body, "s" + stream + "-b" + b);
                        acked.add(BATCH_LINES);
                      }
                      return null;
                    }));
          }
          for (Future<?> f : futures) {
            f.get(30, TimeUnit.MINUTES);
          }
          clients.shutdown();
          return acked.sum();
        });
  }

  /** With its key until accepted: through 429s, a killed instance and its restart. */
  private static void push(Client client, String stream, byte[] body, String key)
      throws InterruptedException {
    while (true) {
      try {
        Client.Response r = client.push(stream, body, key);
        if (r.status() == 202 || r.status() == 200) {
          return;
        }
        if (r.status() != 429 && r.status() != 503) {
          throw new IllegalStateException("unexpected " + r.status() + ": " + r.body());
        }
        Thread.sleep(Math.min(500, Math.max(100, r.retryAfterSeconds() * 1000)));
      } catch (IOException e) {
        Thread.sleep(200);
      }
    }
  }

  private long rows() throws Exception {
    Path archive = work.resolve("archive");
    return Files.isDirectory(archive) ? WalBackpressureRunner.parquetRows(archive) : 0;
  }

  private static long count(SearchEngineClient engine, String prefix) throws Exception {
    engine.send("POST", "/" + prefix + "-*/_refresh", null, null);
    SearchEngineClient.Response r =
        engine.send("GET", "/" + prefix + "-*/_count?ignore_unavailable=true", null, null);
    return r.ok() ? engine.mapper().readTree(r.body()).get("count").asLong() : -1;
  }

  /**
   * D9: {@code kill -9} of one of three instances under load. Its partitions move to the others,
   * which re-read from the committed offsets; it restarts with its id and volume (as a StatefulSet
   * pod does) and ingests its spools. Both tiers end with every acknowledged line exactly once.
   */
  @Test
  void aKilledInstanceLosesAndDuplicatesNothingInEitherTier() throws Exception {
    EngineContainers.assumeDocker();
    SearchEngineClient engine = EngineContainers.client(Flavor.OPENSEARCH);
    for (int i = 0; i < INSTANCES; i++) {
      start(i, true);
    }
    long expected = (long) CLIENTS * BATCHES * BATCH_LINES;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<Long> pushed = pushAll(pool);
    while (rows() < expected / 4) {
      Thread.sleep(200);
    }
    long archivedAtKill = rows();
    long indexedAtKill = count(engine, prefix);
    servers[1].kill();
    Thread.sleep(10_000); // past its pushes' retries, while its partitions move
    start(1, true);
    long acked = pushed.get(30, TimeUnit.MINUTES);
    pool.shutdown();
    for (int i = 0; i < INSTANCES; i++) {
      new Client(ports[i], KEY).awaitIngested(Duration.ofMinutes(10));
    }
    long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
    while ((rows() < expected || count(engine, prefix) < expected)
        && System.nanoTime() < deadline) {
      Thread.sleep(1000);
    }
    Thread.sleep(5_000);
    long archived = rows();
    long indexed = count(engine, prefix);

    System.out.printf(
        "Multi-instance kill (D9): broker=%s instances=%d lines=%d archivedAtKill=%d"
            + " indexedAtKill=%d archived=%d indexed=%d%n",
        BROKER, INSTANCES, expected, archivedAtKill, indexedAtKill, archived, indexed);
    assertEquals(expected, acked);
    assertEquals(expected, archived, "archive: every line exactly once");
    assertEquals(expected, indexed, "hot tier: every line exactly once");
  }

  /** A tail read on its own thread, timing the lines containing "mark-". */
  private static final class Tail implements AutoCloseable {
    final Socket socket = new Socket();
    final Map<String, Long> arrivals = new ConcurrentHashMap<>();
    final CountDownLatch connected = new CountDownLatch(1);

    Tail(int port) throws IOException {
      socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET /api/v2/tail HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Argus-Api-Key: "
                  + KEY
                  + "\r\nAccept: text/event-stream\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      Thread reader = new Thread(this::read, "tail-client");
      reader.setDaemon(true);
      reader.start();
    }

    private void read() {
      try (BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line = in.readLine(); line != null; line = in.readLine()) {
          long now = System.nanoTime();
          if (line.startsWith(": connected")) {
            connected.countDown();
          }
          int mark = line.indexOf("mark-");
          if (mark >= 0) {
            int end = mark + 5;
            while (end < line.length() && Character.isLetterOrDigit(line.charAt(end))) {
              end++;
            }
            arrivals.putIfAbsent(line.substring(mark, end), now);
          }
        }
      } catch (IOException e) {
        // closed
      }
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  /** D10 / T7 across instances: a line pushed to one instance reaches a tail on another. */
  @Test
  void aLinePushedToOneInstanceReachesATailOnAnotherWithinTwoSeconds() throws Exception {
    start(0, false);
    start(1, false);
    Client client = new Client(ports[0], KEY);
    List<Long> latencies = new ArrayList<>();
    try (Tail tail = new Tail(ports[1])) {
      assertTrue(tail.connected.await(10, TimeUnit.SECONDS), "tail connected");
      Thread.sleep(6_000); // a watch heartbeat reaches instance 0, which then taps the tenant
      for (int i = 0; i < 100; i++) {
        String mark = "mark-" + i + "x" + UUID.randomUUID().toString().substring(0, 6);
        byte[] line =
            ("{\"level\":\"info\",\"msg\":\"" + mark + " order placed\"}\n")
                .getBytes(StandardCharsets.UTF_8);
        long sent = System.nanoTime();
        assertEquals(202, client.push("app", PushLoad.gzip(line), null).status());
        long deadline = sent + TimeUnit.SECONDS.toNanos(10);
        while (!tail.arrivals.containsKey(mark) && System.nanoTime() < deadline) {
          Thread.sleep(1);
        }
        Long arrived = tail.arrivals.get(mark);
        latencies.add(arrived == null ? TimeUnit.SECONDS.toNanos(10) : arrived - sent);
        Thread.sleep(50);
      }
    }
    long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
    long p50 = PushLoad.percentile(sorted, 50);
    long p99 = PushLoad.percentile(sorted, 99);
    System.out.printf(
        "Multi-instance tail (D10, T7): broker=%s pushes=%d pushToOtherInstanceTailP50ms=%.1f"
            + " p99ms=%.1f maxms=%.1f%n",
        BROKER, sorted.length, p50 / 1e6, p99 / 1e6, sorted[sorted.length - 1] / 1e6);
    assertTrue(p99 <= TimeUnit.SECONDS.toNanos(2), "T7 across instances: p99 " + p99 / 1e6 + "ms");
  }
}
