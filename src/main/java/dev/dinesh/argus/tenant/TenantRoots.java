package dev.dinesh.argus.tenant;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.TenancyProperties;
import dev.dinesh.argus.source.AllowedPaths;
import dev.dinesh.argus.tenant.ApiKeyTenantResolver.ApiKey;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Which directories each tenant may read files from (v1 and v2 alike). A key's own {@code
 * allowed-roots} isolate its tenant's input; tenants without them share {@code
 * argus.ingest.allowed-roots}, which means they can read each other's files there.
 */
@Slf4j
@Component
public class TenantRoots {

  private final AllowedPaths shared;
  private final Map<String, AllowedPaths> byTenant = new HashMap<>();

  public TenantRoots(IngestProperties ingest, TenancyProperties tenancy) {
    this.shared = new AllowedPaths(ingest.allowedRoots());
    TreeSet<String> sharing = new TreeSet<>();
    for (ApiKey key : tenancy.apiKeys()) {
      if (key.allowedRoots() == null || key.allowedRoots().isEmpty()) {
        sharing.add(key.tenant());
      } else {
        byTenant.put(key.tenant(), new AllowedPaths(key.allowedRoots()));
      }
    }
    if (sharing.size() > 1) {
      log.warn(
          "Tenants {} share argus.ingest.allowed-roots and can read each other's input files;"
              + " set argus.tenancy.api-keys[i].allowed-roots to isolate them",
          sharing);
    }
  }

  /** Roots for one tenant: its own if configured, else the shared ones. */
  public AllowedPaths forTenant(String tenantId) {
    return byTenant.getOrDefault(tenantId, shared);
  }

  /** Every tenant reads the same roots (tests, single-tenant setups). */
  public static TenantRoots sharedOnly(List<Path> roots) {
    return new TenantRoots(
        new IngestProperties(0, null, null, 0, 0, null, 0, roots, null, null),
        new TenancyProperties(null, List.of()));
  }
}
