package dev.dinesh.argus.suppression;

import java.time.Duration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

final class RedisTestSupport {

  private RedisTestSupport() {}

  /** A started connection factory with the same short timeout as application.properties. */
  static LettuceConnectionFactory factory(String host, int port) {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(host, port),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(100)).build());
    factory.afterPropertiesSet();
    factory.start();
    return factory;
  }
}
