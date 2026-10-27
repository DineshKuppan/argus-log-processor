package dev.dinesh.argus.config;

import dev.dinesh.argus.tenant.ApiKeyTenantResolver.ApiKey;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How requests are mapped to tenants.
 *
 * @param apiKeys {@code tenant} and {@code key-sha256} (hex SHA-256 of the key, e.g. {@code printf
 *     %s "$KEY" | shasum -a 256}) per key
 */
@ConfigurationProperties("argus.tenancy")
public record TenancyProperties(Mode mode, List<ApiKey> apiKeys) {

  public enum Mode {
    /** Tenants from API keys (default). */
    API_KEY,
    /** Local development only: a caller-chosen {@code X-Argus-Tenant} header. */
    HEADER
  }

  public TenancyProperties {
    mode = mode != null ? mode : Mode.API_KEY;
    apiKeys = apiKeys != null ? List.copyOf(apiKeys) : List.of();
  }
}
