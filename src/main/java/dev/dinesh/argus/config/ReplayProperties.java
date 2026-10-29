package dev.dinesh.argus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The replay lane ({@code P3_PLAN.md} D8): re-reads and exports run here, apart from live ingest.
 *
 * @param maxConcurrentJobs replay jobs (file or export) at once; more get 429
 * @param bytesPerSecond read budget shared by every replay job
 * @param maxPendingBytes an export pauses while its stream holds this much not yet ingested
 */
@ConfigurationProperties("argus.replay")
public record ReplayProperties(
    int maxConcurrentJobs, DataSize bytesPerSecond, DataSize maxPendingBytes) {

  public ReplayProperties {
    maxConcurrentJobs = maxConcurrentJobs > 0 ? maxConcurrentJobs : 1;
    bytesPerSecond = bytesPerSecond != null ? bytesPerSecond : DataSize.ofMegabytes(16);
    maxPendingBytes = maxPendingBytes != null ? maxPendingBytes : DataSize.ofMegabytes(256);
    if (bytesPerSecond.toBytes() <= 0) {
      throw new IllegalArgumentException("argus.replay.bytes-per-second must be positive");
    }
    if (maxPendingBytes.toBytes() <= 0) {
      throw new IllegalArgumentException("argus.replay.max-pending-bytes must be positive");
    }
  }

  public static ReplayProperties defaults() {
    return new ReplayProperties(0, null, null);
  }
}
