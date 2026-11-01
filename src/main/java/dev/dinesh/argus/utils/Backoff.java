package dev.dinesh.argus.utils;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** The retry wait every background worker uses (P2 D4): exponential, capped, jittered. */
public final class Backoff {

  private static final int MAX_DOUBLINGS = 30;

  private Backoff() {}

  /**
   * {@code initial × 2^(failures-1)}, capped at {@code max}, then a random wait between 50% and
   * 100% of that, so workers that failed together don't retry together.
   */
  public static Duration jittered(Duration initial, Duration max, int failures) {
    long first = initial.toMillis();
    long cap = max.toMillis();
    int doublings = Math.min(Math.max(failures - 1, 0), MAX_DOUBLINGS);
    long base = first > (cap >> doublings) ? cap : Math.min(cap, first << doublings);
    long half = Math.max(base / 2, 1);
    return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
  }
}
