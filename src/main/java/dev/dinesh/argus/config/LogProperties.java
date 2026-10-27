package dev.dinesh.argus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The v1 API ({@code /api/v1/logs}).
 *
 * @param maxFileSize cap for endpoints that return every record (they load the whole file)
 * @param maxConcurrentAggregations streaming aggregations at once; more are rejected with 429
 * @param enforceAllowedRoots refuse files outside {@code argus.ingest.allowed-roots} (400); {@code
 *     false} only logs a warning, as a migration window for clients that pass other paths
 */
@ConfigurationProperties("log")
public record LogProperties(
    DataSize maxFileSize, int maxConcurrentAggregations, Boolean enforceAllowedRoots) {

  /** 0 (or null) means "use the default"; negative values are configuration errors. */
  public LogProperties {
    if (maxFileSize != null && maxFileSize.isNegative()) {
      throw new IllegalArgumentException("log.max-file-size must not be negative: " + maxFileSize);
    }
    if (maxConcurrentAggregations < 0) {
      throw new IllegalArgumentException(
          "log.max-concurrent-aggregations must not be negative: " + maxConcurrentAggregations);
    }
    maxFileSize =
        maxFileSize != null && maxFileSize.toBytes() > 0 ? maxFileSize : DataSize.ofMegabytes(50);
    maxConcurrentAggregations = maxConcurrentAggregations > 0 ? maxConcurrentAggregations : 2;
    enforceAllowedRoots = enforceAllowedRoots == null || enforceAllowedRoots;
  }
}
