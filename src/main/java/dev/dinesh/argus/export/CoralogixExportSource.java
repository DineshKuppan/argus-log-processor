package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dinesh.argus.config.ExportProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * Exports from Coralogix with the DataPrime query API ({@code POST /api/v1/dataprime/query}), one
 * query per time window ({@code P3_PLAN.md} D7). The API returns at most {@code limit} rows per
 * query and has no cursor, so the window is the page: a window that comes back full (as many rows
 * as the limit, a {@code NumberOfResultsLimitWarning}, or an {@code INCOMPLETE} / {@code TIMED_OUT}
 * query) is halved and queried again, and a window under a quarter full doubles the next one, up to
 * {@code initial-window}. A window's rows are unordered; its key is its {@code [start, end)}.
 *
 * <p>Each row becomes one JSON line: {@code userData} (an object, or {@code {"message": text}}),
 * plus {@code timestamp} and {@code severity} from the row's metadata when the record has none, and
 * {@code coralogix.<key>} for its labels and other metadata.
 */
@Slf4j
public final class CoralogixExportSource implements ExportSource {

  static final String QUERY_PATH = "/api/v1/dataprime/query";
  static final String DEFAULT_QUERY = "source logs";
  static final String LIMIT_WARNING = "NumberOfResultsLimitWarning";
  private static final Duration MIN_WINDOW = Duration.ofMillis(1);
  private static final int ERROR_CHARS = 300;
  private static final Set<String> FULL_STATUSES = Set.of("INCOMPLETE", "TIMED_OUT");

  /** {@code JsonLogParser}'s keys: a record that has one keeps its own. */
  private static final List<String> TIMESTAMP_KEYS =
      List.of("timestamp", "@timestamp", "time", "ts");

  private static final List<String> LEVEL_KEYS =
      List.of("level", "severity", "log.level", "levelname", "lvl");

  private final ExportProperties.Source settings;
  private final HttpClient http;
  private final ObjectMapper mapper;
  private final URI endpoint;
  private final String tier;

  public CoralogixExportSource(
      ExportProperties.Source settings, HttpClient http, ObjectMapper mapper) {
    this.settings = settings;
    this.http = http;
    this.mapper = mapper;
    String url = settings.url().toString();
    this.endpoint =
        URI.create((url.endsWith("/") ? url.substring(0, url.length() - 1) : url) + QUERY_PATH);
    this.tier = settings.tier().equals("archive") ? "TIER_ARCHIVE" : "TIER_FREQUENT_SEARCH";
  }

  @Override
  public void validate(JsonNode query) {
    if (query != null && (!query.isTextual() || query.asText().isBlank())) {
      throw new IllegalArgumentException(
          "query must be DataPrime text, such as \"source logs | filter $l.applicationname =="
              + " 'api'\"");
    }
  }

  @Override
  public String describe() {
    return "coralogix " + endpoint + " tier=" + tier;
  }

  @Override
  public Page fetch(Query query, Cursor cursor) throws IOException, InterruptedException {
    Instant from =
        cursor == null ? query.from() : Instant.parse(cursor.position().get("from").asText());
    long windowMs =
        cursor == null
            ? settings.initialWindow().toMillis()
            : cursor.position().get("windowMs").asLong();
    while (true) {
      Instant to = min(from.plusMillis(windowMs), query.to());
      Result result = run(query, from, to);
      if (result.full) {
        Duration span = Duration.between(from, to);
        if (span.compareTo(MIN_WINDOW) <= 0) {
          throw new PermanentExportException(
              "Coralogix returned its limit of "
                  + settings.pageSize()
                  + " rows for the 1ms at "
                  + from
                  + "; raise page-size (up to 12,000 on frequent search, 50,000 on archive)");
        }
        windowMs = Math.max(1, span.toMillis() / 2);
        log.debug(
            "Export source {}: window at {} full, halved to {}ms", settings.name(), from, windowMs);
        continue;
      }
      long spanMs = Duration.between(from, to).toMillis();
      long nextWindow =
          result.lines.size() < settings.pageSize() / 4
              ? Math.min(Math.max(spanMs, windowMs) * 2, settings.initialWindow().toMillis())
              : windowMs;
      ObjectNode next =
          mapper
              .createObjectNode()
              .put("from", to.toString())
              .put("windowMs", Math.max(1, nextWindow));
      return new Page(
          result.lines, from + "/" + to, new Cursor(next, null), !to.isBefore(query.to()));
    }
  }

