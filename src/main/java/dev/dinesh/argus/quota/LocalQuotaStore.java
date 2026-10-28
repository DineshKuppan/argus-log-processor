package dev.dinesh.argus.quota;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Token buckets in memory, for one node. Each bucket is refilled lazily when it is checked, so idle
 * tenants cost nothing. Bounded: at most {@link #MAX_BUCKETS}, and a bucket unused for an hour is
 * dropped (it would be full again by then at any sensible rate).
 */
public final class LocalQuotaStore implements QuotaStore {

  static final int MAX_BUCKETS = 10_000;
  private static final Duration IDLE_EXPIRY = Duration.ofHours(1);
  private static final double NANOS_PER_SECOND = 1e9;

  private final Cache<String, Bucket> buckets;
  private final LongSupplier nanoTime;

  public LocalQuotaStore() {
    this(System::nanoTime);
  }

  LocalQuotaStore(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
    this.buckets =
        Caffeine.newBuilder().maximumSize(MAX_BUCKETS).expireAfterAccess(IDLE_EXPIRY).build();
  }

  @Override
  public QuotaDecision tryTake(String key, long amount, long ratePerSecond, long capacity) {
    long now = nanoTime.getAsLong();
    Bucket bucket = buckets.get(key, k -> new Bucket(capacity, now));
    return bucket.tryTake(amount, ratePerSecond, capacity, now);
  }

  private static final class Bucket {
    private double tokens;
    private long refilledAt;

    Bucket(long capacity, long now) {
      this.tokens = capacity;
      this.refilledAt = now;
    }

    synchronized QuotaDecision tryTake(long amount, long rate, long capacity, long now) {
      if (now > refilledAt) {
        tokens = Math.min(capacity, tokens + (now - refilledAt) * rate / NANOS_PER_SECOND);
        refilledAt = now;
      }
      double needed = Math.min(amount, capacity);
      if (tokens >= needed) {
        tokens -= amount;
        return QuotaDecision.ALLOW;
      }
      long waitNanos = (long) Math.ceil((needed - tokens) * NANOS_PER_SECOND / rate);
      return QuotaDecision.deny(Duration.ofNanos(waitNanos));
    }
  }
}
