package dev.dinesh.argus.hot;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Response;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes hot-tier indices of days older than {@code retention} ({@code P5_PLAN.md} D4): the same
 * code for both engines, instead of an ILM or ISM policy each. Only names {@link HotIndices} parses
 * exactly are ever deleted. Idempotent, so several instances may run it.
 */
@Slf4j
public final class HotRetention {

  private final SearchEngineClient client;
  private final HotIndices indices;

  public HotRetention(SearchEngineClient client, HotIndices indices) {
    this.client = client;
    this.indices = indices;
  }

  /** Deletes the expired indices; returns their names. */
  public List<String> runOnce(Instant now) throws IOException, InterruptedException {
    String pattern = URLEncoder.encode(indices.pattern(), StandardCharsets.UTF_8);
    Response listing = client.get("/_cat/indices/" + pattern + "?format=json&h=index");
    if (listing.status() == 404) {
      return List.of();
    }
    if (!listing.ok()) {
      throw new IOException(
          "Listing hot indices answered " + listing.status() + ": " + listing.excerpt(300));
    }
    LocalDate oldestKept = indices.oldestKept(now);
    List<String> deleted = new ArrayList<>();
    for (JsonNode row : client.mapper().readTree(listing.body())) {
      String name = row.path("index").asText();
      Optional<LocalDate> day = indices.dayOf(name);
      if (day.isEmpty() || !day.get().isBefore(oldestKept)) {
        continue;
      }
      Response delete = client.delete("/" + URLEncoder.encode(name, StandardCharsets.UTF_8));
      if (delete.ok() || delete.status() == 404) {
        deleted.add(name);
      } else {
        log.warn("Hot tier: deleting expired index {} answered {}", name, delete.status());
      }
    }
    if (!deleted.isEmpty()) {
      log.info(
          "Hot tier: deleted {} indices older than {}: {}", deleted.size(), oldestKept, deleted);
    }
    return deleted;
  }
}
