package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Suppression across instances ({@code P6_PLAN.md} D11): two server JVMs sharing one Redis ({@code
 * argus.suppression.store=redis}) allow a message once per window between them, and a 10,000-line
 * push decides its suppression in 10 pipelined round trips. Host only.
 */
@Tag("load")
class SuppressionAcrossInstancesIT {

  private static final String HEAP = "-Xmx256m";
  private static final String KEY = "load-key";
  private static final int LINES = 10_000;
  private static final int REQUESTS = Integer.getInteger("argus.load.suppression-requests", 50);

  private static GenericContainer<?> redis;

  @TempDir Path work;

  private final List<Server> servers = new ArrayList<>();

  @BeforeAll
  static void startRedis() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    Assumptions.assumeFalse(PushLoad.inDocker(), "runs on the host only");
    redis =
        new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    if (redis != null) {
      redis.stop();
    }
  }

  @AfterEach
  void stopServers() {
    for (Server s : servers) {
      try {
        s.stop();
      } catch (Exception e) {
        // already gone
      }
    }
  }

  private Client start(String instance, String store) throws Exception {
    int port = PushLoad.freePort();
    Path dir = Files.createDirectories(work.resolve(instance + "-" + store));
    List<String> args =
        PushLoad.serverArgs(
            dir,
            port,
            Map.of("load", KEY),
            "--argus.instance.id=" + instance,
            "--argus.suppression.store=" + store,
            "--spring.data.redis.host=" + redis.getHost(),
            "--spring.data.redis.port=" + redis.getMappedPort(6379),
            "--argus.push.max-request-lines=" + LINES);
    servers.add(Server.start(dir, HEAP, args));
    return new Client(port, KEY);
  }

  /** {@code lines} lines whose messages are {@code prefix}0 … {@code prefix}(lines-1). */
  private static byte[] lines(String prefix, int lines) {
    StringBuilder sb = new StringBuilder(lines * 64);
    for (int i = 0; i < lines; i++) {
      sb.append("{\"level\":\"info\",\"msg\":\"").append(prefix).append(i).append("\"}\n");
    }
    return PushLoad.gzip(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  /** The same messages pushed to two instances within the window are allowed once between them. */
  @Test
  void copiesSentToTwoInstancesAreAllowedOnce() throws Exception {
    Client a = start("argus-0", "redis");
    Client b = start("argus-1", "redis");
    byte[] body = lines("disk full on node ", 1000);

    PushLoad.Client.Response first = a.push("app", body, null);
    PushLoad.Client.Response second = b.push("app", body, null);

    long accepted =
        PushLoad.JSON.readTree(first.body()).get("accepted").asLong()
            + PushLoad.JSON.readTree(second.body()).get("accepted").asLong();
    long suppressed =
        PushLoad.JSON.readTree(first.body()).get("suppressed").asLong()
            + PushLoad.JSON.readTree(second.body()).get("suppressed").asLong();
    System.out.printf(
        "Suppression across instances (D11): instances=2 pushedPerInstance=1000 accepted=%d"
            + " suppressed=%d%n",
        accepted, suppressed);
    assertEquals(1000, accepted, "each message allowed once across both instances");
    assertEquals(1000, suppressed);
  }

  /** The p99 of a 10,000-line push of unique messages, with Redis and with the local store. */
  @Test
  void aTenThousandLinePushDecidesSuppressionInPipelinedRoundTrips() throws Exception {
    long redisP99 = p99(start("argus-0", "redis"), "r");
    long localP99 = p99(start("argus-1", "local"), "l");
    System.out.printf(
        "Suppression p99 (D11): lines=%d requests=%d redisP99ms=%.1f localP99ms=%.1f%n",
        LINES, REQUESTS, redisP99 / 1e6, localP99 / 1e6);
    assertTrue(redisP99 < TimeUnit.SECONDS.toNanos(1), "redis p99 " + redisP99 / 1e6 + "ms");
  }

  private static long p99(Client client, String prefix) throws Exception {
    for (int i = 0; i < 5; i++) { // warm-up
      client.push("warm", lines(prefix + "w" + i + "-", LINES), null);
    }
    long[] durations = new long[REQUESTS];
    for (int i = 0; i < REQUESTS; i++) {
      byte[] body = lines(prefix + i + "-", LINES);
      long start = System.nanoTime();
      PushLoad.Client.Response r = client.push("app", body, null);
      durations[i] = System.nanoTime() - start;
      assertEquals(202, r.status(), r.body());
    }
    return PushLoad.percentile(durations, 99);
  }
}
