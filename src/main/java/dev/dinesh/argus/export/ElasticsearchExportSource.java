package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dinesh.argus.config.ExportProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Pages through an Elasticsearch (7.12+) or OpenSearch (2.x) index with a point in time and {@code
 * search_after} ({@code P3_PLAN.md} D7), over {@code java.net.http}. Each hit's {@code _source}
 * becomes one compact JSON line.
 *
 * <p>The two differ, so the flavour is read from {@code GET /}: Elasticsearch adds an implicit
 * {@code _shard_doc} tiebreaker and can return the timestamp as ISO text with nanoseconds;
 * OpenSearch has neither, so the sort adds {@code _id}. A point in time that expired (the job
 * paused longer than {@code keep-alive}) is replaced by a new one: on OpenSearch {@code (timestamp,
 * _id)} is stable across points in time, so paging continues exactly; on Elasticsearch {@code
 * _shard_doc} means nothing in a new one, so paging continues from the last timestamp inclusive,
 * and hits sharing that timestamp can be exported twice (never missed).
 */
@Slf4j
public final class ElasticsearchExportSource implements ExportSource {

  enum Flavor {
    ELASTICSEARCH,
    OPENSEARCH
  }

  private static final String CONTEXT_MISSING = "search_context_missing_exception";
  private static final int ERROR_CHARS = 300;
  private static final String ES_DATE_FORMAT = "strict_date_optional_time_nanos";
  private static final String OS_DATE_FORMAT = "strict_date_optional_time";

  private final ExportProperties.Source settings;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final String base;
  private final String authorization;
  private volatile Flavor flavor;

