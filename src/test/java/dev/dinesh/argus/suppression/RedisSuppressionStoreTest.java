package dev.dinesh.argus.suppression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.suppression.SuppressionDecision.Decision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The Redis store against a real Redis; skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class RedisSuppressionStoreTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;

  @BeforeAll
  static void connect() {
    factory = RedisTestSupport.factory(REDIS.getHost(), REDIS.getMappedPort(6379));
    redis = new StringRedisTemplate(factory);
  }

  @AfterAll
  static void disconnect() {
    factory.destroy();
  }

  private static String key() {
    return "test:supp:" + System.nanoTime();
  }

  @Test
  void allowsOnceThenDeniesAndCountsUntilTheWindowEnds() throws Exception {
    RedisSuppressionStore store = new RedisSuppressionStore(redis, Duration.ofMillis(500));
    String key = key();

    SuppressionDecision first = store.acquire(key, "m");
    SuppressionDecision second = store.acquire(key, "m");
    SuppressionDecision third = store.acquire(key, "m");

    assertTrue(first.allowed());
    assertEquals(Decision.DENY, second.decision());
    assertEquals(1, second.suppressedCount());
    assertEquals(2, third.suppressedCount());
    assertFalse(third.windowEndsAt().isAfter(first.windowEndsAt().plusMillis(50)));
    assertTrue(third.windowEndsAt().isAfter(Instant.now()));
    Thread.sleep(600);
    assertTrue(store.acquire(key, "m").allowed());
    assertEquals(0, store.failOpenCount());
  }

  @Test
  void aBatchIsDecidedInOrderInOneRoundTrip() {
    RedisSuppressionStore store = new RedisSuppressionStore(redis, Duration.ofSeconds(10));
    String a = key();
    String b = key() + "b";
    store.acquire(b, "b"); // b's window is already open

    List<SuppressionDecision> decisions =
        store.acquireAll(List.of(a, a, b, a), List.of("a", "a", "b", "a"));

    assertEquals(
        List.of(Decision.ALLOW, Decision.DENY, Decision.DENY, Decision.DENY),
        decisions.stream().map(SuppressionDecision::decision).toList());
    assertEquals(2, decisions.get(3).suppressedCount());
    assertEquals(0, store.failOpenCount());
    // Redis' own script cache was flushed: the batch loads the script again.
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Object>)
            c -> {
              c.scriptingCommands().scriptFlush();
              return null;
            });
    assertEquals(
        Decision.ALLOW, store.acquireAll(List.of(key() + "c"), List.of("c")).get(0).decision());
  }

  @Test
  void exactlyOneOfManyConcurrentIdenticalCallsIsAllowed() throws Exception {
    RedisSuppressionStore store = new RedisSuppressionStore(redis, Duration.ofSeconds(10));
    String key = key();
    int threads = 16;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<SuppressionDecision>> calls =
          IntStream.range(0, threads)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return store.acquire(key, "m");
                          }))
              .toList();
      start.countDown();
      long allowed = 0;
      for (Future<SuppressionDecision> call : calls) {
        allowed += call.get().allowed() ? 1 : 0;
      }
      assertEquals(1, allowed);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aKeyWithoutATtlGetsOneInsteadOfDenyingForever() {
    RedisSuppressionStore store = new RedisSuppressionStore(redis, Duration.ofSeconds(10));
    String key = key();
    redis.opsForValue().set(key, "0");

    assertEquals(Decision.DENY, store.acquire(key, "m").decision());
    assertTrue(redis.getExpire(key) > 0);
  }
}
