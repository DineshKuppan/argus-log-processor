package dev.dinesh.argus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * WAL limits (P2 D7), applied only when an archive store drains the WAL.
 *
 * @param maxBytes pending (not yet archived) WAL bytes the watermarks are fractions of; 30GB keeps
 *     the WAL inside the 100GB disk budget
 * @param highWatermark above this fraction, new segments wait and new ingest jobs get 429
 * @param lowWatermark below this fraction, waiting segments resume; the gap stops the gate flapping
 */
@ConfigurationProperties("argus.wal")
public record WalProperties(DataSize maxBytes, Double highWatermark, Double lowWatermark) {

  public WalProperties {
    maxBytes = maxBytes != null ? maxBytes : DataSize.ofGigabytes(30);
    highWatermark = highWatermark != null ? highWatermark : 0.8;
    lowWatermark = lowWatermark != null ? lowWatermark : 0.6;
    if (maxBytes.toBytes() <= 0) {
      throw new IllegalArgumentException("argus.wal.max-bytes must be positive: " + maxBytes);
    }
    if (!(lowWatermark > 0 && lowWatermark < highWatermark && highWatermark <= 1)) {
      throw new IllegalArgumentException(
          "argus.wal watermarks need 0 < low-watermark < high-watermark <= 1, not "
              + lowWatermark
              + " / "
              + highWatermark);
    }
  }

  public long highBytes() {
    return (long) (maxBytes.toBytes() * highWatermark);
  }

  public long lowBytes() {
    return (long) (maxBytes.toBytes() * lowWatermark);
  }
}
