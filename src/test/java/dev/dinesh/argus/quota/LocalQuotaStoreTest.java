package dev.dinesh.argus.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LocalQuotaStoreTest {

  private static final long RATE = 1000;
  private static final long CAPACITY = 2000;

  private final AtomicLong nanos = new AtomicLong();
  private final LocalQuotaStore store = new LocalQuotaStore(nanos::get);

  private QuotaDecision take(String key, long amount) {
    return store.tryTake(key, amount, RATE, CAPACITY);
  }

  private void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  @Test
  void aFullBucketAllowsTheBurstThenDeniesWithTheExactWait() {
    assertTrue(take("a", 1500).allowed());
    assertTrue(take("a", 500).allowed());

    QuotaDecision denied = take("a", 250);

    assertFalse(denied.allowed());
    assertEquals(Duration.ofMillis(250), denied.retryAfter());
    assertEquals(1, denied.retryAfterSeconds());
  }

  @Test
  void theBucketRefillsAtTheRateUpToItsCapacity() {
    take("a", 2000);
    advance(Duration.ofMillis(249));
    assertFalse(take("a", 250).allowed());

    advance(Duration.ofMillis(1));
    assertTrue(take("a", 250).allowed());

    advance(Duration.ofHours(1));
    assertTrue(take("a", 2000).allowed(), "refilled to capacity");
    assertFalse(take("a", 1).allowed(), "never above capacity");
  }

  @Test
  void aRequestLargerThanTheBurstFitsAFullBucketAndLeavesItInDebt() {
    assertTrue(take("a", 5000).allowed());

    QuotaDecision denied = take("a", 100);

    // 3000 of debt plus the 100 asked for, at 1000/s.
    assertEquals(Duration.ofMillis(3100), denied.retryAfter());
    assertEquals(4, denied.retryAfterSeconds());
  }

  @Test
  void aHighRateRefillsCorrectlyAfterAnIdleGap() {
    long gigabyte = 1L << 30;
    assertTrue(store.tryTake("a", 2 * gigabyte, gigabyte, 2 * gigabyte).allowed());

    // 10s x 1GB/s is 1e19 nanos-bytes: past Long.MAX_VALUE if computed in a long.
    advance(Duration.ofSeconds(10));

    assertTrue(store.tryTake("a", 2 * gigabyte, gigabyte, 2 * gigabyte).allowed());
  }

  @Test
  void bucketsAreIndependentPerKey() {
    take("acme:quota:ingest", 2000);

    assertFalse(take("acme:quota:ingest", 1).allowed());
    assertTrue(take("globex:quota:ingest", 2000).allowed());
  }
}
