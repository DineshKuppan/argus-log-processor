package dev.dinesh.argus.quota;

import java.time.Duration;

/**
 * @param allowed the tokens were taken
 * @param retryAfter when denied, how long until the same request would be allowed; zero otherwise
 */
public record QuotaDecision(boolean allowed, Duration retryAfter) {

  static final QuotaDecision ALLOW = new QuotaDecision(true, Duration.ZERO);

  static QuotaDecision deny(Duration retryAfter) {
    return new QuotaDecision(false, retryAfter);
  }

  /** {@code Retry-After} in whole seconds, rounded up and at least 1. */
  public long retryAfterSeconds() {
    long millis = retryAfter.toMillis();
    return Math.max(1, (millis + 999) / 1000);
  }
}
