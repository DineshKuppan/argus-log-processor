package dev.dinesh.argus.config;

import dev.dinesh.argus.quota.LocalQuotaStore;
import dev.dinesh.argus.quota.QuotaService;
import dev.dinesh.argus.quota.QuotaStore;
import dev.dinesh.argus.quota.RedisQuotaStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Wires the quota store chosen by {@code argus.quota.store}. */
@Configuration
public class QuotaConfig {

  @Bean
  QuotaStore quotaStore(QuotaProperties props, ObjectProvider<StringRedisTemplate> redis) {
    return switch (props.store()) {
      case LOCAL -> new LocalQuotaStore();
      case REDIS -> new RedisQuotaStore(redis.getObject());
    };
  }

  @Bean
  QuotaService quotaService(QuotaStore store, QuotaProperties props) {
    return new QuotaService(store, props);
  }
}
