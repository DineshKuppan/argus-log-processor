package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.archive.WalSegments;
import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
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
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T3 through the push path, scaled down: the archive store becomes unwritable while clients keep
 * pushing. The WAL fills to its high watermark and the stream runs pause; the spools then fill and
 * pushes get 429 with {@code Retry-After}, which the clients honour. Nothing runs out of memory,
 * and once the store is back every acknowledged line is archived. The outage is {@code
 * -Dargus.load.outage-seconds} (default 20). Run with {@code mvn test -Dgroups=load
 * -DexcludedGroups=none -Dtest=PushBackpressureIT}.
 */
@Tag("load")
class PushBackpressureIT {

  private static final String HEAP = "-Xmx256m";
  private static final long LINES = Long.getLong("argus.load.push-lines", 1_000_000);
  private static final long OUTAGE_SECONDS = Long.getLong("argus.load.outage-seconds", 20);
  private static final int CLIENTS = 4;
  private static final int BATCH_LINES = 1000;
  private static final String KEY = "load-key";

  @TempDir Path work;

  @Test
  void anArchiveOutageTurnsIntoPush429sAndEverythingIsArchivedAfterwards() throws Exception {
    Path archive = Files.createDirectories(work.resolve("archive"));
    int port = PushLoad.freePort();
    Server server =
        Server.start(
            work,
            HEAP,
            PushLoad.serverArgs(
                work,
                port,
                Map.of("load", KEY),
                "--argus.archive.store=filesystem",
                "--argus.archive.filesystem.root=" + archive,
                "--argus.archive.staging-dir=" + work.resolve("staging"),
                "--argus.archive.scan-interval=200ms",
                "--argus.archive.retry-initial=100ms",
                "--argus.archive.retry-max=1s",
                "--argus.wal.max-bytes=40MB",
                "--argus.ingest.segment-bytes=4MB",
                "--argus.push.max-spool-bytes=32MB"));
    Client client = new Client(port, KEY);
    int batchesPerClient = (int) (LINES / CLIENTS / BATCH_LINES);
    Map<String, LongAdder> rejectedByReason = new ConcurrentHashMap<>();
    LongAdder accepted = new LongAdder();

    // The store is down from the start until the outage ends.
    Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("r-xr-xr-x"));
    long storeBackAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(OUTAGE_SECONDS);
    ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
    List<Future<?>> clients = new ArrayList<>();
    for (int c = 0; c < CLIENTS; c++) {
      int stream = c;
      clients.add(
          pool.submit(
              () -> {
                for (int b = 0; b < batchesPerClient; b++) {
                  byte[] body = PushLoad.gzip(PushLoad.batch(stream, b, BATCH_LINES));
                  while (true) {
                    Client.Response r = client.push("s" + stream, body, "s" + stream + "-b" + b);
                    if (r.status() == 202 || r.status() == 200) {
                      accepted.add(BATCH_LINES);
                      break;
                    }
                    assertEquals(429, r.status(), r.body());
                    assertTrue(r.retryAfterSeconds() > 0, "a 429 says when to retry");
                    String reason = PushLoad.JSON.readTree(r.body()).path("reason").asText();
                    rejectedByReason
                        .computeIfAbsent(
                            reason + "/" + r.retryAfterSeconds() + "s", k -> new LongAdder())
                        .increment();
                    // Honour Retry-After, scaled down like the outage.
                    Thread.sleep(Math.min(500, r.retryAfterSeconds() * 1000));
                  }
                }
                return null;
              }));
    }
    while (System.nanoTime() < storeBackAt) {
      Thread.sleep(200);
      assertTrue(server.isAlive(), "server died during the outage:\n" + server.tail());
    }
    Files.setPosixFilePermissions(archive, PosixFilePermissions.fromString("rwxr-xr-x"));
    long recovered = System.nanoTime();
    for (Future<?> f : clients) {
      f.get(20, TimeUnit.MINUTES);
    }
    pool.shutdown();
    client.awaitIngested(Duration.ofMinutes(10));
    WalSegments wal = new WalSegments(work.resolve("out"));
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
    while (!wal.pending().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(200);
    }
    double drainSeconds = (System.nanoTime() - recovered) / 1e9;
    long walLeft = wal.pending().size();
    Map<String, String> heap = server.stop();
    long archivedRows = WalBackpressureRunner.parquetRows(archive);
    long total = (long) batchesPerClient * CLIENTS * BATCH_LINES;
    long rejected = rejectedByReason.values().stream().mapToLong(LongAdder::sum).sum();

    System.out.printf(
        "Push backpressure (T3): lines=%d outageSeconds=%d rejected429=%d byReason=%s"
            + " drainSecondsAfterOutage=%.1f archivedRows=%d walSegmentsLeft=%d peakHeapMB=%s"
            + " maxHeapMB=%s%n",
        total,
        OUTAGE_SECONDS,
        rejected,
        rejectedByReason,
        drainSeconds,
        archivedRows,
        walLeft,
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertTrue(rejected > 0, "the outage reached the clients as 429s");
    assertEquals(total, accepted.sum());
    assertEquals(0, walLeft, "the WAL drained after the outage");
    assertEquals(total, archivedRows, "every acknowledged line is archived exactly once");
  }
}
