package dev.dinesh.argus.quota;

import dev.dinesh.argus.config.QuotaProperties;
import dev.dinesh.argus.tenant.TenantIds;

/**
 * Per-tenant quotas (P3 D5). Today one: decompressed bytes pushed per second, with a burst of
 * {@code burst-seconds} at that rate. Bucket keys start with the tenant (guardrail 4).
 */
public class QuotaService {

  private final QuotaStore store;
  private final QuotaProperties props;

  public QuotaService(QuotaStore store, QuotaProperties props) {
    this.store = store;
    this.props = props;
  }

  /** Takes {@code bytes} from the tenant's ingest bucket. */
  public QuotaDecision takeIngestBytes(String tenantId, long bytes) {
    long rate = props.ingestBytesPerSecond(TenantIds.requireValid(tenantId));
    long capacity = Math.max(1, Math.round(rate * props.burstSeconds()));
    return store.tryTake(tenantId + ":quota:ingest", bytes, rate, capacity);
  }
}
