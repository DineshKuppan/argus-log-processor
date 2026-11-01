package dev.dinesh.argus.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dinesh.argus.config.SearchProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Response;
import dev.dinesh.argus.engine.SearchEngineClient.ResponseTooLargeException;
import dev.dinesh.argus.hot.HotIndices;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Searches the hot tier ({@code P5_PLAN.md} D6). The tenant filter is a {@code term} in the query's
 * {@code bool.filter}, beside (never inside) the caller's {@code q}, and only the caller's indices
 * are read: tenant isolation doesn't depend on what a request says. At most {@code
 * max-concurrent-searches} run at once; more are refused, never queued.
 */
@Slf4j
public class SearchService {

  /** {@code simple_query_string} without regexes, fuzzy matching, slop or field names. */
  static final String Q_FLAGS = "AND|OR|NOT|PHRASE|PREFIX|WHITESPACE|ESCAPE|PRECEDENCE";

  private static final int ENGINE_RETRY_AFTER_SECONDS = 5;

  private final SearchEngineClient client;
  private final HotIndices indices;
  private final SearchProperties props;
  private final ObjectMapper mapper;
  private final ArgusMetrics metrics;
  private final Clock clock;
  private final Semaphore slots;

  public SearchService(
      SearchEngineClient client,
      HotIndices indices,
      SearchProperties props,
      ArgusMetrics metrics,
      Clock clock) {
    this.client = client;
    this.indices = indices;
    this.props = props;
    this.mapper = client.mapper();
    this.metrics = metrics;
    this.clock = clock;
    this.slots = new Semaphore(props.maxConcurrentSearches());
  }

