package dev.dinesh.argus.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.hot.HotIndexer;
import dev.dinesh.argus.kafka.ArchiveConsumer;
import dev.dinesh.argus.kafka.HotConsumer;
import dev.dinesh.argus.kafka.KafkaContainers;
import dev.dinesh.argus.kafka.KafkaSink;
import dev.dinesh.argus.kafka.KafkaWalCapacity;
import dev.dinesh.argus.kafka.WalForwarder;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.sink.WalCapacity;
import dev.dinesh.argus.wal.WalSegments;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Which WAL beans exist for {@code argus.wal.store} ({@code P6_PLAN.md}). */
class KafkaConfigTest {

  @TempDir Path dir;

  @Configuration
  @EnableConfigurationProperties({
    ArchiveProperties.class,
    HotProperties.class,
    KafkaProperties.class,
    LocalSinkProperties.class,
    SearchProperties.class,
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
        .withUserConfiguration(
            Base.class,
            SinkConfig.class,
            WalConfig.class,
            ArchiveConfig.class,
            HotConfig.class,
            KafkaConfig.class)
        .withPropertyValues(
            "argus.sink.local.root=" + dir.resolve("wal"),
            "argus.archive.staging-dir=" + dir.resolve("parquet"),
            "argus.kafka.staging-dir=" + dir.resolve("kafka-staging"),
            "argus.archive.store=filesystem",
            "argus.archive.filesystem.root=" + dir.resolve("archive"),
            "argus.hot.store=opensearch",
            "argus.hot.url=http://127.0.0.1:1");
  }

  @Test
  void theLocalWalByDefault() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(LogSink.class)).isInstanceOf(LocalVolumeSink.class);
              assertThat(context).hasSingleBean(WalSegments.class);
              assertThat(context).hasSingleBean(Archiver.class);
              assertThat(context).hasSingleBean(HotIndexer.class);
              assertThat(context).doesNotHaveBean(ArchiveConsumer.class);
              assertThat(context).doesNotHaveBean(KafkaWalCapacity.class);
            });
  }

  @Test
  void kafkaReplacesTheLocalWalAndItsDrainers() {
    String bootstrap = KafkaContainers.bootstrap(KafkaContainers.Broker.KAFKA);
    runner()
        .withPropertyValues(
            "argus.wal.store=kafka",
            "argus.kafka.bootstrap-servers=" + bootstrap,
            "argus.kafka.topic=" + KafkaContainers.topic(),
            "argus.kafka.create-topics=true",
            "argus.kafka.replication-factor=1")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(LogSink.class)).isInstanceOf(KafkaSink.class);
              assertThat(context.getBean(WalCapacity.class)).isInstanceOf(KafkaWalCapacity.class);
              assertThat(context).hasSingleBean(ArchiveConsumer.class);
              assertThat(context).hasSingleBean(HotConsumer.class);
              assertThat(context).hasSingleBean(WalForwarder.class);
              assertThat(context).doesNotHaveBean(WalSegments.class);
              assertThat(context).doesNotHaveBean(Archiver.class);
              assertThat(context).doesNotHaveBean(HotIndexer.class);
            });
  }

  @Test
  void kafkaNeedsBootstrapServers() {
    runner()
        .withPropertyValues("argus.wal.store=kafka")
        .run(context -> assertThat(context).hasFailed());
  }
}
