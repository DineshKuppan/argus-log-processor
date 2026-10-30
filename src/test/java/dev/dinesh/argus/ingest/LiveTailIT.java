package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.ingest.PushLoad.Client;
import dev.dinesh.argus.ingest.PushLoad.Server;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T7: live tail freshness and isolation, against the application in its own 256MB JVM (or, with
 * {@code -Dargus.load.docker-cpus=N}, a container with N CPUs and 4GB). Run with {@code mvn test
 * -Dgroups=load -DexcludedGroups=none -Dtest=LiveTailIT}; {@code -Dargus.load.window-seconds}
 * (default 30) sizes the slow-client windows and {@code -Dargus.load.tail-connections} (default
 * 1000) the connection test.
 */
@Tag("load")
class LiveTailIT {

  private static final String HEAP = "-Xmx256m";
  private static final String KEY = "tail-key";
  private static final int WINDOW_SECONDS = Integer.getInteger("argus.load.window-seconds", 30);
  private static final int CONNECTIONS = Integer.getInteger("argus.load.tail-connections", 1000);
  private static final int STALLED_CLIENTS = 16;

  @TempDir Path work;

  private int port;

  private Server start(String... extra) throws Exception {
    return start(List.of(), extra);
  }

  private Server start(List<String> jvmOptions, String... extra) throws Exception {
    port = PushLoad.freePort();
    return Server.start(
        work, HEAP, jvmOptions, PushLoad.serverArgs(work, port, Map.of("acme", KEY), extra));
  }

  /** A tail read on its own thread; remembers when each line containing "mark-" arrived. */
  private final class Tail implements AutoCloseable {
    final Socket socket;
    final Map<String, Long> arrivals = new ConcurrentHashMap<>();

    /** Texts whose first arrival is also recorded. */
    final java.util.Set<String> needles = ConcurrentHashMap.newKeySet();

    final CountDownLatch connected = new CountDownLatch(1);
    final StringBuffer seen = new StringBuffer();
    volatile boolean sawDropped;

