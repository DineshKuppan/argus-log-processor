package dev.dinesh.argus.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Per-tenant quotas (P3 D5).
 *
 * @param ingestBytesPerSecond pushed bytes (decompressed) each tenant may send per second; 2MB is
 *     about 16x the 10GB/day average
 * @param burstSeconds the bucket holds this many seconds of the rate
 * @param tenants per-tenant overrides, by tenant id
 */
@ConfigurationProperties("argus.quota")
public record QuotaProperties(
    Store store, DataSize ingestBytesPerSecond, Double burstSeconds, Map<String, Tenant> tenants) {

  public enum Store {
    /** One node, in memory. */
    LOCAL,
    /** Shared by every instance; fails open when Redis is unavailable. */
    REDIS
  }

  /**
   * @param ingestBytesPerSecond this tenant's rate, or null for the default
   */
  public record Tenant(DataSize ingestBytesPerSecond) {}

  public QuotaProperties {
    store = store != null ? store : Store.LOCAL;
    ingestBytesPerSecond =
        ingestBytesPerSecond != null ? ingestBytesPerSecond : DataSize.ofMegabytes(2);
    burstSeconds = burstSeconds != null ? burstSeconds : 2.0;
    tenants = tenants != null ? Map.copyOf(tenants) : Map.of();
    requirePositive("argus.quota.ingest-bytes-per-second", ingestBytesPerSecond);
    if (!(burstSeconds > 0) || burstSeconds.isInfinite()) {
      throw new IllegalArgumentException("argus.quota.burst-seconds must be positive");
    }
    tenants.forEach(
        (id, t) -> {
          if (t.ingestBytesPerSecond() != null) {
            requirePositive(
                "argus.quota.tenants." + id + ".ingest-bytes-per-second", t.ingestBytesPerSecond());
          }
        });
  }

  /** The tenant's ingest rate in bytes per second. */
  public long ingestBytesPerSecond(String tenantId) {
    Tenant t = tenants.get(tenantId);
    return t != null && t.ingestBytesPerSecond() != null
        ? t.ingestBytesPerSecond().toBytes()
        : ingestBytesPerSecond.toBytes();
  }

  private static void requirePositive(String name, DataSize size) {
    if (size.toBytes() <= 0) {
      throw new IllegalArgumentException(name + " must be positive: " + size);
    }
  }
}
