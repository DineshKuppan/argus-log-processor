package dev.dinesh.argus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.archive.FileSystemObjectStore;
import dev.dinesh.argus.archive.GcsObjectStore;
import dev.dinesh.argus.archive.ObjectStore;
import dev.dinesh.argus.archive.S3ObjectStore;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.wal.WalSegments;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * Wires the archive chosen by {@code argus.archive.store}. With {@code none}, a blank value or no
 * value (the default) nothing here is created, and the local NDJSON segments stay the output, as
 * before P2. Blank must mean none here because {@link ArchiveProperties} binds it to {@code NONE}.
 */
@Configuration
@Conditional(ArchiveConfig.Enabled.class)
public class ArchiveConfig {

  /** {@code argus.archive.store} names a store (not blank, not {@code none}). */
  static final class Enabled extends StoreCondition {
    Enabled() {
      super("argus.archive.store");
    }
  }

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

  /** Fails startup without Application Default Credentials, rather than on the first upload. */
  private static ObjectStore gcsStore(ArchiveProperties.Gcs gcs) {
    try {
      return new GcsObjectStore(
          gcs.endpoint(),
          gcs.bucket(),
          gcs.anonymous() ? null : GcsObjectStore.defaultCredentials());
    } catch (IOException e) {
      throw new IllegalStateException(
          "argus.archive.store=gcs needs Application Default Credentials (Workload Identity,"
              + " GOOGLE_APPLICATION_CREDENTIALS or gcloud auth application-default login)",
          e);
    }
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
      case GCS -> gcsStore(props.gcs());
      case NONE -> throw new IllegalStateException("No archive store is configured");
    };
  }

  /** Drains the local WAL; with Kafka the archive consumer drives an archiver instead. */
  @Bean
  @ConditionalOnProperty(name = SinkConfig.LOCAL_WAL, havingValue = "local", matchIfMissing = true)
  Archiver archiver(
      ObjectStore archiveStore,
      ArchiveProperties props,
      WalSegments wal,
      ObjectMapper mapper,
      ObjectProvider<ArgusMetrics> metrics) {
    return new Archiver(
        archiveStore, wal, props, mapper, metrics.getIfAvailable(ArgusMetrics::noop));
  }
}
