package dev.dinesh.argus.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The hot search tier ({@code P5_PLAN.md}): an OpenSearch or Elasticsearch cluster every WAL
 * segment is also indexed into, kept for {@code retention}.
 *
 * @param store {@code none} (default), {@code opensearch} or {@code elasticsearch}; checked against
 *     the cluster's {@code GET /} at startup
 * @param apiKey {@code Authorization: ApiKey} (Elasticsearch); or {@code username} / {@code
 *     password} for basic auth. From the environment, never from requests
 * @param indexPrefix shared by every tenant's indices ({@code <prefix>-YYYY.MM.DD-shared}, {@code
 *     <prefix>-YYYY.MM.DD-t-<tenant>}); a deployment prefix, as guardrail 4 allows
 * @param dedicatedTenants tenants with their own daily indices (ADR-4); lowercase, since index
 *     names are
 * @param retention indices of days older than this are deleted
 * @param flushInterval stream runs happen at least this often while a hot store is set (D7)
 * @param scanInterval how often the indexer looks for segments it hasn't indexed
 * @param bulkMaxDocs documents per {@code _bulk} request
 * @param bulkMaxBytes bytes per {@code _bulk} body
 * @param retryInitial first wait after a failure; doubles up to {@code retryMax}
 * @param requestTimeout per HTTP request to the cluster
 */
@ConfigurationProperties("argus.hot")
public record HotProperties(
    Store store,
    URI url,
    String apiKey,
    String username,
    String password,
    String indexPrefix,
    List<String> dedicatedTenants,
    Duration retention,
    int shards,
    int replicas,
    Duration refreshInterval,
    Duration flushInterval,
    Duration scanInterval,
    int bulkMaxDocs,
    DataSize bulkMaxBytes,
    Duration retryInitial,
    Duration retryMax,
    Duration requestTimeout) {

  public enum Store {
    NONE,
    OPENSEARCH,
    ELASTICSEARCH
  }

  /** What an index name may hold: lowercase, and none of {@code \ / * ? " < > | , # : space}. */
  private static final Pattern INDEX_PART = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  public HotProperties {
    store = store != null ? store : Store.NONE;
    indexPrefix = indexPrefix == null || indexPrefix.isBlank() ? "argus-logs" : indexPrefix.strip();
    if (!INDEX_PART.matcher(indexPrefix).matches()) {
      throw new IllegalArgumentException(
          "argus.hot.index-prefix must be lowercase letters, digits, '.', '_' or '-': "
              + indexPrefix);
    }
    dedicatedTenants = dedicatedTenants != null ? List.copyOf(dedicatedTenants) : List.of();
    for (String tenant : dedicatedTenants) {
      if (!INDEX_PART.matcher(tenant).matches()
          || !tenant.equals(tenant.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(
            "argus.hot.dedicated-tenants entries become index names, so must be lowercase tenant"
                + " ids: "
                + tenant);
      }
    }
    retention = positive(retention, Duration.ofDays(14), "retention");
    shards = shards > 0 ? shards : 1;
    replicas = Math.max(replicas, 0);
    refreshInterval = positive(refreshInterval, Duration.ofSeconds(5), "refresh-interval");
    flushInterval = positive(flushInterval, Duration.ofSeconds(10), "flush-interval");
    scanInterval = positive(scanInterval, Duration.ofSeconds(1), "scan-interval");
    bulkMaxDocs = bulkMaxDocs > 0 ? bulkMaxDocs : 5_000;
    bulkMaxBytes = bulkMaxBytes != null ? bulkMaxBytes : DataSize.ofMegabytes(5);
    if (bulkMaxBytes.toBytes() <= 0) {
      throw new IllegalArgumentException("argus.hot.bulk-max-bytes must be positive");
    }
    retryInitial = positive(retryInitial, Duration.ofSeconds(1), "retry-initial");
    retryMax = positive(retryMax, Duration.ofMinutes(5), "retry-max");
    if (retryMax.compareTo(retryInitial) < 0) {
      throw new IllegalArgumentException(
          "argus.hot.retry-max must not be shorter than retry-initial");
    }
    requestTimeout = positive(requestTimeout, Duration.ofSeconds(30), "request-timeout");
    if (store != Store.NONE && url == null) {
      throw new IllegalArgumentException("argus.hot.url is required with argus.hot.store=" + store);
    }
  }

  public boolean enabled() {
    return store != Store.NONE;
  }

  /** Never prints credentials. */
  @Override
  public String toString() {
    return "HotProperties[store=" + store + ", url=" + url + ", indexPrefix=" + indexPrefix + "]";
  }

  private static Duration positive(Duration value, Duration fallback, String name) {
    if (value == null) {
      return fallback;
    }
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException("argus.hot." + name + " must be positive: " + value);
    }
    return value;
  }
}
