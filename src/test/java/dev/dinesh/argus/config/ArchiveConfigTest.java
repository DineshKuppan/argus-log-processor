package dev.dinesh.argus.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.archive.FileSystemObjectStore;
import dev.dinesh.argus.archive.GcsObjectStore;
import dev.dinesh.argus.archive.ObjectStore;
import dev.dinesh.argus.archive.S3ObjectStore;
import dev.dinesh.argus.archive.WatermarkWalCapacity;
import dev.dinesh.argus.sink.WalCapacity;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3AsyncClient;

class ArchiveConfigTest {

  @TempDir Path dir;

  @Configuration
  @EnableConfigurationProperties({
    ArchiveProperties.class,
    LocalSinkProperties.class,
    WalProperties.class
  })
  static class Base {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper();
    }
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(Base.class, ArchiveConfig.class)
        .withPropertyValues(
            "argus.sink.local.root=" + dir.resolve("wal"),
            "argus.archive.staging-dir=" + dir.resolve("staging"));
  }

  @Test
  void noArchiveByDefault() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(Archiver.class);
              assertThat(context).doesNotHaveBean(ObjectStore.class);
              assertThat(context).doesNotHaveBean(WalCapacity.class);
            });
  }

  @Test
  void aBlankStoreMeansNoArchiveToo() {
    runner()
        .withPropertyValues("argus.archive.store=")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(Archiver.class);
            });
  }

  @Test
  void aFilesystemStoreStartsTheArchiver() {
    runner()
        .withPropertyValues(
            "argus.archive.store=filesystem",
            "argus.archive.filesystem.root=" + dir.resolve("archive"))
        .run(
            context -> {
              assertThat(context).hasSingleBean(Archiver.class);
              assertThat(context.getBean(ObjectStore.class))
                  .isInstanceOf(FileSystemObjectStore.class);
              assertThat(context.getBean(Archiver.class).isRunning()).isTrue();
              assertThat(context.getBean(WalCapacity.class))
                  .isInstanceOf(WatermarkWalCapacity.class);
              assertThat(context).doesNotHaveBean(S3AsyncClient.class);
            });
  }

  @Test
  void refusesAFilesystemArchiveInsideTheWal() {
    runner()
        .withPropertyValues(
            "argus.archive.store=FILESYSTEM",
            "argus.archive.filesystem.root=" + dir.resolve("wal/archive"))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void aGcsStoreNeedsABucket() {
    runner()
        .withPropertyValues("argus.archive.store=gcs")
        .run(context -> assertThat(context).hasFailed());
    runner()
        .withPropertyValues(
            "argus.archive.store=gcs",
            "argus.archive.gcs.bucket=logs",
            "argus.archive.gcs.endpoint=http://localhost:1",
            "argus.archive.gcs.anonymous=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ObjectStore.class)).isInstanceOf(GcsObjectStore.class);
              assertThat(context.getBean(ObjectStore.class).describe()).isEqualTo("gs://logs");
            });
  }

  @Test
  void anS3StoreNeedsABucket() {
    runner()
        .withPropertyValues("argus.archive.store=s3")
        .run(context -> assertThat(context).hasFailed());
    runner()
        .withPropertyValues(
            "argus.archive.store=s3",
            "argus.archive.s3.bucket=logs",
            "argus.archive.s3.endpoint=http://localhost:1",
            "argus.archive.s3.path-style=true",
            "argus.archive.s3.checksums=when-required")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(ObjectStore.class)).isInstanceOf(S3ObjectStore.class);
              assertThat(context.getBean(ObjectStore.class).describe()).isEqualTo("s3://logs");
            });
  }
}
