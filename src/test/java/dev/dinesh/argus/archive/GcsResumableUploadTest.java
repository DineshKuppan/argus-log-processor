package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GcsObjectStore}'s resumable uploads against a small server that follows the GCS protocol,
 * including the status query, and fails on demand.
 */
class GcsResumableUploadTest {

  private static final int CHUNK = GcsObjectStore.CHUNK_ALIGNMENT;
  private static final Pattern CHUNK_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

  @TempDir Path dir;

  private HttpServer server;
  private final ByteArrayOutputStream received = new ByteArrayOutputStream();
  private final List<String> ranges = new ArrayList<>();
  private final AtomicInteger failNextChunks = new AtomicInteger();
  private final AtomicInteger failNextStatus = new AtomicInteger();
  private volatile boolean storeFailedChunks;
  private volatile boolean complete;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/upload", this::upload);
    server.createContext("/session", this::session);
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private GcsObjectStore store() {
    URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    return new GcsObjectStore(endpoint, "b", null, CHUNK, Duration.ofMillis(1));
  }

  private void upload(HttpExchange exchange) throws IOException {
    exchange
        .getResponseHeaders()
        .add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/session/1");
    respond(exchange, 200);
  }

  private synchronized void session(HttpExchange exchange) throws IOException {
    String range = exchange.getRequestHeaders().getFirst("Content-Range");
    byte[] body = exchange.getRequestBody().readAllBytes();
    ranges.add(range);
    if (range.startsWith("bytes */")) {
      if (failNextStatus.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
        respond(exchange, 503);
        return;
      }
      // A status query; with every byte received it completes the upload (as for an empty file).
      if (received.size() == Long.parseLong(range.substring("bytes */".length()))) {
        complete = true;
      }
      reportProgress(exchange);
      return;
    }
    Matcher m = CHUNK_RANGE.matcher(range);
    assertTrue(m.matches(), range);
    long start = Long.parseLong(m.group(1));
    assertEquals(received.size(), start, "chunk must continue where the server is");
    boolean fail = failNextChunks.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
    if (!fail || storeFailedChunks) {
      received.write(body);
    }
    if (fail) {
      respond(exchange, 503); // or: stored, but the response was lost
      return;
    }
    if (!m.group(3).equals("*") && received.size() == Long.parseLong(m.group(3))) {
      complete = true;
      respond(exchange, 200);
    } else {
      reportProgress(exchange);
    }
  }

  private void reportProgress(HttpExchange exchange) throws IOException {
    if (complete) {
      respond(exchange, 200);
      return;
    }
    if (received.size() > 0) {
      exchange.getResponseHeaders().add("Range", "bytes=0-" + (received.size() - 1));
    }
    respond(exchange, 308);
  }

  private static void respond(HttpExchange exchange, int status) throws IOException {
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }

  private Path file(int bytes) throws IOException {
    byte[] content = new byte[bytes];
    new Random(3).nextBytes(content);
    return Files.write(dir.resolve("f.parquet"), content);
  }

  @Test
  void uploadsInAlignedChunksAndMarksOnlyTheLastWithTheTotal() throws Exception {
    Path file = file(2 * CHUNK + 100);

    store().put("tenant=a/x.parquet", file);

    assertArrayEquals(Files.readAllBytes(file), received.toByteArray());
    assertEquals(
        List.of(
            "bytes 0-" + (CHUNK - 1) + "/*",
            "bytes " + CHUNK + "-" + (2 * CHUNK - 1) + "/*",
            "bytes " + 2 * CHUNK + "-" + (2 * CHUNK + 99) + "/" + (2 * CHUNK + 100)),
        ranges);
  }

  @Test
  void aFailedChunkIsResentAfterAskingTheSessionWhereItIs() throws Exception {
    Path file = file(3 * CHUNK);
    failNextChunks.set(1); // the first chunk fails and was not stored

    store().put("k", file);

    assertArrayEquals(Files.readAllBytes(file), received.toByteArray());
    assertTrue(ranges.contains("bytes */" + 3 * CHUNK), ranges.toString());
  }

  @Test
  void aChunkStoredButWhoseResponseWasLostIsNotSentTwice() throws Exception {
    Path file = file(2 * CHUNK);
    storeFailedChunks = true;
    failNextChunks.set(1);

    store().put("k", file);

    // The server asserts every chunk starts where it is; the bytes are not duplicated.
    assertArrayEquals(Files.readAllBytes(file), received.toByteArray());
  }

  @Test
  void aChunkIsSentUpToMaxAttemptsTimes() throws Exception {
    Path file = file(CHUNK);
    failNextChunks.set(GcsObjectStore.MAX_ATTEMPTS - 1);

    store().put("k", file);

    assertArrayEquals(Files.readAllBytes(file), received.toByteArray());
  }

  @Test
  void aFailedStatusQueryIsRetried() throws Exception {
    Path file = file(2 * CHUNK);
    failNextChunks.set(1);
    failNextStatus.set(1);

    store().put("k", file);

    assertArrayEquals(Files.readAllBytes(file), received.toByteArray());
  }

  @Test
  void givesUpAfterRepeatedFailuresWithoutProgress() throws Exception {
    Path file = file(CHUNK);
    failNextChunks.set(100);

    assertThrows(IOException.class, () -> store().put("k", file));
  }

  @Test
  void aSessionThatNeverTakesBytesFailsInsteadOfLooping() throws Exception {
    server.removeContext("/session");
    server.createContext("/session", exchange -> respond(exchange, 308));

    assertThrows(IOException.class, () -> store().put("k", file(CHUNK)));
  }

  @Test
  void anEmptyFileIsOneFinalRequest() throws Exception {
    Path file = Files.write(dir.resolve("empty"), new byte[0]);

    store().put("k", file);

    assertEquals(List.of("bytes */0"), ranges);
  }

  @Test
  void chunksMustBeMultiplesOf256Kb() {
    URI endpoint = URI.create("http://127.0.0.1:1");
    assertThrows(
        IllegalArgumentException.class,
        () -> new GcsObjectStore(endpoint, "b", null, CHUNK + 1, Duration.ZERO));
  }
}
