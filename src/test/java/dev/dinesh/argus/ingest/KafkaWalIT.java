package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import dev.dinesh.argus.kafka.KafkaContainers;
import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/**
 * Kafka as the WAL under load ({@code P6_PLAN.md} §5, P6a): the application in its own 256MB JVM,
 * pushed to over HTTP, writing to an Apache Kafka 3.8 container ({@code
 * -Dargus.load.kafka-broker=redpanda} for Redpanda 24.2) and archiving to a filesystem store. Host
 * only: the broker advertises a host port a containerised server couldn't reach. T5 on Kafka is
 * {@code HotTierIT} with {@code -Dargus.load.wal=kafka}.
 */
@Tag("load")
class KafkaWalIT {

  private static final String HEAP = "-Xmx256m";
  private static final String KEY = "load-key";
  static final Broker BROKER =
      "redpanda".equalsIgnoreCase(System.getProperty("argus.load.kafka-broker"))
          ? Broker.REDPANDA
          : Broker.KAFKA;
  private static final long LINES = Long.getLong("argus.load.kafka-lines", 1_000_000);
  private static final int OUTAGE_SECONDS = Integer.getInteger("argus.load.outage-seconds", 60);
  private static final int CLIENTS = 8;
  private static final int BATCH_LINES = 1000;

  @TempDir Path work;

  @BeforeEach
  void hostOnly() {
    KafkaContainers.assumeDocker();
    Assumptions.assumeFalse(PushLoad.inDocker(), "runs on the host only");
  }

  /** The server arguments for {@code argus.wal.store=kafka} on a fresh topic. */
  static List<String> kafkaArgs(Path work) {
    return List.of(
        "--argus.wal.store=kafka",
        "--argus.kafka.bootstrap-servers=" + KafkaContainers.bootstrap(BROKER),
        "--argus.kafka.topic=" + KafkaContainers.topic(),
        "--argus.kafka.create-topics=true",
        "--argus.kafka.replication-factor=1",
        "--argus.kafka.partitions=6",
        "--argus.kafka.staging-dir=" + work.resolve("kafka-staging"),
        "--argus.kafka.retry-initial=100ms",
        "--argus.kafka.retry-max=1s");
  }

  private List<String> args(int port, String... extra) {
    return PushLoad.serverArgs(work, port, Map.of("load", KEY), extra);
  }

  /**
   * With a filesystem archive; each argument may appear once (Spring joins repeats with commas).
   */
  private List<String> withArchive(List<String> args, String segmentBytes) {
    List<String> all = new ArrayList<>(args);
    all.addAll(
        List.of(
            "--argus.archive.store=filesystem",
            "--argus.archive.filesystem.root=" + work.resolve("archive"),
            "--argus.archive.staging-dir=" + work.resolve("parquet"),
            "--argus.ingest.segment-bytes=" + segmentBytes));
    return all;
  }

