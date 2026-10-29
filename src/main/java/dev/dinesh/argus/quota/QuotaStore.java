package dev.dinesh.argus.quota;

/**
 * One token bucket per key, refilled continuously at {@code ratePerSecond} up to {@code capacity}.
 * A request is allowed when the bucket holds at least {@code min(amount, capacity)} tokens, and
 * then takes all {@code amount}: a request larger than the burst still fits a full bucket and
 * leaves it in debt, rather than being refused forever. Implementations must be thread-safe and
 * atomic, and must fail open (allow) when their backing store is unavailable, as suppression does.
 */
public interface QuotaStore {

  /**
   * @param key tenant-prefixed bucket key (see {@link QuotaService})
   */
  QuotaDecision tryTake(String key, long amount, long ratePerSecond, long capacity);
}
