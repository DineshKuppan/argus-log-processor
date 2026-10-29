package dev.dinesh.argus.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Live tail ({@code P4_PLAN.md} D2–D4).
 *
 * @param maxConnections open tail connections on this node; more get 429
 * @param maxConnectionsPerTenant open tail connections per tenant; more get 429
 * @param maxLinesPerSecond matching lines sent per connection; the rest are reported as dropped
 * @param maxDuration a connection is closed after this; the client reconnects with {@code
 *     Last-Event-ID}
 * @param heartbeat a comment is sent after this long without a write
 * @param ringLines lines kept per tailed tenant
 * @param ringBytes estimated bytes kept per tailed tenant
 * @param ringIdle a tenant's ring is kept this long after its last connection closes, so a
 *     reconnect can resume
 */
@ConfigurationProperties("argus.tail")
public record TailProperties(
    int maxConnections,
    int maxConnectionsPerTenant,
    int maxLinesPerSecond,
    Duration maxDuration,
    Duration heartbeat,
    int ringLines,
    DataSize ringBytes,
    Duration ringIdle) {

  public TailProperties {
    maxConnections = maxConnections > 0 ? maxConnections : 256;
    maxConnectionsPerTenant = maxConnectionsPerTenant > 0 ? maxConnectionsPerTenant : 16;
    maxLinesPerSecond = maxLinesPerSecond > 0 ? maxLinesPerSecond : 200;
    maxDuration = maxDuration != null ? maxDuration : Duration.ofHours(1);
    heartbeat = heartbeat != null ? heartbeat : Duration.ofSeconds(15);
    ringLines = ringLines > 0 ? ringLines : 10_000;
    ringBytes = ringBytes != null ? ringBytes : DataSize.ofMegabytes(4);
    ringIdle = ringIdle != null ? ringIdle : Duration.ofMinutes(1);
    if (maxDuration.isNegative() || maxDuration.isZero()) {
      throw new IllegalArgumentException("argus.tail.max-duration must be positive");
    }
    if (heartbeat.isNegative() || heartbeat.isZero()) {
      throw new IllegalArgumentException("argus.tail.heartbeat must be positive");
    }
    if (ringBytes.toBytes() <= 0) {
      throw new IllegalArgumentException("argus.tail.ring-bytes must be positive");
    }
  }

  public static TailProperties defaults() {
    return new TailProperties(0, 0, 0, null, null, 0, null, null);
  }
}
