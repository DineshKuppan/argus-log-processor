package dev.dinesh.argus.suppression;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Suppression windows shared by every instance, in Redis. One Lua call per decision: {@code SET key
 * 0 NX PX window} opens a window (ALLOW); otherwise {@code INCR} counts the copy (keeping the TTL)
 * and {@code PTTL} says when the window ends (DENY). Any Redis error fails open (guardrail 8).
 * Summaries on window end need keyspace notifications and are left to P4.
 */
@Slf4j
public final class RedisSuppressionStore implements SuppressionStore {

  // Returns {allowed (1/0), remaining ms, denied count}. A key without a TTL (written by something
  // else) gets one, so it can't deny forever.
  private static final String ACQUIRE =
      """
      if redis.call('SET', KEYS[1], '0', 'NX', 'PX', ARGV[1]) then
        return {1, tonumber(ARGV[1]), 0}
      end
      local n = redis.call('INCR', KEYS[1])
      local ttl = redis.call('PTTL', KEYS[1])
      if ttl < 0 then
        redis.call('PEXPIRE', KEYS[1], ARGV[1])
        ttl = tonumber(ARGV[1])
      end
      return {0, ttl, n}
      """;

  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> SCRIPT = RedisScript.of(ACQUIRE, List.class);

  private final StringRedisTemplate redis;
  private final Duration window;
  private final Clock clock;
  private final AtomicLong failOpens = new AtomicLong();
  private final AtomicLong lastWarnNanos;

  public RedisSuppressionStore(StringRedisTemplate redis, Duration window) {
    this(redis, window, Clock.systemUTC());
  }

  RedisSuppressionStore(StringRedisTemplate redis, Duration window, Clock clock) {
    this.redis = redis;
    this.window = window;
    this.clock = clock;
    this.lastWarnNanos = new AtomicLong(System.nanoTime() - window.toNanos());
  }

  @Override
  public SuppressionDecision acquire(String key, String message) {
    List<?> reply;
    try {
      reply = redis.execute(SCRIPT, List.of(key), Long.toString(window.toMillis()));
    } catch (RuntimeException e) {
      return failOpen(e);
    }
    if (reply == null || reply.size() != 3) {
      return failOpen(new IllegalStateException("Unexpected Redis reply: " + reply));
    }
    // Read the clock after the reply, so the round trip makes windowEndsAt late rather than early:
    // a
    // client that retries at windowEndsAt is then allowed.
    Instant endsAt = clock.instant().plusMillis(((Number) reply.get(1)).longValue());
    return ((Number) reply.get(0)).longValue() == 1
        ? SuppressionDecision.allow(endsAt)
        : SuppressionDecision.deny(endsAt, ((Number) reply.get(2)).longValue());
  }

  /**
   * One pipelined round trip: the script is loaded (idempotent), then run with {@code EVALSHA} once
   * per key, in order, so a key twice in the batch is denied the second time. Any error fails the
   * whole batch open.
   */
  @Override
  public List<SuppressionDecision> acquireAll(List<String> keys, List<String> messages) {
    if (keys.isEmpty()) {
      return List.of();
    }
    byte[] script = ACQUIRE.getBytes(StandardCharsets.UTF_8);
    byte[] windowMillis = Long.toString(window.toMillis()).getBytes(StandardCharsets.UTF_8);
    String sha = SCRIPT.getSha1();
    List<Object> replies;
    try {
      replies =
          redis.executePipelined(
              (RedisCallback<Object>)
                  connection -> {
                    connection.scriptingCommands().scriptLoad(script);
                    for (String key : keys) {
                      connection
                          .scriptingCommands()
                          .evalSha(
                              sha,
                              ReturnType.MULTI,
                              1,
                              key.getBytes(StandardCharsets.UTF_8),
                              windowMillis);
                    }
                    return null;
                  });
    } catch (RuntimeException e) {
      return failOpenAll(keys.size(), e);
    }
    if (replies == null || replies.size() != keys.size() + 1) {
      return failOpenAll(
          keys.size(), new IllegalStateException("Unexpected Redis pipeline reply: " + replies));
    }
    Instant now = clock.instant();
    List<SuppressionDecision> decisions = new ArrayList<>(keys.size());
    for (int i = 0; i < keys.size(); i++) {
      if (!(replies.get(i + 1) instanceof List<?> reply) || reply.size() != 3) {
        decisions.add(
            failOpen(new IllegalStateException("Unexpected Redis reply: " + replies.get(i + 1))));
        continue;
      }
      Instant endsAt = now.plusMillis(((Number) reply.get(1)).longValue());
      decisions.add(
          ((Number) reply.get(0)).longValue() == 1
              ? SuppressionDecision.allow(endsAt)
              : SuppressionDecision.deny(endsAt, ((Number) reply.get(2)).longValue()));
    }
    return decisions;
  }

  private List<SuppressionDecision> failOpenAll(int count, RuntimeException e) {
    List<SuppressionDecision> decisions = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      decisions.add(failOpen(e));
    }
    return decisions;
  }

  @Override
  public void release(String key) {
    try {
      redis.delete(key);
    } catch (RuntimeException e) {
      // The window then ends at its TTL: a retry within it is suppressed, as without release.
      log.warn(
          "Suppression store unavailable, window not released key={} error={}", key, e.toString());
    }
  }

  /** Decisions answered ALLOW because Redis failed. */
  public long failOpenCount() {
    return failOpens.get();
  }

  private SuppressionDecision failOpen(RuntimeException e) {
    long count = failOpens.incrementAndGet();
    // At most one warning per window, so an outage at thousands of calls/s doesn't flood the log.
    long nowNanos = System.nanoTime();
    long last = lastWarnNanos.get();
    if (nowNanos - last >= window.toNanos() && lastWarnNanos.compareAndSet(last, nowNanos)) {
      log.warn(
          "Suppression store unavailable, allowing (fail open) failOpens={} error={}",
          count,
          e.toString());
    }
    return SuppressionDecision.allow(clock.instant().plus(window));
  }
}