  /** The start of the hot window: the oldest day retention keeps. */
  public Instant oldest() {
    return indices.oldestKept(clock.instant()).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  public SearchProperties props() {
    return props;
  }

  /**
   * @param cursor a previous page's {@code next}, or null
   * @throws IllegalArgumentException a bad cursor (the query itself is validated by {@link
   *     SearchQuery#parse})
   * @throws SearchException 429 when every slot is busy, 503 when the engine can't answer, 502 when
   *     its answer is too large
   */
  public SearchResult search(SearchQuery query, String cursor) {
    ArrayNode after =
        cursor == null || cursor.isBlank() ? null : SearchCursor.decode(cursor, query, mapper);
    if (!slots.tryAcquire()) {
      metrics.search(query.tenantId(), "busy", 0);
      throw new SearchException(
          429, 1, "All " + props.maxConcurrentSearches() + " search slots are busy");
    }
    long start = System.nanoTime();
    String outcome = "failed";
    int hits = 0;
    boolean partial = false;
    long engineTookMs = -1;
    try {
      Response response = send(query, after);
      if (response.status() == 429 || response.status() >= 500) {
        outcome = "unavailable";
        throw new SearchException(
            503,
            ENGINE_RETRY_AFTER_SECONDS,
            "The search engine is unavailable (" + response.status() + "); try again shortly");
      }
      if (!response.ok()) {
        outcome = "rejected";
        log.warn("Search rejected by the engine: {} {}", response.status(), response.excerpt(300));
        throw new SearchException(400, 0, "The search engine rejected this search");
      }
      JsonNode body = mapper.readTree(response.body());
      // A shard that failed (or timed out) leaves the hits incomplete without failing the search.
      partial =
          body.path("timed_out").asBoolean(false)
              || body.path("_shards").path("failed").asInt(0) > 0;
      engineTookMs = body.path("took").asLong(-1);
      List<SearchHit> found = new ArrayList<>();
      JsonNode lastSort = null;
      for (JsonNode hit : body.path("hits").path("hits")) {
        found.add(hit(hit.path("_source")));
        lastSort = hit.get("sort");
      }
      hits = found.size();
      String next =
          hits == query.size() && lastSort != null
              ? SearchCursor.encode(query, lastSort, mapper)
              : null;
      outcome = partial ? "partial" : "ok";
      return new SearchResult(
          found, next, partial, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    } catch (ResponseTooLargeException e) {
      outcome = "too_large";
      throw new SearchException(
          502,
          0,
          "The result is larger than " + props.maxResponseBytes() + "; ask for a smaller size");
    } catch (IOException e) {
      outcome = "unavailable";
      throw new SearchException(
          503, ENGINE_RETRY_AFTER_SECONDS, "The search engine is unreachable; try again shortly");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SearchException(503, ENGINE_RETRY_AFTER_SECONDS, "Interrupted");
    } finally {
      slots.release();
      long nanos = System.nanoTime() - start;
      metrics.search(query.tenantId(), outcome, nanos);
      // Canonical line: one per search (guardrail 9). Never q's text: it may hold customer data.
      log.info(
          "Search finished tenant={} from={} to={} {} size={} page={} hits={} partial={}"
              + " outcome={} engineTookMs={} durationMs={}",
          query.tenantId(),
          query.from(),
          query.to(),
          query.describeFilters(),
          query.size(),
          after == null ? "first" : "next",
          hits,
          partial,
          outcome,
          engineTookMs,
          TimeUnit.NANOSECONDS.toMillis(nanos));
    }
  }

  private Response send(SearchQuery query, ArrayNode after)
      throws IOException, InterruptedException {
    List<String> names = indices.searchIndices(query.tenantId(), query.from(), query.to());
    StringBuilder path = new StringBuilder("/");
    for (int i = 0; i < names.size(); i++) {
      path.append(i == 0 ? "" : ",")
          .append(URLEncoder.encode(names.get(i), StandardCharsets.UTF_8));
    }
    path.append("/_search?ignore_unavailable=true&allow_no_indices=true");
    if (!indices.isDedicated(query.tenantId())) {
      // Only shared indices, where the tenant's lines were routed to one shard.
      path.append("&routing=").append(URLEncoder.encode(query.tenantId(), StandardCharsets.UTF_8));
    }
    return client.send(
        "POST",
        path.toString(),
        mapper.writeValueAsBytes(body(query, after)),
        SearchEngineClient.JSON,
        props.maxResponseBytes().toBytes());
  }

  ObjectNode body(SearchQuery query, ArrayNode after) {
    ObjectNode body = mapper.createObjectNode();
    body.put("size", query.size());
    body.put("track_total_hits", false);
    body.put("timeout", props.timeout().toMillis() + "ms");
    ObjectNode bool = body.putObject("query").putObject("bool");
    ArrayNode filter = bool.putArray("filter");
    filter.addObject().putObject("term").put("tenant_id", query.tenantId());
    filter
        .addObject()
        .putObject("range")
        .putObject("@timestamp")
        .put("gte", query.from().toString())
        .put("lt", query.to().toString())
        .put("format", "strict_date_optional_time");
    if (query.minSeverity() > 0) {
      filter
          .addObject()
          .putObject("range")
          .putObject("severity_number")
          .put("gte", query.minSeverity());
    }
    if (query.source() != null) {
      filter.addObject().putObject("term").put("source_id", query.source());
    }
    if (query.service() != null) {
      filter.addObject().putObject("term").put("service_name", query.service());
    }
    for (SearchQuery.Attribute a : query.attributes()) {
      filter.addObject().putObject("term").put("attributes." + a.key(), a.value());
    }
    if (query.q() != null) {
      ObjectNode text = bool.putArray("must").addObject().putObject("simple_query_string");
      text.put("query", query.q());
      text.putArray("fields").add("body");
      text.put("default_operator", "and");
      text.put("flags", Q_FLAGS);
    }
    ArrayNode sort = body.putArray("sort");
    sort.addObject().putObject("@timestamp").put("order", "desc");
    sort.addObject().putObject("event_id").put("order", "desc");
    if (after != null) {
      body.set("search_after", after);
    }
    return body;
  }

  @SuppressWarnings("unchecked")
  private SearchHit hit(JsonNode source) {
    boolean missing = source.path("event_time_missing").asBoolean(false);
    return new SearchHit(
        text(source, "event_id"),
        missing ? null : instant(source, "@timestamp"),
        instant(source, "observed_timestamp"),
        text(source, "severity_text"),
        source.path("severity_number").asInt(0),
        text(source, "body"),
        text(source, "trace_id"),
        text(source, "span_id"),
        source.has("resource") ? mapper.convertValue(source.get("resource"), Map.class) : null,
        source.has("attributes") ? mapper.convertValue(source.get("attributes"), Map.class) : null,
        new LogEnvelope.Source(
            text(source, "source_id"),
            source.path("source_generation").asInt(0),
            source.path("source_offset").asLong(0),
            text(source, "source_format"),
            text(source, "source_instance")));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Instant instant(JsonNode node, String field) {
    String value = text(node, field);
    return value == null ? null : Instant.parse(value);
  }
}
