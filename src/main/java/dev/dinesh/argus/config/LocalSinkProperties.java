package dev.dinesh.argus.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.util.unit.DataSize;

/**
 * Settings for {@code LocalVolumeSink}: output root, the free space required per segment, and
 * whether segments are compressed.
 *
 * @param compression {@code zstd} writes {@code .ndjson.zst} segments, several times smaller, so
 *     the WAL holds more backlog when an archive store is set; {@code none} keeps plain NDJSON
 */
@ConfigurationProperties("argus.sink.local")
public record LocalSinkProperties(Path root, DataSize minFreeBytes, Compression compression) {

  public enum Compression {
    NONE,
    ZSTD
  }

  @ConstructorBinding
  public LocalSinkProperties {
    root = root != null ? root : Path.of("data", "out");
    minFreeBytes = minFreeBytes != null ? minFreeBytes : DataSize.ofGigabytes(2);
    compression = compression != null ? compression : Compression.NONE;
  }

  /** Uncompressed segments. */
  public LocalSinkProperties(Path root, DataSize minFreeBytes) {
    this(root, minFreeBytes, null);
  }
}
