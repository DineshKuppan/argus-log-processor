package dev.dinesh.argus.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** The Redis store against a real Redis; skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class RedisQuotaStoreTest {

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static StringRedisTemplate redis;

  @BeforeAll
  static void connect() {
    factory = factory(REDIS.getHost(), REDIS.getMappedPort(6379));
    redis = new StringRedisTemplate(factory);
  }

  @AfterAll
  static void disconnect() {
    factory.destroy();
  }

  static LettuceConnectionFactory factory(String host, int port) {
    LettuceConnectionFactory f =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(host, port),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(100)).build());
    f.afterPropertiesSet();
    f.start();
    return f;
  }

  private static String key() {
    return "t" + System.nanoTime() + ":quota:ingest";
  }

  @Test
  void allowsTheBurstThenDeniesWithAWaitAndExpiresTheBucket() {
    RedisQuotaStore store = new RedisQuotaStore(redis);
    String key = key();
    // 1 byte/s, so refill during the test is negligible.
    assertTrue(store.tryTake(key, 6, 1, 10).allowed());
    assertTrue(store.tryTake(key, 4, 1, 10).allowed());

    QuotaDecision denied = store.tryTake(key, 2, 1, 10);

    assertFalse(denied.allowed());
    long waitMs = denied.retryAfter().toMillis();
    assertTrue(waitMs > 1000 && waitMs <= 2000, "wait " + waitMs);
    long ttl = redis.getExpire(key);
    assertTrue(ttl > 0 && ttl <= 13, "ttl " + ttl);
    assertEquals(0, store.failOpenCount());
  }

  @Test
  void aRequestLargerThanTheBurstFitsAFullBucket() {
    RedisQuotaStore store = new RedisQuotaStore(redis);
    String key = key();

    assertTrue(store.tryTake(key, 50, 10, 20).allowed());

    QuotaDecision denied = store.tryTake(key, 5, 10, 20);
    assertFalse(denied.allowed());
    assertTrue(denied.retryAfter().toMillis() > 3000, "debt of 30 + 5 at 10/s");
  }

  @Test
  void bucketsAreIndependentPerKey() {
    RedisQuotaStore store = new RedisQuotaStore(redis);
    String a = key();
    String b = key() + "b";
    store.tryTake(a, 10, 1, 10);

    assertFalse(store.tryTake(a, 1, 1, 10).allowed());
    assertTrue(store.tryTake(b, 10, 1, 10).allowed());
  }

  @Test
  void allowsEverythingWhenRedisIsUnreachable() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    LettuceConnectionFactory down = factory("127.0.0.1", closedPort);
    try {
      RedisQuotaStore store = new RedisQuotaStore(new StringRedisTemplate(down));

      assertTrue(store.tryTake("acme:quota:ingest", 100, 1, 1).allowed());
      assertTrue(store.tryTake("acme:quota:ingest", 100, 1, 1).allowed());
      assertEquals(2, store.failOpenCount());
    } finally {
      down.destroy();
    }
  }
}
