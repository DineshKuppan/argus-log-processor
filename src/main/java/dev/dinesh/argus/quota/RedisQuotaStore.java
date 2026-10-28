package dev.dinesh.argus.quota;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Token buckets shared by every instance, in Redis: one Lua call per request refills the bucket
 * from Redis's own clock ({@code TIME}, so instances' clocks don't matter) and takes the tokens
 * atomically. A bucket expires once it would be full again. Any Redis error fails open (allows),
 * with at most one warning per {@link #WARN_INTERVAL}.
 */
@Slf4j
public final class RedisQuotaStore implements QuotaStore {

  static final Duration WARN_INTERVAL = Duration.ofSeconds(10);

  // ARGV: capacity, rate per second, amount, expiry ms. Returns {allowed (1/0), wait ms}.
  private static final String TAKE =
      """
      local capacity = tonumber(ARGV[1])
      local rate = tonumber(ARGV[2])
      local amount = tonumber(ARGV[3])
      local t = redis.call('TIME')
      local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
      local b = redis.call('HMGET', KEYS[1], 'tokens', 'at')
      local tokens = tonumber(b[1])
      local at = tonumber(b[2])
      if tokens == nil or at == nil then
        tokens = capacity
        at = now
      end
      if now > at then
        tokens = math.min(capacity, tokens + (now - at) * rate / 1000000)
        at = now
      end
      local needed = math.min(amount, capacity)
      local allowed = 0
      local wait = 0
      if tokens >= needed then
        tokens = tokens - amount
        allowed = 1
      else
        wait = math.ceil((needed - tokens) * 1000 / rate)
      end
      redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', tostring(at))
      redis.call('PEXPIRE', KEYS[1], ARGV[4])
      return {allowed, wait}
      """;

  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> SCRIPT = RedisScript.of(TAKE, List.class);

  private final StringRedisTemplate redis;
  private final AtomicLong failOpens = new AtomicLong();
  private final AtomicLong lastWarnNanos =
      new AtomicLong(System.nanoTime() - WARN_INTERVAL.toNanos());

  public RedisQuotaStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public QuotaDecision tryTake(String key, long amount, long ratePerSecond, long capacity) {
    // Long enough for a bucket in debt by one maximal request to refill completely.
    long expiryMs = (long) Math.ceil((capacity + amount) * 1000.0 / ratePerSecond) + 1000;
    List<?> reply;
    try {
      reply =
          redis.execute(
              SCRIPT,
              List.of(key),
              Long.toString(capacity),
              Long.toString(ratePerSecond),
              Long.toString(amount),
              Long.toString(expiryMs));
    } catch (RuntimeException e) {
      return failOpen(e);
    }
    if (reply == null || reply.size() != 2) {
      return failOpen(new IllegalStateException("Unexpected Redis reply: " + reply));
    }
    return ((Number) reply.get(0)).longValue() == 1
        ? QuotaDecision.ALLOW
        : QuotaDecision.deny(Duration.ofMillis(((Number) reply.get(1)).longValue()));
  }

  /** Requests allowed because Redis failed. */
  public long failOpenCount() {
    return failOpens.get();
  }

  private QuotaDecision failOpen(RuntimeException e) {
    long count = failOpens.incrementAndGet();
    long now = System.nanoTime();
    long last = lastWarnNanos.get();
    if (now - last >= WARN_INTERVAL.toNanos() && lastWarnNanos.compareAndSet(last, now)) {
      log.warn(
          "Quota store unavailable, allowing (fail open) failOpens={} error={}",
          count,
          e.toString());
    }
    return QuotaDecision.ALLOW;
  }
}
