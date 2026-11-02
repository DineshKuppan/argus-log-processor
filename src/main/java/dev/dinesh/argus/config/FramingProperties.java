package dev.dinesh.argus.config;

import dev.dinesh.argus.framing.FramingSpec;
import dev.dinesh.argus.framing.RecordFramer;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

/**
 * How lines are joined into records ({@code P7_PLAN.md} D2–D4).
 *
 * @param mode framing for file jobs and streams that don't request one: {@code auto}, {@code none}
 *     or {@code start:<regex>}
 * @param maxRecordLines lines in a record, head included; later continuation lines are dropped
 * @param maxRecordBytes bytes of a record's body; later continuation lines are dropped
 * @param flushAfter a multiline record still open when a run reaches the end of its source is
 *     ingested once the source was last modified this long ago; until then it is held back for the
 *     next run
 */
@ConfigurationProperties("argus.framing")
public record FramingProperties(
    String mode, Integer maxRecordLines, DataSize maxRecordBytes, Duration flushAfter) {

  public static final FramingProperties DEFAULTS = new FramingProperties(null, null, null, null);

  @ConstructorBinding
  public FramingProperties {
    mode = mode == null || mode.isBlank() ? "auto" : mode.strip();
    maxRecordLines = maxRecordLines != null ? maxRecordLines : 500;
    maxRecordBytes = maxRecordBytes != null ? maxRecordBytes : DataSize.ofMegabytes(1);
    flushAfter = flushAfter != null ? flushAfter : Duration.ofSeconds(5);
    try {
      FramingSpec.parse(mode).orElseThrow();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("argus.framing.mode: " + e.getMessage(), e);
    }
    if (maxRecordLines < 1
        || maxRecordBytes.toBytes() < 1
        || maxRecordBytes.toBytes() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "argus.framing.max-record-lines and max-record-bytes must be positive (bytes under 2GB)");
    }
    if (flushAfter.isNegative()) {
      throw new IllegalArgumentException("argus.framing.flush-after must not be negative");
    }
  }

  /** The framing of a run that requested none. */
  public FramingSpec defaultSpec() {
    return FramingSpec.parse(mode).orElseThrow();
  }

  public RecordFramer.Limits limits() {
    return new RecordFramer.Limits(maxRecordLines, (int) maxRecordBytes.toBytes());
  }
}
