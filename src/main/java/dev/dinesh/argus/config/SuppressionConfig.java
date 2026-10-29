package dev.dinesh.argus.config;

import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.suppression.LocalSuppressionStore;
import dev.dinesh.argus.suppression.MessageFingerprint;
import dev.dinesh.argus.suppression.RedisSuppressionStore;
import dev.dinesh.argus.suppression.SuppressionStore;
import dev.dinesh.argus.suppression.Suppressor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Wires the suppression store chosen by {@code argus.suppression.store}. */
@Configuration
public class SuppressionConfig {

  @Bean
  SuppressionStore suppressionStore(
      SuppressionProperties props, ObjectProvider<StringRedisTemplate> redis) {
    return switch (props.store()) {
      case LOCAL -> new LocalSuppressionStore(props.window(), props.maxKeys());
      case REDIS -> new RedisSuppressionStore(redis.getObject(), props.window());
    };
  }

  @Bean
  Suppressor suppressor(
      SuppressionStore store, SuppressionProperties props, ObjectProvider<ArgusMetrics> metrics) {
    return new Suppressor(
        store,
        new MessageFingerprint(props.templateFingerprint()),
        metrics.getIfAvailable(ArgusMetrics::noop));
  }
}
