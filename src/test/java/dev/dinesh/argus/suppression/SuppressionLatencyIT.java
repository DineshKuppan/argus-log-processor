package dev.dinesh.argus.suppression;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T6: p99 decision latency under 1ms with the local store, on a mix of repeated and unique
 * messages. Excluded from {@code mvn test}; run with {@code mvn test -Dgroups=load
 * -DexcludedGroups=none}.
 */
@Tag("load")
class SuppressionLatencyIT {

  private static final int WARMUP = 200_000;
  private static final int MEASURED = 1_000_000;
  private static final long P99_LIMIT_NANOS = 1_000_000;

  @Test
  void p99DecisionIsUnderOneMillisecond() {
    Suppressor suppressor =
        new Suppressor(
            new LocalSuppressionStore(Duration.ofSeconds(10), 100_000),
            new MessageFingerprint(false));
    for (int i = 0; i < WARMUP; i++) {
      suppressor.check("acme", message(i));
    }

    long[] nanos = new long[MEASURED];
    for (int i = 0; i < MEASURED; i++) {
      String message = message(WARMUP + i);
      long start = System.nanoTime();
      suppressor.check("acme", message);
      nanos[i] = System.nanoTime() - start;
    }
    Arrays.sort(nanos);
    long p50 = nanos[MEASURED / 2];
    long p99 = nanos[(int) (MEASURED * 0.99)];
    long p999 = nanos[(int) (MEASURED * 0.999)];
    System.out.printf(
        "RESULT suppression decisions=%d p50Us=%.1f p99Us=%.1f p999Us=%.1f%n",
        MEASURED, p50 / 1e3, p99 / 1e3, p999 / 1e3);

    assertTrue(p99 < P99_LIMIT_NANOS, "p99 " + p99 / 1e3 + "us");
  }

  /** Half the calls repeat one of 1,000 hot messages; the rest are unique (cache misses). */
  private static String message(int i) {
    return i % 2 == 0
        ? "GET /api/orders/" + (i % 1_000) + " failed: upstream timeout after 3000ms"
        : "request " + i + " from 10.0.0." + (i % 255) + " completed in " + (i % 900) + "ms";
  }
}
