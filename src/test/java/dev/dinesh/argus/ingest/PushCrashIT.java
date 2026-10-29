package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T4 through the push path: the application is killed ({@code kill -9}) at a random moment while
 * clients push with an {@code Idempotency-Key} per batch, then restarted on the same disk. Clients
 * retry whatever wasn't acknowledged, as an agent would. Every acknowledged line must be ingested,
 * and every copy of a line must carry the same {@code eventId} (a replayed WAL segment may write a
 * line twice, never as two different events). Run with {@code mvn test -Dgroups=load
 * -DexcludedGroups=none -Dtest=PushCrashIT}; {@code -Dargus.load.seed} fixes the kill moment.
 */
@Tag("load")
class PushCrashIT {

  private static final String HEAP = "-Xmx256m";
  private static final long LINES = Long.getLong("argus.load.push-lines", 400_000);
  private static final long SEED = Long.getLong("argus.load.seed", System.nanoTime());
  private static final int CLIENTS = 4;
  private static final int BATCH_LINES = 500;
  private static final String KEY = "load-key";

  @TempDir Path work;

  @Test
  void aKillDuringPushLoadLosesNoAcknowledgedLineAndDuplicatesNone() throws Exception {
    int port = PushLoad.freePort();
    // Segments of 2MB, so the kill lands with several committed segments and one open.
    List<String> args =
        PushLoad.serverArgs(work, port, Map.of("load", KEY), "--argus.ingest.segment-bytes=2MB");
    Server server = Server.start(work, HEAP, args);
    Client client = new Client(port, KEY);
    int batchesPerClient = (int) (LINES / CLIENTS / BATCH_LINES);
    LongAdder failedRequests = new LongAdder();
    LongAdder duplicates = new LongAdder();
    LongAdder acknowledged = new LongAdder();

    ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
    List<Future<?>> clients = new ArrayList<>();
    for (int c = 0; c < CLIENTS; c++) {
      int stream = c;
      clients.add(
          pool.submit(
              () -> {
                for (int b = 0; b < batchesPerClient; b++) {
                  byte[] body = PushLoad.gzip(PushLoad.batch(stream, b, BATCH_LINES));
                  pushUntilAcknowledged(client, stream, b, body, failedRequests, duplicates);
                  acknowledged.increment();
                }
                return null;
              }));
    }
    // Kill once a random 20-80% of the batches are acknowledged: always mid-load.
    long totalBatches = (long) batchesPerClient * CLIENTS;
    long killAt = (long) (totalBatches * (0.2 + 0.6 * new Random(SEED).nextDouble()));
    while (acknowledged.sum() < killAt) {
      Thread.sleep(5);
    }
    server.kill();
    Server restarted = Server.start(work, HEAP, args);
    for (Future<?> f : clients) {
      f.get(20, TimeUnit.MINUTES);
    }
    pool.shutdown();
    client.awaitIngested(Duration.ofMinutes(10));
    restarted.stop();

    Map<String, Set<String>> eventIdsByLine = new HashMap<>();
    LongAdder records = new LongAdder();
    PushLoad.forEachOutputRecord(
        work.resolve("out"),
        (file, record) -> {
          records.increment();
          String body = record.get("body").asText();
          String line = body.substring(0, body.indexOf(" order placed"));
          eventIdsByLine
              .computeIfAbsent(line, k -> new HashSet<>(2))
              .add(record.get("eventId").asText());
        });
    long expected = (long) batchesPerClient * CLIENTS * BATCH_LINES;
    long missing = 0;
    for (int s = 0; s < CLIENTS; s++) {
      for (int b = 0; b < batchesPerClient; b++) {
        for (int l = 0; l < BATCH_LINES; l++) {
          if (!eventIdsByLine.containsKey(PushLoad.message(s, b, l))) {
            missing++;
          }
        }
      }
    }
    long splitEvents = eventIdsByLine.values().stream().filter(ids -> ids.size() > 1).count();
    System.out.printf(
        "Push crash (T4): seed=%d killedAfterBatches=%d/%d lines=%d outputRecords=%d distinctLines=%d"
            + " missing=%d linesWithTwoEventIds=%d failedRequests=%d duplicateAcks=%d%n",
        SEED,
        killAt,
        totalBatches,
        expected,
        records.sum(),
        eventIdsByLine.size(),
        missing,
        splitEvents,
        failedRequests.sum(),
        duplicates.sum());
    assertTrue(failedRequests.sum() > 0, "the kill interrupted requests in flight");
    assertEquals(0, missing, "every acknowledged line was ingested");
    assertEquals(expected, eventIdsByLine.size());
    assertEquals(0, splitEvents, "a retried batch never became a second set of events");
  }

  /** Retries through the outage with the same key, as an agent's retry would. */
  private static void pushUntilAcknowledged(
      Client client, int stream, int batch, byte[] body, LongAdder failed, LongAdder duplicates)
      throws InterruptedException {
    while (true) {
      try {
        Client.Response r = client.push("s" + stream, body, "s" + stream + "-b" + batch);
        if (r.status() == 202) {
          return;
        }
        if (r.status() == 200) {
          duplicates.increment();
          return;
        }
        if (r.status() != 429) {
          throw new IllegalStateException("unexpected " + r.status() + ": " + r.body());
        }
        Thread.sleep(Math.min(1000, r.retryAfterSeconds() * 1000));
      } catch (IOException e) {
        // Connection refused or reset: the server is down. Unknown whether it was spooled.
        failed.increment();
        Thread.sleep(200);
      }
    }
  }
}
