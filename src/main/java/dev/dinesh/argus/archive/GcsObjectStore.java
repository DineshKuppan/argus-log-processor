package dev.dinesh.argus.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@link ObjectStore} on a Google Cloud Storage bucket, through the JSON API (P2 D9) rather than
 * {@code google-cloud-storage}, whose gRPC stack is 43MB of jars.
 *
 * <p>Uploads are resumable, in {@value #DEFAULT_CHUNK_BYTES}-byte chunks read from disk one at a
 * time. When a chunk fails (network error, 429, 5xx), the session is asked how much it has ({@code
 * Content-Range: bytes *}{@code /N} → 308 + {@code Range}) and the upload resumes from there, up to
 * {@value #MAX_ATTEMPTS} times; after that {@link #put} throws and the archiver backs off. List and
 * delete are retried the same way.
 *
 * <p>Credentials are Application Default Credentials (Workload Identity, a service account key, or
 * {@code gcloud} locally); null means anonymous, for emulators.
 */
public final class GcsObjectStore implements ObjectStore {

  static final String DEFAULT_ENDPOINT = "https://storage.googleapis.com";
  static final String SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";

  /** GCS needs every chunk but the last to be a multiple of 256KB. */
  static final int CHUNK_ALIGNMENT = 256 * 1024;

  static final int DEFAULT_CHUNK_BYTES = 64 * CHUNK_ALIGNMENT; // 16MB

  static final int MAX_ATTEMPTS = 3;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
  private static final Pattern RANGE = Pattern.compile("bytes=0-(\\d+)");
  private static final int RESUME_INCOMPLETE = 308;

  private final HttpClient http;
  private final ObjectMapper json = new ObjectMapper();
  private final URI endpoint;
  private final String bucket;
  private final GoogleCredentials credentials;
  private final int chunkBytes;
  private final Duration retryWait;

  /**
   * @param endpoint null for {@value #DEFAULT_ENDPOINT}
   * @param credentials null for anonymous requests (emulators only)
   */
  public GcsObjectStore(URI endpoint, String bucket, GoogleCredentials credentials) {
    this(endpoint, bucket, credentials, DEFAULT_CHUNK_BYTES, Duration.ofMillis(500));
  }

  GcsObjectStore(
      URI endpoint,
      String bucket,
      GoogleCredentials credentials,
      int chunkBytes,
      Duration retryWait) {
    if (chunkBytes <= 0 || chunkBytes % CHUNK_ALIGNMENT != 0) {
      throw new IllegalArgumentException("Chunk size must be a multiple of 256KB: " + chunkBytes);
    }
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    this.endpoint =
        URI.create(stripSlash(endpoint == null ? DEFAULT_ENDPOINT : endpoint.toString()));
    this.bucket = bucket;
    this.credentials = credentials;
    this.chunkBytes = chunkBytes;
    this.retryWait = retryWait;
  }

  /** Application Default Credentials scoped for object read/write. */
  public static GoogleCredentials defaultCredentials() throws IOException {
    return GoogleCredentials.getApplicationDefault().createScoped(SCOPE);
  }

  @Override
  public String describe() {
    return "gs://" + bucket;
  }

  @Override
  public void put(String key, Path file) throws IOException {
    ObjectKeys.requireValid(key);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      URI session = startSession(key, size);
      long offset = 0;
      int failures = 0;
      byte[] chunk = new byte[(int) Math.min(chunkBytes, Math.max(size, 1))];
      while (true) {
        Optional<Long> next;
        try {
          next = sendChunk(session, channel, chunk, offset, size);
        } catch (IOException e) {
          if (++failures >= MAX_ATTEMPTS) {
            throw new IOException("Upload of " + key + " to " + describe() + " failed", e);
          }
          pause(failures);
          next = status(session, size);
        }
        if (next.isEmpty()) {
          return; // the object is complete
        }
        if (next.get() > offset) {
          failures = 0; // progress
        } else if (++failures >= MAX_ATTEMPTS) {
          // A session that keeps answering without taking bytes would otherwise loop forever.
          throw new IOException(
              "Upload of " + key + " to " + describe() + " made no progress at byte " + offset);
        }
        offset = next.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted uploading " + key, e);
    }
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    List<String> keys = new ArrayList<>();
    String pageToken = null;
    do {
      String url =
          endpoint
              + "/storage/v1/b/"
              + encode(bucket)
              + "/o?fields=items(name),nextPageToken&prefix="
              + encode(prefix)
              + (pageToken == null ? "" : "&pageToken=" + encode(pageToken));
      HttpResponse<String> response =
          withRetries("list " + prefix, () -> send(request(URI.create(url)).GET(), 200));
      JsonNode page = json.readTree(response.body());
      for (JsonNode item : page.path("items")) {
        keys.add(item.path("name").asText());
      }
      pageToken = page.hasNonNull("nextPageToken") ? page.get("nextPageToken").asText() : null;
    } while (pageToken != null);
    return keys;
  }

  @Override
  public void delete(String key) throws IOException {
    URI uri = URI.create(objectUrl(ObjectKeys.requireValid(key)));
    withRetries("delete " + key, () -> send(request(uri).DELETE(), 204, 200, 404));
  }

  /** Starts a resumable upload; returns the session URI. */
  private URI startSession(String key, long size) throws IOException {
    URI uri =
        URI.create(
            endpoint
                + "/upload/storage/v1/b/"
                + encode(bucket)
                + "/o?uploadType=resumable&name="
                + encode(key));
    String metadata = "{\"contentType\":\"" + S3ObjectStore.PARQUET_CONTENT_TYPE + "\"}";
    HttpResponse<String> response =
        withRetries(
            "start upload of " + key,
            () ->
                send(
                    request(uri)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("X-Upload-Content-Type", S3ObjectStore.PARQUET_CONTENT_TYPE)
                        .header("X-Upload-Content-Length", Long.toString(size))
                        .POST(HttpRequest.BodyPublishers.ofString(metadata)),
                    200));
    return response
        .headers()
        .firstValue("Location")
        .map(URI::create)
        .orElseThrow(() -> new IOException("GCS returned no upload session for " + key));
  }

  /**
   * Sends the chunk starting at {@code offset}. Returns the offset to continue from, or empty when
   * the object is complete.
   */
  private Optional<Long> sendChunk(
      URI session, FileChannel channel, byte[] chunk, long offset, long size)
      throws IOException, InterruptedException {
    int length = (int) Math.min(chunk.length, size - offset);
    ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, length);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer, offset + buffer.position()) < 0) {
        throw new IOException("File shrank during upload");
      }
    }
    boolean last = offset + length == size;
    String range =
        size == 0
            ? "bytes */0"
            : "bytes " + offset + "-" + (offset + length - 1) + "/" + (last ? size : "*");
    HttpResponse<String> response =
        http.send(
            request(session)
                .header("Content-Range", range)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk, 0, length))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return progress(response, "upload chunk at " + offset);
  }

  /** Asks the session how much it has, after a failed chunk. */
  private Optional<Long> status(URI session, long size) throws IOException, InterruptedException {
    HttpResponse<String> response =
        http.send(
            request(session)
                .header("Content-Range", "bytes */" + size)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return progress(response, "query upload status");
  }

  private static Optional<Long> progress(HttpResponse<String> response, String what)
      throws IOException {
    int status = response.statusCode();
    if (status == 200 || status == 201) {
      return Optional.empty();
    }
    if (status == RESUME_INCOMPLETE) {
      Optional<String> range = response.headers().firstValue("Range");
      if (range.isEmpty()) {
        return Optional.of(0L); // nothing received yet
      }
      Matcher m = RANGE.matcher(range.get());
      if (!m.matches()) {
        throw new IOException("Unexpected Range " + range.get());
      }
      return Optional.of(Long.parseLong(m.group(1)) + 1);
    }
    throw new IOException("GCS " + what + ": HTTP " + status + " " + abbreviate(response.body()));
  }

  private interface Call {
    HttpResponse<String> run() throws IOException, InterruptedException;
  }

  private HttpResponse<String> withRetries(String what, Call call) throws IOException {
    IOException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return call.run();
      } catch (IOException e) {
        last = e;
        if (attempt < MAX_ATTEMPTS) {
          try {
            pause(attempt);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted: " + what, ie);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted: " + what, e);
      }
    }
    throw new IOException(
        "Failed to " + what + " in " + describe() + ": " + last.getMessage(), last);
  }

  /** Sends; a status outside {@code expected} throws (and so is retried by the caller). */
  private HttpResponse<String> send(HttpRequest.Builder builder, int... expected)
      throws IOException, InterruptedException {
    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    for (int status : expected) {
      if (response.statusCode() == status) {
        return response;
      }
    }
    throw new IOException("HTTP " + response.statusCode() + " " + abbreviate(response.body()));
  }

  private HttpRequest.Builder request(URI uri) throws IOException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
    if (credentials != null) {
      credentials.refreshIfExpired();
      builder.header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue());
    }
    return builder;
  }

  private void pause(int failures) throws InterruptedException {
    Thread.sleep(retryWait.toMillis() << (failures - 1));
  }

  private String objectUrl(String key) {
    return endpoint + "/storage/v1/b/" + encode(bucket) + "/o/" + encode(key);
  }

  /** Percent-encodes a whole path segment or query value; {@code /} becomes {@code %2F}. */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String stripSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String abbreviate(String body) {
    return body == null || body.length() <= 300 ? body : body.substring(0, 300) + "…";
  }
}
