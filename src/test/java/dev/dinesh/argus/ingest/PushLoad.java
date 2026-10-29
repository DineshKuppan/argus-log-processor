package dev.dinesh.argus.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** Shared by the push load tests: a server in its own JVM, an HTTP client, and test data. */
final class PushLoad {

  static final ObjectMapper JSON = new ObjectMapper();

  private PushLoad() {}

  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Properties every push load server shares; {@code extra} are added (and win). */
  static List<String> serverArgs(
      Path work, int port, Map<String, String> tenantKeys, String... extra) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--server.port=" + port,
                "--spring.devtools.restart.enabled=false",
                "--spring.devtools.livereload.enabled=false",
                "--argus.ingest.allowed-roots=" + work,
                "--argus.ingest.checkpoint-dir=" + work.resolve("cp"),
                "--argus.sink.local.root=" + work.resolve("out"),
                "--argus.sink.local.min-free-bytes=0B",
                "--argus.push.spool-dir=" + work.resolve("spool"),
                "--argus.push.flush-interval=1s",
                "--argus.quota.ingest-bytes-per-second=1GB"));
    int i = 0;
    for (Map.Entry<String, String> key : tenantKeys.entrySet()) {
      args.add("--argus.tenancy.api-keys[" + i + "].tenant=" + key.getKey());
      args.add("--argus.tenancy.api-keys[" + i + "].key-sha256=" + sha256(key.getValue()));
      i++;
    }
    args.addAll(Arrays.asList(extra));
    return args;
  }

  /**
   * With {@code -Dargus.load.docker-cpus=N} the server runs in a Linux container limited to N CPUs
   * and 4GB (the sizing target's node), from the same class path, mounted read-only. Its data
   * directory is a Docker volume at {@code work}'s path, so fsyncs aren't measured through the host
   * file share; the host then sees only {@code server.log}.
   */
  static final String DOCKER_CPUS = System.getProperty("argus.load.docker-cpus");

  static boolean inDocker() {
    return DOCKER_CPUS != null && !DOCKER_CPUS.isBlank();
  }

  /** The application in a separate JVM with {@code heap}. */
  static final class Server {
    private final Process process;
    private final Path log;
    private final String container;

    private Server(Process process, Path log, String container) {
      this.process = process;
      this.log = log;
      this.container = container;
    }

    static Server start(Path work, String heap, List<String> args) throws Exception {
      return start(work, heap, List.of(), args);
    }

    /**
     * @param jvmOptions extra JVM options (system properties for {@link PushServerMain})
     */
    static Server start(Path work, String heap, List<String> jvmOptions, List<String> args)
        throws Exception {
      Path log = work.resolve("server.log");
      long logStart = Files.exists(log) ? Files.size(log) : 0;
      List<String> command = new ArrayList<>();
      String container = null;
      if (inDocker()) {
        container = "argus-load-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
        command.addAll(
            List.of(
                "docker",
                "run",
                "--rm",
                "-i",
                "--name",
                container,
                "--cpus=" + DOCKER_CPUS,
                "-m",
                "4g",
                "--memory-swap",
                "4g",
                "-p",
                "127.0.0.1:" + serverPort(args) + ":" + serverPort(args),
                "-v",
                System.getProperty("user.home")
                    + "/.m2:"
                    + System.getProperty("user.home")
                    + "/.m2:ro",
                "-v",
                Path.of("").toAbsolutePath() + ":" + Path.of("").toAbsolutePath() + ":ro",
                "-v",
                container + ":" + work,
                "eclipse-temurin:17-jre",
                "java"));
      } else {
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      }
      command.addAll(List.of(heap, "-XX:+ExitOnOutOfMemoryError"));
      command.addAll(jvmOptions);
      // Surefire's java.class.path can be a manifest-only jar in a temp directory the container
      // can't see; the real entries are in surefire.test.class.path.
      String classPath =
          inDocker()
              ? System.getProperty(
                  "surefire.test.class.path", System.getProperty("java.class.path"))
              : System.getProperty("java.class.path");
      command.addAll(List.of("-cp", classPath, PushServerMain.class.getName()));
      command.addAll(args);
      Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
              .start();
      Server server = new Server(process, log, container);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(inDocker() ? 300 : 90);
      while (!server.logSince(logStart).contains("READY port=")) {
        if (!process.isAlive()) {
          throw new IllegalStateException("server exited:\n" + server.tail());
        }
        if (System.nanoTime() > deadline) {
          process.destroyForcibly();
          throw new IllegalStateException("server did not start:\n" + server.tail());
        }
        Thread.sleep(100);
      }
      return server;
    }

    private static String serverPort(List<String> args) {
      return args.stream()
          .filter(a -> a.startsWith("--server.port="))
          .map(a -> a.substring("--server.port=".length()))
          .reduce((a, b) -> b)
          .orElseThrow(() -> new IllegalArgumentException("no --server.port"));
    }

    /** {@code kill -9}. */
    void kill() throws Exception {
      if (container != null) {
        new ProcessBuilder("docker", "kill", container).inheritIO().start().waitFor();
      }
      process.destroyForcibly();
      process.waitFor(30, TimeUnit.SECONDS);
    }

    /** Graceful shutdown; returns the server's HEAP line. */
    Map<String, String> stop() throws Exception {
      try (OutputStream stdin = process.getOutputStream()) {
        stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
      }
      if (!process.waitFor(90, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("server did not stop:\n" + tail());
      }
      if (container != null) {
        new ProcessBuilder("docker", "volume", "rm", "-f", container).start().waitFor();
      }
      String heapLine =
          Files.readAllLines(log).stream()
              .filter(l -> l.startsWith("HEAP "))
              .reduce((a, b) -> b)
              .orElseThrow(() -> new IllegalStateException("no HEAP line:\n" + tail()));
      return keyValues(heapLine.substring("HEAP ".length()));
    }

    boolean isAlive() {
      return process.isAlive();
    }

    long countLogLines(String needle) throws IOException {
      try (Stream<String> lines = Files.lines(log)) {
        return lines.filter(l -> l.contains(needle)).count();
      }
    }

    String tail() {
      try {
        List<String> lines = Files.readAllLines(log);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
      } catch (IOException e) {
        return "(no log: " + e + ")";
      }
    }

    private String logSince(long offset) throws IOException {
      byte[] bytes = Files.readAllBytes(log);
      return new String(
          bytes,
          (int) Math.min(offset, bytes.length),
          (int) Math.max(0, bytes.length - offset),
          StandardCharsets.UTF_8);
    }
  }

  static Map<String, String> keyValues(String line) {
    Map<String, String> result = new HashMap<>();
    for (String kv : line.trim().split(" ")) {
      String[] parts = kv.split("=", 2);
      result.put(parts[0], parts.length > 1 ? parts[1] : "");
    }
    return result;
  }

  /** A push client for one API key. */
  static final class Client {
    private final HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI base;
    private final String apiKey;

    Client(int port, String apiKey) {
      this.base = URI.create("http://127.0.0.1:" + port + "/api/v2/ingest/streams");
      this.apiKey = apiKey;
    }

    /**
     * @param retryAfterSeconds the {@code Retry-After} header, or 0
     */
    record Response(int status, long retryAfterSeconds, String body) {}

    Response push(String stream, byte[] gzipBody, String idempotencyKey)
        throws IOException, InterruptedException {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(base + "/" + stream))
              .timeout(Duration.ofSeconds(60))
              .header("X-Argus-Api-Key", apiKey)
              .header("Content-Type", "application/x-ndjson")
              .header("Content-Encoding", "gzip")
              .POST(HttpRequest.BodyPublishers.ofByteArray(gzipBody));
      if (idempotencyKey != null) {
        request.header("Idempotency-Key", idempotencyKey);
      }
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      long retryAfter =
          response.headers().firstValue("Retry-After").map(Long::parseLong).orElse(0L);
      return new Response(response.statusCode(), retryAfter, response.body());
    }

    JsonNode streams() throws IOException, InterruptedException {
      HttpRequest request =
          HttpRequest.newBuilder(base)
              .timeout(Duration.ofSeconds(30))
              .header("X-Argus-Api-Key", apiKey)
              .GET()
              .build();
      return JSON.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    /** Waits until every stream's spool is fully ingested (its checkpoint covers it). */
    void awaitIngested(Duration timeout) throws Exception {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        long pending = 0;
        for (JsonNode s : streams()) {
          pending += s.get("pendingBytes").asLong();
        }
        if (pending == 0) {
          return;
        }
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("still " + pending + " bytes not ingested");
        }
        Thread.sleep(250);
      }
    }
  }

  /**
   * {@code lines} NDJSON records whose messages are unique per (stream, batch, line), about 200
   * bytes each.
   */
  static byte[] batch(int stream, int batch, int lines) {
    StringBuilder sb = new StringBuilder(lines * 220);
    for (int l = 0; l < lines; l++) {
      int n = batch * lines + l;
      sb.append("{\"ts\":\"2026-10-28T12:")
          .append(String.format("%02d:%02d.%03d", (n / 60_000) % 60, (n / 1000) % 60, n % 1000))
          .append("Z\",\"level\":\"")
          .append(n % 50 == 0 ? "ERROR" : "INFO")
          .append("\",\"service\":\"checkout\",\"msg\":\"")
          .append(message(stream, batch, l))
          .append(" order placed user=")
          .append(n * 7919 % 100_000)
          .append(" amount=")
          .append(n % 997)
          .append(".42\",\"trace_id\":\"")
          .append(String.format("%032x", (long) n * 2654435761L))
          .append("\"}\n");
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** The unique part of a {@link #batch} line's message. */
  static String message(int stream, int batch, int line) {
    return "s" + stream + " b" + batch + " l" + line;
  }

  static byte[] gzip(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4);
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** Calls {@code each} with every NDJSON record the local sink wrote under {@code out}. */
  static void forEachOutputRecord(Path out, BiConsumer<Path, JsonNode> each) throws IOException {
    if (!Files.exists(out)) {
      return;
    }
    try (Stream<Path> files = Files.walk(out)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".ndjson")).toList()) {
        try (Stream<String> lines = Files.lines(file)) {
          lines.forEach(
              line -> {
                try {
                  each.accept(file, JSON.readTree(line));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
        }
      }
    }
  }

  /** p-th percentile (0–100) of {@code values}, which it sorts. */
  static long percentile(long[] values, double p) {
    if (values.length == 0) {
      return 0;
    }
    Arrays.sort(values);
    int index = (int) Math.ceil(p / 100.0 * values.length) - 1;
    return values[Math.max(0, Math.min(values.length - 1, index))];
  }
}
