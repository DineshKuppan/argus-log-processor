package dev.dinesh.argus.config;

import dev.dinesh.argus.tenant.ApiKeyTenantResolver;
import dev.dinesh.argus.tenant.CurrentTenantArgumentResolver;
import dev.dinesh.argus.tenant.HeaderTenantResolver;
import dev.dinesh.argus.tenant.TenantResolver;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Picks the tenant resolver from {@code argus.tenancy.mode} and wires {@code @CurrentTenant}. */
@Slf4j
@Configuration
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyConfig implements WebMvcConfigurer {

  private final TenantResolver resolver;

  public TenancyConfig(TenancyProperties props) {
    this.resolver =
        switch (props.mode()) {
          case API_KEY -> apiKeys(props);
          case HEADER -> {
            log.warn(
                "argus.tenancy.mode=header: tenants come from the caller-chosen {} header."
                    + " Local development only",
                HeaderTenantResolver.TENANT_HEADER);
            yield new HeaderTenantResolver();
          }
        };
  }

  private static TenantResolver apiKeys(TenancyProperties props) {
    ApiKeyTenantResolver resolver = new ApiKeyTenantResolver(props.apiKeys());
    if (resolver.keyCount() == 0) {
      log.warn("No API keys configured (argus.tenancy.api-keys): every v2 request will get 401");
    }
    return resolver;
  }

  @Bean
  TenantResolver tenantResolver() {
    return resolver;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CurrentTenantArgumentResolver(resolver));
  }
}
