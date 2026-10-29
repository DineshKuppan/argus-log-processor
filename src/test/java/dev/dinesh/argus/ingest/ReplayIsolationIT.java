package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T8: a replay of a large file must not starve live ingest. A client pushes small batches at a
 * steady pace while the application (its own 256MB JVM) re-reads a 1GB file on the replay lane; its
 * push p99 is compared with the p99 measured just before and just after (live p99 ≤20% worse). For
 * contrast it also measures the same replay on the live lane, unthrottled (reported, not asserted).
 * Run with {@code mvn test -Dgroups=load -DexcludedGroups=none -Dtest=ReplayIsolationIT}; {@code
 * -Dargus.load.replay-size} (bytes, default 1GB) and {@code -Dargus.load.window-seconds} (default
 * 30) size it.
 */
@Tag("load")
class ReplayIsolationIT {

  private static final String HEAP = "-Xmx256m";
  private static final long REPLAY_BYTES = Long.getLong("argus.load.replay-size", 1L << 30);
  private static final int WINDOW_SECONDS = Integer.getInteger("argus.load.window-seconds", 30);
  private static final long REPLAY_RATE = Long.getLong("argus.load.replay-rate", 8L << 20);
  private static final String KEY = "live-key";

  @TempDir Path work;

  private final HttpClient http = HttpClient.newHttpClient();
  private int port;

