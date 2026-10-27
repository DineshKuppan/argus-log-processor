package dev.dinesh.argus.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The suppression window: a message is allowed once per {@code window} per tenant.
 *
 * @param maxKeys windows held at once by the local store; at ~4k distinct messages/s peak, a 10s
 *     window needs ~40k, so the default leaves 2.5x headroom (about 30MB)
 * @param templateFingerprint treat messages differing only in numbers, UUIDs and hex ids as the
 *     same message (off: the brief asks for identical messages)
 */
@ConfigurationProperties("argus.suppression")
public record SuppressionProperties(
    Duration window, long maxKeys, Store store, Boolean templateFingerprint) {

  public enum Store {
    /** One node, in memory (Caffeine). */
    LOCAL,
    /** Shared by every instance; fails open when Redis is unavailable. */
    REDIS
  }

  public SuppressionProperties {
    window = window != null ? window : Duration.ofSeconds(10);
    // Redis expiries are in whole milliseconds: a shorter window would become PX 0, which Redis
    // rejects, so every check would silently fail open.
    if (window.compareTo(Duration.ofMillis(1)) < 0) {
      throw new IllegalArgumentException(
          "argus.suppression.window must be at least 1ms: " + window);
    }
    maxKeys = maxKeys > 0 ? maxKeys : 100_000;
    store = store != null ? store : Store.LOCAL;
    templateFingerprint = templateFingerprint != null && templateFingerprint;
  }
}
