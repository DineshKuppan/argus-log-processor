package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ByteRateLimiterTest {

  @Test
  void bytesPassAtTheConfiguredAverageRate() throws InterruptedException {
    ByteRateLimiter limiter = new ByteRateLimiter(1_000_000);
    long start = System.nanoTime();

    // The first call passes at once; each later one waits for the bytes before it.
    for (int i = 0; i < 4; i++) {
      assertTrue(limiter.acquire(100_000, () -> false));
    }

    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(elapsedMs >= 290, "4 x 100KB at 1MB/s took " + elapsedMs + "ms");
    assertTrue(elapsedMs < 2_000, "took " + elapsedMs + "ms");
  }

  @Test
  void idleTimeSavesUpNoCredit() throws InterruptedException {
    long[] now = {0};
    ByteRateLimiter limiter = new ByteRateLimiter(1_000, () -> now[0]);
    limiter.acquire(1_000, () -> false);

    now[0] = TimeUnit.SECONDS.toNanos(100); // idle for 100s
    assertTrue(limiter.acquire(1_000, () -> false)); // passes at once
    AtomicBoolean waited = new AtomicBoolean();
    // The next 1KB is a full second away, not covered by the idle time.
    assertFalse(limiter.acquire(1, () -> waited.getAndSet(true)));
    assertTrue(waited.get());
  }

  @Test
  void aStopRequestEndsTheWait() throws InterruptedException {
    ByteRateLimiter limiter = new ByteRateLimiter(1);
    limiter.acquire(3_600, () -> false); // the next byte may pass in an hour
    long start = System.nanoTime();

    assertFalse(limiter.acquire(1, () -> System.nanoTime() - start > 50_000_000));

    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
  }

  @Test
  void theRateMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> new ByteRateLimiter(0));
  }
}