  /** {@code clients} keyed pushers at about {@code rate} lines/s in total, until {@code stop}. */
  private static List<Future<?>> pushAtRate(
      ExecutorService pool,
      Client client,
      int clients,
      int rate,
      AtomicBoolean stop,
      LongAdder acked,
      Map<String, LongAdder> rejected) {
    int lines = 100;
    long intervalNanos = TimeUnit.SECONDS.toNanos(1) * lines * clients / rate;
    List<Future<?>> futures = new ArrayList<>();
    for (int c = 0; c < clients; c++) {
      int stream = c;
      futures.add(
          pool.submit(
              () -> {
                long next = System.nanoTime();
                for (int b = 0; !stop.get(); b++) {
                  byte[] body = PushLoad.gzip(PushLoad.batch(stream, b, lines));
                  push(client, "s" + stream, body, "s" + stream + "-b" + b, rejected);
                  acked.add(lines);
                  next += intervalNanos;
                  long wait = next - System.nanoTime();
                  if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                  }
                }
                return null;
              }));
    }
    return futures;
  }

  /** Pushes {@code batches} keyed batches per client as fast as accepted; returns lines acked. */
  private static long pushAll(
      Client client, int clients, int batches, Map<String, LongAdder> rejected) throws Exception {
    LongAdder acked = new LongAdder();
    ExecutorService pool = Executors.newFixedThreadPool(clients);
    List<Future<?>> futures = new ArrayList<>();
    for (int c = 0; c < clients; c++) {
      int stream = c;
      futures.add(
          pool.submit(
              () -> {
                for (int b = 0; b < batches; b++) {
                  byte[] body = PushLoad.gzip(PushLoad.batch(stream, b, BATCH_LINES));
                  push(client, "s" + stream, body, "s" + stream + "-b" + b, rejected);
                  acked.add(BATCH_LINES);
                }
                return null;
              }));
    }
    for (Future<?> f : futures) {
      f.get(30, TimeUnit.MINUTES);
    }
    pool.shutdown();
    return acked.sum();
  }

  /** With its key until accepted, through 429s (counted by reason) and restarts. */
  private static void push(
      Client client, String stream, byte[] body, String key, Map<String, LongAdder> rejected)
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
        String reason = PushLoad.JSON.readTree(r.body()).path("reason").asText();
        rejected.computeIfAbsent(reason, k -> new LongAdder()).increment();
        Thread.sleep(Math.min(500, Math.max(100, r.retryAfterSeconds() * 1000)));
      } catch (java.io.IOException e) {
        Thread.sleep(200);
      }
    }
  }

  private static long rows(Path archive) throws Exception {
    return Files.isDirectory(archive) ? WalBackpressureRunner.parquetRows(archive) : 0;
  }

  private static long awaitRows(Path archive, long expected, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    long rows;
    while ((rows = rows(archive)) < expected && System.nanoTime() < deadline) {
      Thread.sleep(1000);
    }
    return rows;
  }

  /** T1: push throughput to the topic, beside the local WAL's under the same load. */
  @Test
  void pushThroughputToKafkaAndToTheLocalWal() throws Exception {
    int batches = (int) (LINES / CLIENTS / BATCH_LINES);
    long lines = (long) batches * CLIENTS * BATCH_LINES;
    Map<String, Double> rates = new java.util.LinkedHashMap<>();
    Map<String, String> heaps = new java.util.LinkedHashMap<>();
    for (String store : List.of("local", "kafka")) {
      Path dir = Files.createDirectories(work.resolve(store));
      int port = PushLoad.freePort();
      List<String> args = PushLoad.serverArgs(dir, port, Map.of("load", KEY));
      if (store.equals("kafka")) {
        args = new ArrayList<>(args);
        args.addAll(kafkaArgs(dir));
      }
      Server server = Server.start(dir, HEAP, args);
      Client client = new Client(port, KEY);
      long start = System.nanoTime();
      long acked = pushAll(client, CLIENTS, batches, new ConcurrentHashMap<>());
      client.awaitIngested(Duration.ofMinutes(10));
      double seconds = (System.nanoTime() - start) / 1e9;
      Map<String, String> heap = server.stop();
      assertEquals(lines, acked);
      rates.put(store, lines / seconds);
      heaps.put(store, heap.get("peakHeapMB"));
    }
    System.out.printf(
        "Kafka WAL throughput (T1): broker=%s lines=%d localLinesPerSecond=%.0f"
            + " kafkaLinesPerSecond=%.0f localPeakHeapMB=%s kafkaPeakHeapMB=%s maxHeapMB=256%n",
        BROKER,
        lines,
        rates.get("local"),
        rates.get("kafka"),
        heaps.get("local"),
        heaps.get("kafka"));
    assertTrue(rates.get("kafka") >= 4_150, "T1: at least the target peak");
  }

  /**
   * T3, broker down: pushes land in their spools while the stream runs block on the producer; after
   * the broker returns everything is archived exactly once.
   */
  @Test
  void aBrokerOutageIsBackpressureAndLosesNothing() throws Exception {
    int port = PushLoad.freePort();
    List<String> args = withArchive(args(port), "4MB");
    args.addAll(kafkaArgs(work));
    Server server = Server.start(work, HEAP, args);
    Client client = new Client(port, KEY);
    Path archive = work.resolve("archive");
    AtomicBoolean stop = new AtomicBoolean();
    LongAdder acked = new LongAdder();
    Map<String, LongAdder> rejected = new ConcurrentHashMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Future<?>> clients = pushAtRate(pool, client, 4, 2_000, stop, acked, rejected);
    String id = KafkaContainers.containerId(BROKER);
    var docker = DockerClientFactory.instance().client();
    Thread.sleep(15_000);
    long archivedBefore = rows(archive);
    long ackedBefore = acked.sum();
    docker.pauseContainerCmd(id).exec();
    long ackedDuring;
    long archivedDuring;
    long resumedAt;
    try {
      Thread.sleep(TimeUnit.SECONDS.toMillis(OUTAGE_SECONDS));
      ackedDuring = acked.sum() - ackedBefore;
      archivedDuring = rows(archive) - archivedBefore;
    } finally {
      docker.unpauseContainerCmd(id).exec();
      resumedAt = System.nanoTime();
    }
    Thread.sleep(15_000);
    stop.set(true);
    for (Future<?> f : clients) {
      f.get(5, TimeUnit.MINUTES);
    }
    pool.shutdown();
    client.awaitIngested(Duration.ofMinutes(10));
    long archived = awaitRows(archive, acked.sum(), Duration.ofMinutes(10));
    double caughtUp = (System.nanoTime() - resumedAt) / 1e9;
    Thread.sleep(3_000);
    long settled = rows(archive);
    Map<String, String> heap = server.stop();

    System.out.printf(
        "Kafka broker outage (T3): broker=%s outageSeconds=%d linesAcked=%d ackedDuringOutage=%d"
            + " archivedDuringOutage=%d rejected429=%s archived=%d caughtUpSecondsAfterOutage=%.1f"
            + " peakHeapMB=%s maxHeapMB=%s%n",
        BROKER,
        OUTAGE_SECONDS,
        acked.sum(),
        ackedDuring,
        archivedDuring,
        rejected,
        settled,
        caughtUp,
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertTrue(ackedDuring > 0, "pushes were accepted into the spools during the outage");
    assertEquals(acked.sum(), archived);
    assertEquals(acked.sum(), settled, "no line archived twice");
  }

  /**
   * T3, sink down: the archive store refuses writes, the archive group's lag passes the high
   * watermark (40,000 of 50,000 records), pushes get 429 {@code WAL_FULL}; afterwards everything is
   * archived exactly once.
   */
  @Test
  void aSinkOutagePausesIngestAtTheLagWatermark() throws Exception {
    Path archive = Files.createDirectories(work.resolve("archive"));
    int port = PushLoad.freePort();
    List<String> args = withArchive(args(port, "--argus.kafka.max-lag-records=50000"), "4MB");
    args.addAll(kafkaArgs(work));
    Server server = Server.start(work, HEAP, args);
    Client client = new Client(port, KEY);
    Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("r-xr-xr-x"));
    AtomicBoolean stop = new AtomicBoolean();
    LongAdder acked = new LongAdder();
    Map<String, LongAdder> rejected = new ConcurrentHashMap<>();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Future<?>> clients = pushAtRate(pool, client, 4, 4_000, stop, acked, rejected);
    int outage = Math.max(30, OUTAGE_SECONDS / 2);
    Thread.sleep(TimeUnit.SECONDS.toMillis(outage));
    long ackedDuring = acked.sum();
    Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rwxr-xr-x"));
    long recovered = System.nanoTime();
    Thread.sleep(15_000);
    stop.set(true);
    for (Future<?> f : clients) {
      f.get(5, TimeUnit.MINUTES);
    }
    pool.shutdown();
    client.awaitIngested(Duration.ofMinutes(10));
    long archived = awaitRows(archive, acked.sum(), Duration.ofMinutes(10));
    double drained = (System.nanoTime() - recovered) / 1e9;
    Thread.sleep(3_000);
    long settled = rows(archive);
    Map<String, String> heap = server.stop();

    System.out.printf(
        "Kafka sink outage (T3): broker=%s outageSeconds=%d linesAcked=%d ackedDuringOutage=%d"
            + " rejected429=%s archived=%d drainedSecondsAfterOutage=%.1f peakHeapMB=%s"
            + " maxHeapMB=%s%n",
        BROKER,
        outage,
        acked.sum(),
        ackedDuring,
        rejected,
        settled,
        drained,
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertTrue(
        rejected.containsKey("WAL_FULL"), "the lag watermark reached the clients: " + rejected);
    assertTrue(ackedDuring < 4_000L * outage * 3 / 4, "ingest paused during the outage");
    assertEquals(acked.sum(), archived);
    assertEquals(acked.sum(), settled, "every acknowledged line archived exactly once");
  }

  /** T4: {@code kill -9} mid-push; after a restart every line is archived exactly once. */
  @Test
  void aKillMidSegmentLosesAndDuplicatesNothing() throws Exception {
    int port = PushLoad.freePort();
    List<String> args = withArchive(args(port), "2MB");
    args.addAll(kafkaArgs(work));
    Server server = Server.start(work, HEAP, args);
    Client client = new Client(port, KEY);
    Path archive = work.resolve("archive");
    int batches = 100; // 4 × 100 × 1,000 = 400,000 lines
    ExecutorService pusher = Executors.newSingleThreadExecutor();
    Future<Long> pushed =
        pusher.submit(() -> pushAll(client, 4, batches, new ConcurrentHashMap<>()));
    long expected = 4L * batches * BATCH_LINES;
    while (rows(archive) < expected / 4) {
      Thread.sleep(200);
    }
    long archivedAtKill = rows(archive);
    server.kill();
    Server restarted = Server.start(work, HEAP, args);
    long acked = pushed.get(30, TimeUnit.MINUTES);
    pusher.shutdown();
    client.awaitIngested(Duration.ofMinutes(10));
    long archived = awaitRows(archive, expected, Duration.ofMinutes(10));
    Thread.sleep(3_000);
    long settled = rows(archive);
    restarted.stop();

    System.out.printf(
        "Kafka WAL crash (T4): broker=%s lines=%d archivedAtKill=%d archived=%d%n",
        BROKER, expected, archivedAtKill, settled);
    assertEquals(expected, acked);
    assertEquals(expected, archived);
    assertEquals(expected, settled, "a replayed segment replaced its earlier copy");
  }
}