  private record Result(List<String> lines, boolean full) {}

  private Result run(Query query, Instant from, Instant to)
      throws IOException, InterruptedException {
    ObjectNode body = mapper.createObjectNode();
    body.put("query", query.query() == null ? DEFAULT_QUERY : query.query().asText());
    body.putObject("metadata")
        .put("tier", tier)
        .put("syntax", "QUERY_SYNTAX_DATAPRIME")
        .put("startDate", from.toString())
        .put("endDate", to.toString())
        .put("defaultSource", "logs")
        .put("limit", settings.pageSize());
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(settings.requestTimeout())
            .header("Content-Type", "application/json")
            .header("Accept", "application/x-ndjson")
            .header(
                "Authorization", "Bearer " + (settings.apiKey() == null ? "" : settings.apiKey()))
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
            .build();
    HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
    try (Stream<String> lines = response.body()) {
      int status = response.statusCode();
      if (status != 200) {
        String detail = String.join("\n", lines.limit(5).toList());
        detail = detail.length() > ERROR_CHARS ? detail.substring(0, ERROR_CHARS) + "…" : detail;
        String message =
            "Export source " + settings.name() + ": Coralogix answered " + status + ": " + detail;
        if (status == 429 || status >= 500) {
          throw new IOException(message);
        }
        throw new PermanentExportException(message);
      }
      boolean full =
          response.headers().allValues("Warning").stream().anyMatch(w -> w.contains(LIMIT_WARNING));
      List<String> out = new ArrayList<>();
      String queryStatus = null;
      for (Iterator<String> it = lines.iterator(); it.hasNext(); ) {
        String line = it.next();
        if (line.isBlank()) {
          continue;
        }
        JsonNode node = mapper.readTree(line);
        if (node.has("result")) {
          for (JsonNode row : node.path("result").path("results")) {
            out.add(toLine(row));
          }
        } else if (node.has("statistics")) {
          queryStatus = node.path("statistics").path("status").asText(null);
        } else if (node.has("error")) {
          throw new IOException(
              "Export source "
                  + settings.name()
                  + ": Coralogix query failed: "
                  + node.get("error"));
        }
      }
      if ("FAILED".equals(queryStatus) || "CANCELLED".equals(queryStatus)) {
        throw new IOException(
            "Export source " + settings.name() + ": Coralogix query ended " + queryStatus);
      }
      full = full || out.size() >= settings.pageSize() || FULL_STATUSES.contains(queryStatus);
      return new Result(out, full);
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private String toLine(JsonNode row) throws IOException {
    JsonNode userData = row.get("userData");
    ObjectNode record = null;
    if (userData != null && userData.isTextual()) {
      try {
        JsonNode parsed = mapper.readTree(userData.asText());
        if (parsed != null && parsed.isObject()) {
          record = (ObjectNode) parsed;
        }
      } catch (IOException notJson) {
        // plain text
      }
    } else if (userData != null && userData.isObject()) {
      record = (ObjectNode) userData.deepCopy();
    }
    if (record == null) {
      record = mapper.createObjectNode();
      record.put("message", userData == null || userData.isNull() ? "" : userData.asText());
    }
    for (JsonNode entry : row.path("metadata")) {
      String key = entry.path("key").asText();
      String value = entry.path("value").asText(null);
      if (value == null || key.isEmpty()) {
        continue;
      }
      if (key.equals("timestamp")) {
        if (TIMESTAMP_KEYS.stream().noneMatch(record::has)) {
          record.put("timestamp", value);
        }
      } else if (key.equals("severity")) {
        if (LEVEL_KEYS.stream().noneMatch(record::has)) {
          record.put("severity", value);
        }
      } else {
        record.put("coralogix." + key, value);
      }
    }
    for (JsonNode entry : row.path("labels")) {
      String key = entry.path("key").asText();
      if (!key.isEmpty() && entry.hasNonNull("value")) {
        record.put("coralogix." + key, entry.get("value").asText());
      }
    }
    return mapper.writeValueAsString(record);
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
