package dev.dinesh.argus.suppression;

import java.time.Instant;

/**
 * The answer to "may this message be printed now?".
 *
 * @param windowEndsAt when the same message will next be allowed
 * @param suppressedCount copies denied in the current window, including this one (0 on ALLOW)
 */
public record SuppressionDecision(Decision decision, Instant windowEndsAt, long suppressedCount) {

  public enum Decision {
    ALLOW,
    DENY
  }

  public static SuppressionDecision allow(Instant windowEndsAt) {
    return new SuppressionDecision(Decision.ALLOW, windowEndsAt, 0);
  }

  public static SuppressionDecision deny(Instant windowEndsAt, long suppressedCount) {
    return new SuppressionDecision(Decision.DENY, windowEndsAt, suppressedCount);
  }

  public boolean allowed() {
    return decision == Decision.ALLOW;
  }
}
