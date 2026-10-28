package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T1 through the push path: concurrent clients post gzip NDJSON to an application whose heap is
 * 256MB, then everything is ingested. Also tenant isolation: one tenant flooding past its quota
 * doesn't get another a 429 or a much slower response. Run with {@code mvn test -Dgroups=load
 * -DexcludedGroups=none -Dtest=PushIngestIT}; {@code -Dargus.load.push-lines} sets the volume
 * (default 2M lines, about 420MB).
 */
@Tag("load")
class PushIngestIT {

  private static final String HEAP = "-Xmx256m";
  private static final long LINES = Long.getLong("argus.load.push-lines", 2_000_000);
  private static final int CLIENTS = 8;
  private static final int BATCH_LINES = 1000;
  private static final String KEY = "load-key";

  @TempDir Path work;

  @Test
  void concurrentClientsPushAtTheTargetRateWithinA256MbHeap() throws Exception {
    int port = PushLoad.freePort();
    Server server = Server.start(work, HEAP, PushLoad.serverArgs(work, port, Map.of("load", KEY)));
    Client client = new Client(port, KEY);
    int batchesPerClient = (int) (LINES / CLIENTS / BATCH_LINES);
    List<Long> latencies = new ArrayList<>();
    LongAdder accepted = new LongAdder();
    LongAdder rejected = new LongAdder();

    long started = System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
    List<Future<long[]>> results = new ArrayList<>();
    for (int c = 0; c < CLIENTS; c++) {
      int stream = c;
      results.add(
          pool.submit(
              () -> {
                long[] own = new long[batchesPerClient];
                for (int b = 0; b < batchesPerClient; b++) {
                  byte[] body = PushLoad.gzip(PushLoad.batch(stream, b, BATCH_LINES));
                  while (true) {
                    long t0 = System.nanoTime();
                    Client.Response r = client.push("s" + stream, body, "s" + stream + "-b" + b);
                    long micros = (System.nanoTime() - t0) / 1000;
                    if (r.status() == 202 || r.status() == 200) {
                      own[b] = micros;
                      accepted.add(BATCH_LINES);
                      break;
                    }
                    assertEquals(429, r.status(), r.body());
                    rejected.increment();
                    Thread.sleep(Math.min(1000, r.retryAfterSeconds() * 1000));
                  }
                }
                return own;
              }));
    }
    for (Future<long[]> f : results) {
      for (long micros : f.get(20, TimeUnit.MINUTES)) {
        latencies.add(micros);
      }
    }
    double pushSeconds = (System.nanoTime() - started) / 1e9;
    pool.shutdown();
    client.awaitIngested(Duration.ofMinutes(10));
    double totalSeconds = (System.nanoTime() - started) / 1e9;
    Map<String, String> heap = server.stop();

    AtomicLong outputLines = new AtomicLong();
    PushLoad.forEachOutputRecord(
        work.resolve("out"), (file, record) -> outputLines.incrementAndGet());
    long[] micros = latencies.stream().mapToLong(Long::longValue).toArray();
    long total = (long) batchesPerClient * CLIENTS * BATCH_LINES;
    System.out.printf(
        "Push ingest (T1): lines=%d clients=%d batchLines=%d pushSeconds=%.1f pushLinesPerSecond=%.0f"
            + " endToEndSeconds=%.1f endToEndLinesPerSecond=%.0f requestP50ms=%.1f requestP99ms=%.1f"
            + " requestMaxMs=%.1f rejected429=%d outputLines=%d peakHeapMB=%s maxHeapMB=%s%n",
        total,
        CLIENTS,
        BATCH_LINES,
        pushSeconds,
        total / pushSeconds,
        totalSeconds,
        total / totalSeconds,
        PushLoad.percentile(micros, 50) / 1000.0,
        PushLoad.percentile(micros, 99) / 1000.0,
        PushLoad.percentile(micros, 100) / 1000.0,
        rejected.sum(),
        outputLines.get(),
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertEquals(total, accepted.sum());
    assertEquals(total, outputLines.get(), "every acknowledged line is ingested");
    assertTrue(total / totalSeconds >= 4150, "T1: at least 4,150 lines/s end to end");
  }

  @Test
  void aTenantFloodingPastItsQuotaDoesNotSlowAnother() throws Exception {
    int port = PushLoad.freePort();
    Server server =
        Server.start(
            work,
            HEAP,
            PushLoad.serverArgs(
                work,
                port,
                Map.of("calm", "calm-key", "flood", "flood-key"),
                "--argus.quota.tenants.flood.ingest-bytes-per-second=1MB"));
    Client calm = new Client(port, "calm-key");
    Client flood = new Client(port, "flood-key");

    long[] alone = calmLatencies(calm, 0, 300);
    AtomicBoolean flooding = new AtomicBoolean(true);
    LongAdder floodRejected = new LongAdder();
    ExecutorService floodPool = Executors.newFixedThreadPool(6);
    for (int t = 0; t < 6; t++) {
      int stream = t;
      floodPool.submit(
          () -> {
            int b = 0;
            while (flooding.get()) {
              byte[] big = PushLoad.gzip(PushLoad.batch(stream, b++, 2000));
              if (flood.push("f" + stream, big, null).status() == 429) {
                floodRejected.increment();
              }
            }
            return null;
          });
    }
    Thread.sleep(2000);
    long[] flooded = calmLatencies(calm, 1, 300);
    flooding.set(false);
    floodPool.shutdown();
    floodPool.awaitTermination(1, TimeUnit.MINUTES);
    server.stop();

    long aloneP99 = PushLoad.percentile(alone, 99);
    long floodedP99 = PushLoad.percentile(flooded, 99);
    System.out.printf(
        "Tenant isolation: calmP99AloneMs=%.1f calmP99DuringFloodMs=%.1f floodRejected429=%d%n",
        aloneP99 / 1000.0, floodedP99 / 1000.0, floodRejected.sum());
    assertTrue(floodRejected.sum() > 0, "the flooding tenant was held to its quota");
    // The flood still uses CPU and disk on the same node, so allow some slowdown, not a stall.
    assertTrue(
        floodedP99 <= 3 * aloneP99 + 20_000,
        "calm p99 went from " + aloneP99 + "µs to " + floodedP99 + "µs");
  }

  /** Pushes {@code requests} batches as tenant calm, each must be accepted; returns µs each. */
  private static long[] calmLatencies(Client calm, int round, int requests) throws Exception {
    long[] micros = new long[requests];
    for (int i = 0; i < requests; i++) {
      byte[] body = PushLoad.gzip(PushLoad.batch(round, i, 200));
      long t0 = System.nanoTime();
      Client.Response r = calm.push("calm", body, null);
      micros[i] = (System.nanoTime() - t0) / 1000;
      assertEquals(202, r.status(), "the calm tenant is never refused: " + r.body());
      Thread.sleep(10);
    }
    return micros;
  }
}