  @Test
  void aReplayOnTheReplayLaneLeavesLivePushLatencyWithin20Percent() throws Exception {
    Path file = work.resolve("history.log");
    List<String> jvmOptions = new ArrayList<>();
    if (PushLoad.inDocker()) {
      // Written inside the container's file system, where the server reads it.
      jvmOptions.add("-D" + PushServerMain.GENERATE + "=" + file + ":" + REPLAY_BYTES);
    } else {
      PushServerMain.writeLines(file, REPLAY_BYTES);
    }
    port = PushLoad.freePort();
    Server server =
        Server.start(
            work,
            HEAP,
            jvmOptions,
            PushLoad.serverArgs(
                work,
                port,
                Map.of("acme", KEY),
                "--argus.replay.bytes-per-second=" + REPLAY_RATE + "B"));
    Client live = new Client(port, KEY);
    pushFor(live, 0, Duration.ofSeconds(5)); // warm-up

    long[] before = pushFor(live, 1, Duration.ofSeconds(WINDOW_SECONDS));
    String replayJob = startJob(file, "history", "replay");
    long replayStarted = System.nanoTime();
    long[] during = pushFor(live, 2, Duration.ofSeconds(WINDOW_SECONDS));
    JsonNode replay = job(replayJob);
    double replaySeconds = (System.nanoTime() - replayStarted) / 1e9;
    assertEquals("RUNNING", replay.get("state").asText(), "the replay ran the whole window");
    assertEquals("REPLAY", replay.get("lane").asText());
    long replayRead = replay.get("bytesRead").asLong();
    cancelAndWait(replayJob);
    long[] after = pushFor(live, 3, Duration.ofSeconds(WINDOW_SECONDS));

    String unthrottledJob = startJob(file, "history-live", "live");
    long unthrottledStarted = System.nanoTime();
    long[] unthrottled =
        pushWhileRunning(live, 4, unthrottledJob, Duration.ofSeconds(WINDOW_SECONDS));
    JsonNode fast = job(unthrottledJob);
    double fastSeconds = (System.nanoTime() - unthrottledStarted) / 1e9;
    long fastRead = fast.get("bytesRead").asLong();
    if (!fast.get("state").asText().equals("COMPLETED")) {
      cancelAndWait(unthrottledJob);
    }
    Map<String, String> heap = server.stop();

    long baselineP99 = (PushLoad.percentile(before, 99) + PushLoad.percentile(after, 99)) / 2;
    long duringP99 = PushLoad.percentile(during, 99);
    long unthrottledP99 = PushLoad.percentile(unthrottled, 99);
    double replayMBps = replayRead / replaySeconds / (1 << 20);
    System.out.printf(
        "Replay isolation (T8): cpus=%s replayBytes=%d windowSeconds=%d requests=%d/%d/%d"
            + " liveP50msBefore=%.1f liveP99msBefore=%.1f liveP99msAfter=%.1f"
            + " liveP50msDuringReplay=%.1f liveP99msDuringReplay=%.1f degradationPercent=%.1f"
            + " replayMBps=%.1f liveP99msDuringUnthrottled=%.1f unthrottledMBps=%.1f"
            + " peakHeapMB=%s maxHeapMB=%s%n",
        PushLoad.inDocker() ? PushLoad.DOCKER_CPUS : "host",
        REPLAY_BYTES,
        WINDOW_SECONDS,
        before.length,
        during.length,
        after.length,
        PushLoad.percentile(before, 50) / 1000.0,
        PushLoad.percentile(before, 99) / 1000.0,
        PushLoad.percentile(after, 99) / 1000.0,
        PushLoad.percentile(during, 50) / 1000.0,
        duringP99 / 1000.0,
        100.0 * (duringP99 - baselineP99) / baselineP99,
        replayMBps,
        unthrottledP99 / 1000.0,
        fastRead / fastSeconds / (1 << 20),
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertTrue(replayMBps > 0.5 * REPLAY_RATE / (1 << 20), "the replay made progress");
    assertTrue(replayMBps < 1.1 * REPLAY_RATE / (1 << 20), "the replay kept to its budget");
    assertTrue(
        duringP99 <= baselineP99 * 1.2,
        "T8: live p99 went from " + baselineP99 + "µs to " + duringP99 + "µs during the replay");
  }

  /** Pushes 200-line batches with a 10ms pause for {@code duration}; µs per request. */
  private static long[] pushFor(Client live, int round, Duration duration) throws Exception {
    List<Long> micros = new ArrayList<>();
    long end = System.nanoTime() + duration.toNanos();
    for (int i = 0; System.nanoTime() < end; i++) {
      micros.add(pushOne(live, round, i));
    }
    return micros.stream().mapToLong(Long::longValue).toArray();
  }

  private long[] pushWhileRunning(Client live, int round, String jobId, Duration max)
      throws Exception {
    List<Long> micros = new ArrayList<>();
    long end = System.nanoTime() + max.toNanos();
    for (int i = 0; System.nanoTime() < end; i++) {
      micros.add(pushOne(live, round, i));
      if (i % 50 == 0 && !job(jobId).get("state").asText().equals("RUNNING")) {
        break;
      }
    }
    return micros.stream().mapToLong(Long::longValue).toArray();
  }

  private static long pushOne(Client live, int round, int i) throws Exception {
    byte[] body = PushLoad.gzip(PushLoad.batch(round, i, 200));
    long t0 = System.nanoTime();
    Client.Response r = live.push("live", body, null);
    long micros = (System.nanoTime() - t0) / 1000;
    assertEquals(202, r.status(), "live pushes are never refused: " + r.body());
    Thread.sleep(10);
    return micros;
  }

  private String startJob(Path file, String sourceId, String lane) throws Exception {
    String body =
        PushLoad.JSON.writeValueAsString(
            Map.of(
                "path",
                file.toString(),
                "sourceId",
                sourceId,
                "fromBeginning",
                true,
                "lane",
                lane,
                "format",
                "json"));
    HttpResponse<String> response =
        http.send(
            request("/api/v2/ingest/file")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(202, response.statusCode(), response.body());
    return PushLoad.JSON.readTree(response.body()).get("jobId").asText();
  }

  private JsonNode job(String jobId) throws Exception {
    return PushLoad.JSON.readTree(
        http.send(
                request("/api/v2/ingest/jobs/" + jobId).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .body());
  }

  private void cancelAndWait(String jobId) throws Exception {
    http.send(
        request("/api/v2/ingest/jobs/" + jobId).DELETE().build(),
        HttpResponse.BodyHandlers.ofString());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (job(jobId).get("state").asText().equals("RUNNING")) {
      assertTrue(System.nanoTime() < deadline, "job " + jobId + " did not stop");
      Thread.sleep(100);
    }
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(30))
        .header("X-Argus-Api-Key", KEY);
  }
}
