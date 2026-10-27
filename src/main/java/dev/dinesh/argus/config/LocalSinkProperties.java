package dev.dinesh.argus.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Settings for {@code LocalVolumeSink}: output root and the free space required per segment. */
@ConfigurationProperties("argus.sink.local")
public record LocalSinkProperties(Path root, DataSize minFreeBytes) {

  public LocalSinkProperties {
    root = root != null ? root : Path.of("data", "out");
    minFreeBytes = minFreeBytes != null ? minFreeBytes : DataSize.ofGigabytes(2);
  }
}
