package dev.dinesh.argus.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * An OpenSearch or Elasticsearch cluster over {@code java.net.http} ({@code P5_PLAN.md} D8): base
 * URL, credentials, timeouts and the engine's flavour, read once from {@code GET /}. Shared by the
 * export source (P3b), the hot indexer and search. Status codes are returned, not thrown: each
 * caller decides what is transient.
 */
public final class SearchEngineClient {

  public enum Flavor {
    ELASTICSEARCH,
    OPENSEARCH
  }

  /** The cluster's flavour and version ({@code version.number}). */
  public record Engine(Flavor flavor, String version) {

    /** The major version, or 0 if it can't be read. */
    public int major() {
      try {
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
      } catch (RuntimeException e) {
        return 0;
      }
    }

    /** The minor version, or 0 if it can't be read. */
    public int minor() {
      try {
        String[] parts = version.split("\\.");
        return Integer.parseInt(parts[1]);
      } catch (RuntimeException e) {
        return 0;
      }
    }
  }

  /** A response, its body as text. */
  public record Response(int status, String body) {
    public boolean ok() {
      return status >= 200 && status < 300;
    }

    /** 429 and 5xx: worth retrying. */
    public boolean transientFailure() {
      return status == 429 || status >= 500;
    }

    /** The body, cut to {@code max} characters, for error messages. */
    public String excerpt(int max) {
      return body.length() > max ? body.substring(0, max) + "…" : body;
    }
  }

  /** A response body longer than the caller's cap. */
  public static final class ResponseTooLargeException extends IOException {
    public ResponseTooLargeException(long maxBytes) {
      super("The response is larger than " + maxBytes + " bytes");
    }
  }

  public static final String JSON = "application/json";
  public static final String NDJSON = "application/x-ndjson";

  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String base;
  private final String authorization;
  private final Duration timeout;
  private volatile Engine engine;

  /**
   * @param apiKey sent as {@code Authorization: ApiKey}; else {@code username}/{@code password} as
   *     basic auth; else none
   */
  public SearchEngineClient(
      URI url,
      String apiKey,
      String username,
      String password,
      Duration timeout,
      HttpClient http,
      ObjectMapper mapper) {
    this.http = http;
    this.mapper = mapper;
    this.timeout = timeout;
    String text = url.toString();
    this.base = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    if (apiKey != null && !apiKey.isBlank()) {
      this.authorization = "ApiKey " + apiKey;
    } else if (username != null && !username.isBlank()) {
      String pair = username + ":" + (password == null ? "" : password);
      this.authorization =
          "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    } else {
      this.authorization = null;
    }
  }

  /** The base URL, without credentials. */
  public String base() {
    return base;
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  /** Read from {@code GET /} on first use, then cached. */
  public Engine engine() throws IOException, InterruptedException {
    Engine e = engine;
    if (e == null) {
      Response response = send("GET", "/", null, null);
      if (!response.ok()) {
        throw new IOException("GET / answered " + response.status() + ": " + response.excerpt(300));
      }
      JsonNode version = mapper.readTree(response.body()).path("version");
      e =
          new Engine(
              "opensearch".equals(version.path("distribution").asText())
                  ? Flavor.OPENSEARCH
                  : Flavor.ELASTICSEARCH,
              version.path("number").asText(""));
      engine = e;
    }
    return e;
  }

  public Flavor flavor() throws IOException, InterruptedException {
    return engine().flavor();
  }

  public Response get(String path) throws IOException, InterruptedException {
    return send("GET", path, null, null);
  }

  public Response delete(String path) throws IOException, InterruptedException {
    return send("DELETE", path, null, null);
  }

  /** {@code method} with a JSON body ({@code null} for none). */
  public Response json(String method, String path, JsonNode body)
      throws IOException, InterruptedException {
    return send(method, path, body == null ? null : mapper.writeValueAsBytes(body), JSON);
  }

  public Response send(String method, String path, byte[] body, String contentType)
      throws IOException, InterruptedException {
    return send(method, path, body, contentType, Long.MAX_VALUE);
  }

  /**
   * @param maxResponseBytes read at most this much of the body; past it, {@link
   *     ResponseTooLargeException}
   */
  public Response send(
      String method, String path, byte[] body, String contentType, long maxResponseBytes)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(timeout)
            .header("Accept", JSON)
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    if (maxResponseBytes == Long.MAX_VALUE) {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Response(response.statusCode(), response.body());
    }
    HttpResponse<InputStream> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream in = response.body()) {
      byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxResponseBytes + 1));
      if (bytes.length > maxResponseBytes) {
        throw new ResponseTooLargeException(maxResponseBytes);
      }
      return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
    }
  }

  /** Never prints credentials. */
  @Override
  public String toString() {
    return "SearchEngineClient[" + base + "]";
  }
}
