package dev.dinesh.argus.config;

import org.springframework.boot.actuate.data.redis.RedisHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Reports Redis in {@code /actuator/health} only when a Redis store uses it. Boot's own Redis
 * indicator is off ({@code management.health.redis.enabled=false}): with the local stores, an
 * unreachable Redis made the whole service DOWN although nothing needed it.
 */
@Configuration
@ConditionalOnExpression(
    "'${argus.suppression.store:local}'.equalsIgnoreCase('redis')"
        + " || '${argus.quota.store:local}'.equalsIgnoreCase('redis')")
public class RedisHealthConfig {

  @Bean
  RedisHealthIndicator redisHealthIndicator(RedisConnectionFactory connectionFactory) {
    return new RedisHealthIndicator(connectionFactory);
  }
}