  public ElasticsearchExportSource(
      ExportProperties.Source settings, HttpClient http, ObjectMapper mapper) {
    this.settings = settings;
    this.http = http;
    this.mapper = mapper;
    String url = settings.url().toString();
    this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
      this.authorization = "ApiKey " + settings.apiKey();
    } else if (settings.username() != null && !settings.username().isBlank()) {
      String pair =
          settings.username() + ":" + (settings.password() == null ? "" : settings.password());
      this.authorization =
          "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    } else {
      this.authorization = null;
    }
  }

  @Override
  public void validate(JsonNode query) {
    if (query != null && !query.isObject()) {
      throw new IllegalArgumentException(
          "query must be an Elasticsearch query object, such as {\"match\":{\"service\":\"api\"}}");
    }
  }

  @Override
  public String describe() {
    return "elasticsearch "
        + base
        + " index="
        + settings.index()
        + " timestamp="
        + settings.timestampField();
  }

  @Override
  public Page fetch(Query query, Cursor cursor) throws IOException, InterruptedException {
    Flavor f = flavor();
    ObjectNode position =
        cursor == null
            ? mapper.createObjectNode().put("from", query.from().toString())
            : cursor.position().deepCopy();
    String pit = cursor == null || cursor.session() == null ? openPit(f) : cursor.session();
    HttpResponse<String> response = search(f, query, position, pit);
    if (contextMissing(response)) {
      log.info("Export source {}: point in time expired; opening a new one", settings.name());
      pit = openPit(f);
      if (f == Flavor.ELASTICSEARCH && position.has("after")) {
        // _shard_doc values mean nothing in a new point in time: continue from the last
        // timestamp, inclusive.
        position = mapper.createObjectNode().put("from", position.get("after").get(0).asText());
      }
      response = search(f, query, position, pit);
    }
    JsonNode body = parse(response, "search");
    JsonNode hits = body.path("hits").path("hits");
    List<String> lines = new ArrayList<>(hits.size());
    JsonNode lastSort = null;
    for (JsonNode hit : hits) {
      JsonNode source = hit.get("_source");
      if (source == null) {
        throw new PermanentExportException(
            "Index " + settings.index() + " has _source disabled; nothing to export");
      }
      lines.add(mapper.writeValueAsString(source));
      lastSort = hit.get("sort");
    }
    if (body.hasNonNull("pit_id")) {
      pit = body.get("pit_id").asText();
    }
    String pageId = mapper.writeValueAsString(position);
    ObjectNode next = position.deepCopy();
    if (lastSort != null) {
      next.set("after", lastSort);
    }
    return new Page(lines, pageId, new Cursor(next, pit), hits.size() < settings.pageSize());
  }

  @Override
  public void release(Cursor cursor) {
    if (cursor == null || cursor.session() == null) {
      return;
    }
    try {
      Flavor f = flavor();
      ObjectNode body = mapper.createObjectNode();
      String path;
      if (f == Flavor.OPENSEARCH) {
        body.putArray("pit_id").add(cursor.session());
        path = "/_search/point_in_time";
      } else {
        body.put("id", cursor.session());
        path = "/_pit";
      }
      send(request(path).method("DELETE", json(body)));
    } catch (IOException e) {
      log.debug(
          "Export source {}: closing a point in time failed: {}", settings.name(), e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  Flavor flavor() throws IOException, InterruptedException {
    Flavor f = flavor;
    if (f == null) {
      JsonNode root = parse(send(request("/").GET()), "GET /");
      f =
          "opensearch".equals(root.path("version").path("distribution").asText())
              ? Flavor.OPENSEARCH
              : Flavor.ELASTICSEARCH;
      flavor = f;
    }
    return f;
  }

  private HttpResponse<String> search(Flavor f, Query query, ObjectNode position, String pit)
      throws IOException, InterruptedException {
    ObjectNode body = mapper.createObjectNode();
    body.put("size", settings.pageSize());
    body.put("track_total_hits", false);
    body.putObject("pit").put("id", pit).put("keep_alive", keepAlive());
    ArrayNode filter = body.putObject("query").putObject("bool").putArray("filter");
    ObjectNode range = filter.addObject().putObject("range").putObject(settings.timestampField());
    range.set("gte", position.get("from"));
    range.put("lt", query.to().toString());
    range.put("format", f == Flavor.OPENSEARCH ? OS_DATE_FORMAT : ES_DATE_FORMAT);
    if (query.query() != null) {
      filter.add(query.query());
    }
    ArrayNode sort = body.putArray("sort");
    if (f == Flavor.OPENSEARCH) {
      sort.addObject().put(settings.timestampField(), "asc");
      sort.addObject().put("_id", "asc");
    } else {
      // Plus the implicit _shard_doc tiebreaker every point-in-time search gets.
      sort.addObject()
          .putObject(settings.timestampField())
          .put("order", "asc")
          .put("format", ES_DATE_FORMAT);
    }
    if (position.has("after")) {
      body.set("search_after", position.get("after"));
    }
    return send(request("/_search").POST(json(body)));
  }

  private String openPit(Flavor f) throws IOException, InterruptedException {
    String index = URLEncoder.encode(settings.index(), StandardCharsets.UTF_8);
    String keepAlive = URLEncoder.encode(keepAlive(), StandardCharsets.UTF_8);
    String path =
        f == Flavor.OPENSEARCH
            ? "/" + index + "/_search/point_in_time?keep_alive=" + keepAlive
            : "/" + index + "/_pit?keep_alive=" + keepAlive;
    JsonNode body =
        parse(
            send(request(path).POST(HttpRequest.BodyPublishers.noBody())), "open a point in time");
    JsonNode id = body.get(f == Flavor.OPENSEARCH ? "pit_id" : "id");
    if (id == null || id.asText().isEmpty()) {
      throw new IOException("Opening a point in time returned no id");
    }
    return id.asText();
  }

  private String keepAlive() {
    return Math.max(1, settings.keepAlive().toSeconds()) + "s";
  }

  private boolean contextMissing(HttpResponse<String> response) {
    return response.statusCode() >= 400 && response.body().contains(CONTEXT_MISSING);
  }

  /** The body as JSON if the call succeeded; otherwise a transient or permanent failure. */
  private JsonNode parse(HttpResponse<String> response, String what) throws IOException {
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return mapper.readTree(response.body());
    }
    String detail = response.body();
    detail = detail.length() > ERROR_CHARS ? detail.substring(0, ERROR_CHARS) + "…" : detail;
    String message =
        "Export source " + settings.name() + ": " + what + " answered " + status + ": " + detail;
    if (status == 429 || status >= 500) {
      throw new IOException(message);
    }
    throw new PermanentExportException(message);
  }

  private HttpRequest.Builder request(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return builder;
  }

  private HttpRequest.BodyPublisher json(JsonNode body) throws IOException {
    return HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body));
  }

  private HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
