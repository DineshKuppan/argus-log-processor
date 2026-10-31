package dev.dinesh.argus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

/**
 * The WAL: where it is, and its limits (P2 D7), applied only when a sink drains it.
 *
 * @param maxBytes pending (not yet archived) WAL bytes the watermarks are fractions of; 30GB keeps
 *     the WAL inside the 100GB disk budget
 * @param highWatermark above this fraction, new segments wait and new ingest jobs get 429
 * @param lowWatermark below this fraction, waiting segments resume; the gap stops the gate flapping
 * @param store {@code local} (the committed segments on {@code argus.sink.local.root}, P2) or
 *     {@code kafka} (a topic, {@code P6_PLAN.md} D1; the watermarks are then fractions of {@code
 *     argus.kafka.max-lag-records})
 */
@ConfigurationProperties("argus.wal")
public record WalProperties(
    DataSize maxBytes, Double highWatermark, Double lowWatermark, Store store) {

  public enum Store {
    LOCAL,
    KAFKA
  }

  /** A local WAL. */
  public WalProperties(DataSize maxBytes, Double highWatermark, Double lowWatermark) {
    this(maxBytes, highWatermark, lowWatermark, null);
  }

  @ConstructorBinding
  public WalProperties {
    store = store != null ? store : Store.LOCAL;
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
