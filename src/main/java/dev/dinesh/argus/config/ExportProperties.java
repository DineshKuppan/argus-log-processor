package dev.dinesh.argus.config;

import dev.dinesh.argus.tenant.TenantIds;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The remote systems exports may read from ({@code P3_PLAN.md} D9). Each source belongs to one
 * tenant; a request names a source, never a URL or credentials.
 */
@ConfigurationProperties("argus.export")
public record ExportProperties(List<Source> sources) {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public ExportProperties {
    sources = sources != null ? List.copyOf(sources) : List.of();
    Set<String> names = new HashSet<>();
    for (Source source : sources) {
      if (!names.add(source.name())) {
        throw new IllegalArgumentException("Duplicate argus.export.sources name: " + source.name());
      }
    }
  }

  public enum Type {
    /** Elasticsearch 7.10+ or OpenSearch 2.x: point in time + {@code search_after}. */
    ELASTICSEARCH,
    /** Coralogix's DataPrime query API, one query per time window. */
    CORALOGIX
  }

  /**
   * One source.
   *
   * @param name what requests call it
   * @param tenant the only tenant that may export from it
   * @param url Elasticsearch / OpenSearch base URL, or the Coralogix API host ({@code
   *     https://api.<domain>})
   * @param index Elasticsearch index or pattern (required for that type)
   * @param timestampField the field ranges and sorts use (Elasticsearch; default {@code
   *     @timestamp})
   * @param apiKey Elasticsearch {@code ApiKey} or Coralogix API key; set from the environment
   * @param username Elasticsearch basic auth, with {@code password}
   * @param pageSize hits per Elasticsearch page, or the Coralogix row limit per window (5,000)
   * @param keepAlive Elasticsearch point-in-time keep-alive between pages (10m)
   * @param tier Coralogix {@code frequent_search} (default) or {@code archive}
   * @param initialWindow the largest Coralogix time window (1h); windows halve when full
   * @param requestTimeout one remote request (60s)
   */
  public record Source(
      String name,
      String tenant,
      Type type,
      URI url,
      String index,
      String timestampField,
      String apiKey,
      String username,
      String password,
      int pageSize,
      Duration keepAlive,
      String tier,
      Duration initialWindow,
      Duration requestTimeout) {

    /** Coralogix's limit for the archive tier; frequent search allows 12,000. */
    private static final int MAX_PAGE_SIZE = 50_000;

    public Source {
      if (name == null || !NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "argus.export.sources name must match [A-Za-z0-9._-]{1,64}: " + name);
      }
      TenantIds.requireValid(tenant);
      if (type == null) {
        throw new IllegalArgumentException("argus.export.sources " + name + ": type is required");
      }
      if (url == null || url.getScheme() == null || url.getHost() == null) {
        throw new IllegalArgumentException(
            "argus.export.sources " + name + ": url must be an absolute http(s) URL");
      }
      if (type == Type.ELASTICSEARCH && (index == null || index.isBlank())) {
        throw new IllegalArgumentException(
            "argus.export.sources " + name + ": index is required for elasticsearch");
      }
      timestampField =
          timestampField != null && !timestampField.isBlank() ? timestampField : "@timestamp";
      pageSize = pageSize > 0 ? pageSize : 5_000;
      if (pageSize > MAX_PAGE_SIZE) {
        throw new IllegalArgumentException(
            "argus.export.sources " + name + ": page-size must be at most " + MAX_PAGE_SIZE);
      }
      keepAlive = keepAlive != null ? keepAlive : Duration.ofMinutes(10);
      tier = tier != null && !tier.isBlank() ? tier.toLowerCase(Locale.ROOT) : "frequent_search";
      if (!tier.equals("frequent_search") && !tier.equals("archive")) {
        throw new IllegalArgumentException(
            "argus.export.sources " + name + ": tier must be frequent_search or archive");
      }
      initialWindow = initialWindow != null ? initialWindow : Duration.ofHours(1);
      if (initialWindow.toMillis() < 1) {
        throw new IllegalArgumentException(
            "argus.export.sources " + name + ": initial-window must be at least 1ms");
      }
      requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(60);
    }

    /** Never prints credentials. */
    @Override
    public String toString() {
      return "Source[name="
          + name
          + ", tenant="
          + tenant
          + ", type="
          + type
          + ", url="
          + url
          + ", index="
          + index
          + "]";
    }
  }
}
