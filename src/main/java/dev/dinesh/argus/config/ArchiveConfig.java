package dev.dinesh.argus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.archive.FileSystemObjectStore;
import dev.dinesh.argus.archive.ObjectStore;
import dev.dinesh.argus.archive.S3ObjectStore;
import dev.dinesh.argus.archive.WalSegments;
import dev.dinesh.argus.archive.WatermarkWalCapacity;
import dev.dinesh.argus.sink.WalCapacity;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * Wires the archive chosen by {@code argus.archive.store}. With {@code none}, a blank value or no
 * value (the default) nothing here is created, and the local NDJSON segments stay the output, as
 * before P2. Blank must mean none here because {@link ArchiveProperties} binds it to {@code NONE}.
 */
@Configuration
@ConditionalOnExpression(
    "!'${argus.archive.store:none}'.trim().isEmpty()"
        + " && !'${argus.archive.store:none}'.trim().equalsIgnoreCase('none')")
public class ArchiveConfig {

  /** Closed by Spring on shutdown (inferred {@code close()}). */
  @Bean
  @ConditionalOnProperty(name = "argus.archive.store", havingValue = "s3")
  S3AsyncClient archiveS3Client(ArchiveProperties props) {
    ArchiveProperties.S3 s3 = props.s3();
    return S3ObjectStore.newClient(
        s3.endpoint(),
        s3.region(),
        s3.pathStyle(),
        s3.checksums() == ArchiveProperties.S3.Checksums.WHEN_REQUIRED,
        null);
  }

  @Bean
  ObjectStore archiveStore(
      ArchiveProperties props, LocalSinkProperties wal, ObjectProvider<S3AsyncClient> s3) {
    return switch (props.store()) {
      case FILESYSTEM -> {
        Path root = props.filesystem().root().toAbsolutePath().normalize();
        if (root.startsWith(wal.root().toAbsolutePath().normalize())) {
          throw new IllegalArgumentException(
              "argus.archive.filesystem.root must be outside the WAL (argus.sink.local.root): "
                  + root);
        }
        yield new FileSystemObjectStore(root);
      }
      case S3 -> new S3ObjectStore(s3.getObject(), props.s3().bucket());
      case NONE -> throw new IllegalStateException("No archive store is configured");
    };
  }

  /** Ingest waits on this before each segment (P2 D7): the archive is what drains the WAL. */
  @Bean
  WalCapacity walCapacity(LocalSinkProperties wal, WalProperties limits) {
    return new WatermarkWalCapacity(new WalSegments(wal.root()), limits);
  }

  @Bean
  Archiver archiver(
      ObjectStore archiveStore,
      ArchiveProperties props,
      LocalSinkProperties wal,
      ObjectMapper mapper) {
    return new Archiver(archiveStore, new WalSegments(wal.root()), props, mapper);
  }
}
