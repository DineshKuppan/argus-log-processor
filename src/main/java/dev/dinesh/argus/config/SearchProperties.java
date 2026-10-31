package dev.dinesh.argus.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * {@code GET /api/v2/search} ({@code P5_PLAN.md} D6): the bounds that keep any search cheap.
 *
 * @param maxRange the widest {@code [from, to)}
 * @param maxSize hits per page
 * @param maxConcurrentSearches searches at once on this node; more get 429, never a queue
 * @param maxResponseBytes the engine's answer is read up to this; a larger one is a 502
 * @param timeout the engine's search timeout; a search that hits it answers what it found
 */
@ConfigurationProperties("argus.search")
public record SearchProperties(
    Duration maxRange,
    int maxSize,
    int maxConcurrentSearches,
    DataSize maxResponseBytes,
    Duration timeout) {

  public SearchProperties {
    maxRange =
        maxRange != null && !maxRange.isNegative() && !maxRange.isZero()
            ? maxRange
            : Duration.ofDays(14);
    maxSize = maxSize > 0 ? maxSize : 500;
    maxConcurrentSearches = maxConcurrentSearches > 0 ? maxConcurrentSearches : 8;
    maxResponseBytes = maxResponseBytes != null ? maxResponseBytes : DataSize.ofMegabytes(32);
    timeout =
        timeout != null && !timeout.isNegative() && !timeout.isZero()
            ? timeout
            : Duration.ofSeconds(10);
  }

  public static SearchProperties defaults() {
    return new SearchProperties(null, 0, 0, null, null);
  }
}
