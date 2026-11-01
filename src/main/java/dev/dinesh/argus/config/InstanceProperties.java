package dev.dinesh.argus.config;

import dev.dinesh.argus.tenant.TenantIds;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * This instance's identity among several ({@code P6_PLAN.md} D8).
 *
 * @param id joins every source's identity ({@code eventId}, archive keys, the Parquet {@code
 *     source_instance} column, the hot tier's field), so two instances ingesting the same source id
 *     never overwrite each other's lines. Blank (the default) keeps single-instance identities
 *     unchanged. In Kubernetes, the StatefulSet pod name; stable across restarts
 */
@ConfigurationProperties("argus.instance")
public record InstanceProperties(String id) {

  public InstanceProperties {
    if (id == null || id.isBlank()) {
      id = null;
    } else {
      try {
        // Safe in paths and keys, like a tenant id.
        id = TenantIds.requireValid(id.strip());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "argus.instance.id: 1-64 of A-Z a-z 0-9 . _ -, not starting with '.': " + id, e);
      }
    }
  }

  /** No instance id: a single instance. */
  public static final InstanceProperties NONE = new InstanceProperties(null);

  public boolean isSet() {
    return id != null;
  }
}
