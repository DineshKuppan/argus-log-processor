package dev.dinesh.argus.config;

import dev.dinesh.argus.tenant.TenantIds;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * This instance's identity among several ({@code P6_PLAN.md} D8).
 *
 * @param id joins every source's identity ({@code eventId}, archive keys, the Parquet {@code
 *     source_instance} column, the hot tier's field), so two instances ingesting the same source id
 *     never overwrite each other's lines. Blank (the default) keeps single-instance identities
 *     unchanged. In Kubernetes, the StatefulSet pod name; stable across restarts
 * @param drainTimeout on shutdown, how long to wait for pushes in progress and then for the spools
 *     to be ingested (D12); keep it under the pod's {@code terminationGracePeriodSeconds}
 */
@ConfigurationProperties("argus.instance")
public record InstanceProperties(String id, Duration drainTimeout) {

  public InstanceProperties(String id) {
    this(id, null);
  }

  @ConstructorBinding
  public InstanceProperties {
    drainTimeout = drainTimeout != null ? drainTimeout : Duration.ofSeconds(90);
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
  public static final InstanceProperties NONE = new InstanceProperties(null, null);

  public boolean isSet() {
    return id != null;
  }
}