    Tail(boolean read, int receiveBuffer) throws IOException {
      socket = new Socket();
      if (receiveBuffer > 0) {
        socket.setReceiveBufferSize(receiveBuffer);
      }
      socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5000);
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET /api/v2/tail HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Argus-Api-Key: "
                  + KEY
                  + "\r\nAccept: text/event-stream\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      if (read) {
        Thread reader = new Thread(this::readLines, "tail-client");
        reader.setDaemon(true);
        reader.start();
      } else {
        awaitConnectedThenStall();
      }
    }

    /** Reads only the response head and the greeting, so a 429 isn't mistaken for a stall. */
    private void awaitConnectedThenStall() throws IOException {
      InputStream in = socket.getInputStream();
      StringBuilder head = new StringBuilder();
      while (head.indexOf(": connected") < 0) {
        int b = in.read();
        if (b < 0 || (head.length() > 12 && !head.toString().startsWith("HTTP/1.1 200"))) {
          socket.close();
          throw new IllegalStateException("tail not opened: " + head);
        }
        head.append((char) b);
      }
      connected.countDown();
    }

    private void readLines() {
      try (BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line = in.readLine(); line != null; line = in.readLine()) {
          long now = System.nanoTime();
          if (line.startsWith(": connected")) {
            connected.countDown();
          }
          if (line.contains("\"dropped\"")) {
            sawDropped = true;
          }
          for (String needle : needles) {
            if (line.contains(needle)) {
              arrivals.putIfAbsent(needle, now);
            }
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

    /** For a stalled client: reads what piled up until a dropped marker or {@code timeout}. */
    boolean drainUntilDropped(Duration timeout) throws IOException {
      socket.setSoTimeout(1000);
      InputStream in = socket.getInputStream();
      byte[] buffer = new byte[64 * 1024];
      StringBuilder tail = new StringBuilder();
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        int n;
        try {
          n = in.read(buffer);
        } catch (java.net.SocketTimeoutException e) {
          continue;
        }
        if (n < 0) {
          return false;
        }
        tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        if (tail.indexOf("\"dropped\"") >= 0) {
          return true;
        }
        if (tail.length() > 1 << 16) {
          tail.delete(0, tail.length() - 64);
        }
      }
      return false;
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  private static byte[] markedLine(String mark) {
    return ("{\"level\":\"info\",\"msg\":\"" + mark + " order placed\"}\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** T7: a pushed line or a file job's line reaches an open tail within 2s (p99). */
  @Test
  void pushedAndFileLinesReachATailWithinTwoSeconds() throws Exception {
    // The file's first line (PushLoad.batch's stream 99, batch 0); written by the server itself in
    // Docker, where the host's work directory isn't visible.
    Path file = work.resolve("app.log");
    String firstLine = PushLoad.message(99, 0, 0) + " order";
    List<String> jvmOptions = new ArrayList<>();
    if (PushLoad.inDocker()) {
      jvmOptions.add("-D" + PushServerMain.GENERATE + "=" + file + ":1");
    } else {
      PushServerMain.writeLines(file, 1);
    }
    Server server = start(jvmOptions);
    Client client = new Client(port, KEY);
    long[] pushLatency;
    long fileLatency;
    try (Tail tail = new Tail(true, 0)) {
      assertTrue(tail.connected.await(10, TimeUnit.SECONDS), "tail connected");
      List<Long> latencies = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        String mark = "mark-p" + i;
        long sent = System.nanoTime();
        assertEquals(202, client.push("app", PushLoad.gzip(markedLine(mark)), null).status());
        latencies.add(awaitArrival(tail, mark, sent));
        Thread.sleep(20);
      }
      pushLatency = latencies.stream().mapToLong(Long::longValue).toArray();

      tail.needles.add(firstLine);
      long submitted = System.nanoTime();
      submitFileJob(file);
      fileLatency = awaitArrival(tail, firstLine, submitted);
    }
    Map<String, String> heap = server.stop();

    long p50 = PushLoad.percentile(pushLatency.clone(), 50);
    long p99 = PushLoad.percentile(pushLatency.clone(), 99);
    System.out.printf(
        "Live tail freshness (T7): cpus=%s pushes=%d pushToTailP50ms=%.1f pushToTailP99ms=%.1f"
            + " pushToTailMaxms=%.1f fileJobToFirstLineMs=%.1f peakHeapMB=%s maxHeapMB=%s%n",
        PushLoad.inDocker() ? PushLoad.DOCKER_CPUS : "host",
        pushLatency.length,
        p50 / 1e6,
        p99 / 1e6,
        PushLoad.percentile(pushLatency.clone(), 100) / 1e6,
        fileLatency / 1e6,
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertTrue(p99 <= TimeUnit.SECONDS.toNanos(2), "T7: push-to-tail p99 " + p99 / 1e6 + "ms");
    assertTrue(fileLatency <= TimeUnit.SECONDS.toNanos(2), "T7: file line " + fileLatency / 1e6);
  }

  /**
   * T7: tail clients that never read can't slow ingest. Four windows of the same push load: no
   * tail; 16 tail clients that read (the per-tenant limit); 16 that never read; no tail again, once
   * the ring is released. A stalled client must cost ingest no more than one that reads (p99 and
   * throughput within 20%, the T8 margin); what tailing costs at all is reported against the
   * windows without a tail.
   */
  @Test
  void stalledTailClientsDoNotSlowPushIngest() throws Exception {
    // 32: Docker Desktop's port proxy keeps the server side of a closed client open, so the
    // readers'
    // connections can outlive them; the stalled window then carries those too (a harder case).
    Server server =
        start("--argus.tail.ring-idle=1s", "--argus.tail.max-connections-per-tenant=32");
    Client client = new Client(port, KEY);
    pushFor(client, 0, Duration.ofSeconds(5)); // warm-up

    Window before = pushFor(client, 1, Duration.ofSeconds(WINDOW_SECONDS));
    List<Tail> reading = new ArrayList<>();
    for (int i = 0; i < STALLED_CLIENTS; i++) {
      reading.add(new Tail(true, 0));
    }
    Window withReaders = pushFor(client, 2, Duration.ofSeconds(WINDOW_SECONDS));
    for (Tail t : reading) {
      t.close();
    }
    List<Tail> stalled = new ArrayList<>();
    for (int i = 0; i < STALLED_CLIENTS; i++) {
      stalled.add(openStalled());
    }
    Window withStalled = pushFor(client, 3, Duration.ofSeconds(WINDOW_SECONDS));
    boolean toldDropped = stalled.get(0).drainUntilDropped(Duration.ofSeconds(30));
    for (Tail t : stalled) {
      t.close();
    }
    Thread.sleep(5_000); // closed connections are noticed, then the ring is released
    Window after = pushFor(client, 4, Duration.ofSeconds(WINDOW_SECONDS));
    Map<String, String> heap = server.stop();

    long noTailP99 = (before.p99() + after.p99()) / 2;
    System.out.printf(
        "Live tail slow clients (T7): cpus=%s clients=%d windowSeconds=%d pushP99msNoTail=%.1f/%.1f"
            + " pushP99msReadingClients=%.1f pushP99msStalledClients=%.1f"
            + " linesPerSecondNoTail=%.0f/%.0f linesPerSecondReading=%.0f linesPerSecondStalled=%.0f"
            + " stalledVsReadingPercent=%.1f tailingCostPercent=%.1f stalledToldDropped=%s"
            + " peakHeapMB=%s maxHeapMB=%s%n",
        PushLoad.inDocker() ? PushLoad.DOCKER_CPUS : "host",
        STALLED_CLIENTS,
        WINDOW_SECONDS,
        before.p99() / 1000.0,
        after.p99() / 1000.0,
        withReaders.p99() / 1000.0,
        withStalled.p99() / 1000.0,
        before.linesPerSecond(),
        after.linesPerSecond(),
        withReaders.linesPerSecond(),
        withStalled.linesPerSecond(),
        100.0 * (withStalled.p99() - withReaders.p99()) / withReaders.p99(),
        100.0 * (withReaders.p99() - noTailP99) / noTailP99,
        toldDropped,
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertTrue(toldDropped, "a stalled client learns how many lines it missed");
    assertTrue(
        withStalled.p99() <= withReaders.p99() * 1.2,
        "T7: push p99 with stalled clients "
            + withStalled.p99()
            + "µs, with reading clients "
            + withReaders.p99()
            + "µs");
    assertTrue(
        withStalled.linesPerSecond() >= withReaders.linesPerSecond() * 0.8,
        "T7: push rate with stalled clients "
            + withStalled.linesPerSecond()
            + ", with reading clients "
            + withReaders.linesPerSecond());
  }

  /** ADR-6: 1,000 open tail connections stay within the memory budget, and all receive a line. */
  @Test
  void aThousandTailConnectionsStayWithinTheBudget() throws Exception {
    Server server =
        start(
            "--argus.tail.max-connections=" + CONNECTIONS,
            "--argus.tail.max-connections-per-tenant=" + CONNECTIONS);
    List<Tail> tails = new ArrayList<>();
    long openStarted = System.nanoTime();
    for (int i = 0; i < CONNECTIONS; i++) {
      tails.add(new Tail(true, 0));
    }
    for (Tail t : tails) {
      assertTrue(t.connected.await(30, TimeUnit.SECONDS), "every tail connected");
    }
    double openSeconds = (System.nanoTime() - openStarted) / 1e9;
    long rssBefore = server.residentKilobytes();

    long sent = System.nanoTime();
    assertEquals(
        202,
        new Client(port, KEY).push("app", PushLoad.gzip(markedLine("mark-all")), null).status());
    AtomicInteger received = new AtomicInteger();
    long[] latency = new long[tails.size()];
    for (int i = 0; i < tails.size(); i++) {
      latency[i] = awaitArrival(tails.get(i), "mark-all", sent);
      received.incrementAndGet();
    }
    long rss = server.residentKilobytes();
    for (Tail t : tails) {
      t.close();
    }
    Map<String, String> heap = server.stop();

    System.out.printf(
        "Live tail connections (ADR-6): cpus=%s connections=%d openSeconds=%.1f received=%d"
            + " fanOutP50ms=%.1f fanOutP99ms=%.1f residentMBIdle=%s residentMBAfterFanOut=%s"
            + " peakHeapMB=%s maxHeapMB=%s%n",
        PushLoad.inDocker() ? PushLoad.DOCKER_CPUS : "host",
        tails.size(),
        openSeconds,
        received.get(),
        PushLoad.percentile(latency.clone(), 50) / 1e6,
        PushLoad.percentile(latency.clone(), 99) / 1e6,
        rssBefore < 0 ? "n/a" : Long.toString(rssBefore / 1024),
        rss < 0 ? "n/a" : Long.toString(rss / 1024),
        heap.get("peakHeapMB"),
        heap.get("maxHeapMB"));
    assertEquals(tails.size(), received.get());
    assertTrue(
        PushLoad.percentile(latency.clone(), 99) <= TimeUnit.SECONDS.toNanos(2),
        "T7 with 1,000 clients");
  }

  /**
   * A closed client's connection is freed when the server's next write to it fails, which through
   * Docker's port proxy can take a while: 429s are retried.
   */
  private Tail openStalled() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (true) {
      try {
        return new Tail(false, 4096);
      } catch (IllegalStateException e) {
        if (System.nanoTime() > deadline) {
          throw e;
        }
        Thread.sleep(500);
      }
    }
  }

  /** Nanoseconds from {@code since} until {@code mark} arrived; fails after 10s. */
  private static long awaitArrival(Tail tail, String mark, long since) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      Long at = tail.arrivals.get(mark);
      if (at != null) {
        return at - since;
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError(mark + " never reached the tail");
      }
      Thread.sleep(1);
    }
  }

  private void submitFileJob(Path file) throws Exception {
    String body =
        PushLoad.JSON.writeValueAsString(Map.of("path", file.toString(), "format", "json"));
    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v2/ingest/file"))
                    .header("X-Argus-Api-Key", KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(202, response.statusCode(), response.body());
  }

  /** Request latencies (µs) and lines pushed in one window. */
  private record Window(long[] micros, long lines, double seconds) {
    long p99() {
      return PushLoad.percentile(micros.clone(), 99);
    }

    double linesPerSecond() {
      return lines / seconds;
    }
  }

  /** Pushes 200-line batches with a 10ms pause for {@code duration}, as T8 does. */
  private static Window pushFor(Client client, int round, Duration duration) throws Exception {
    List<Long> micros = new ArrayList<>();
    long start = System.nanoTime();
    long end = start + duration.toNanos();
    long lines = 0;
    for (int i = 0; System.nanoTime() < end; i++) {
      byte[] body = PushLoad.gzip(PushLoad.batch(round, i, 200));
      long t0 = System.nanoTime();
      Client.Response r = client.push("live", body, null);
      micros.add((System.nanoTime() - t0) / 1000);
      assertEquals(202, r.status(), r.body());
      lines += 200;
      Thread.sleep(10);
    }
    return new Window(
        micros.stream().mapToLong(Long::longValue).toArray(),
        lines,
        (System.nanoTime() - start) / 1e9);
  }
}
