package dev.dinesh.argus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.archive.ObjectStore;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.hot.HotBulkWriter;
import dev.dinesh.argus.hot.HotIndices;
import dev.dinesh.argus.kafka.ArchiveConsumer;
import dev.dinesh.argus.kafka.HotConsumer;
import dev.dinesh.argus.kafka.KafkaSink;
import dev.dinesh.argus.kafka.KafkaTailBus;
import dev.dinesh.argus.kafka.KafkaWal;
import dev.dinesh.argus.kafka.KafkaWalCapacity;
import dev.dinesh.argus.kafka.WalForwarder;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.tail.TailHub;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Kafka as the WAL ({@code P6_PLAN.md}), with {@code argus.wal.store=kafka}: ingest writes segments
 * to the topic ({@link KafkaSink}), the archive and the hot tier consume it as groups, and the
 * watermarks measure their lag. A topic that is unsafe, or missing without {@code create-topics},
 * fails startup; a cluster that doesn't answer only warns (writers block, consumers retry).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = SinkConfig.LOCAL_WAL, havingValue = "kafka")
public class KafkaConfig {

  @Bean(destroyMethod = "close")
  KafkaWal kafkaWal(KafkaProperties props, InstanceProperties instance) {
    if (!instance.isSet()) {
      log.warn(
          "argus.instance.id is not set: run a single instance, or give each its own id (D8);"
              + " two instances without ids overwrite each other's lines");
    }
    KafkaWal wal = new KafkaWal(props, instance.id());
    try {
      wal.ensureTopic();
    } catch (IOException e) {
      log.warn(
          "Kafka {} not reachable at startup ({}); ingest waits for it, consumers retry",
          props.bootstrapServers(),
          e.toString());
    }
    return wal;
  }

  @Bean
  LogSink kafkaSink(KafkaWal wal, ObjectMapper mapper, ObjectProvider<ArgusMetrics> metrics) {
    return new KafkaSink(wal, mapper, metrics.getIfAvailable(ArgusMetrics::noop), wal.instance());
  }

  private static boolean archiveRouted(Environment env) {
    return named(env, "argus.archive.store");
  }

  private static boolean hotRouted(Environment env) {
    return named(env, "argus.hot.store");
  }

  private static boolean named(Environment env, String property) {
    String value = env.getProperty(property, "none").strip();
    return !value.isEmpty() && !value.equalsIgnoreCase("none");
  }

  /** Only with a sink routed; otherwise nothing consumes the topic and retention is the limit. */
  @Bean
  @Conditional(WalConfig.Drained.class)
  KafkaWalCapacity kafkaWalCapacity(KafkaWal wal, WalProperties limits, Environment env) {
    return new KafkaWalCapacity(wal, KafkaWal.groups(archiveRouted(env), hotRouted(env)), limits);
  }

  /**
   * The archiver the consumer drives, over its staging root ({@code D3}): the P2 code, keys and
   * supersede logic, unchanged.
   */
  @Bean
  @Conditional(ArchiveConfig.Enabled.class)
  ArchiveConsumer kafkaArchiveConsumer(
      KafkaWal wal,
      KafkaProperties props,
      ObjectStore archiveStore,
      ArchiveProperties archive,
      ObjectMapper mapper,
      ObjectProvider<ArgusMetrics> metrics)
      throws IOException {
    ArgusMetrics m = metrics.getIfAvailable(ArgusMetrics::noop);
    WalSegments staging =
        new WalSegments(Files.createDirectories(props.stagingDir()), Set.of(WalSink.ARCHIVE), true);
    return new ArchiveConsumer(
        wal, props, new Archiver(archiveStore, staging, archive, mapper, m, true), m);
  }

  @Bean
  @Conditional(HotConfig.Enabled.class)
  HotConsumer kafkaHotConsumer(
      KafkaWal wal,
      KafkaProperties props,
      SearchEngineClient hotClient,
      HotIndices indices,
      HotProperties hot,
      ObjectProvider<ArgusMetrics> metrics) {
    ArgusMetrics m = metrics.getIfAvailable(ArgusMetrics::noop);
    HotBulkWriter writer =
        new HotBulkWriter(hotClient, indices, hot, m, Clock.systemUTC(), Duration.ofSeconds(1));
    return new HotConsumer(wal, props, writer, m);
  }

  /** The live tail shared by every instance (D10), unless {@code tail-topic=none}. */
  @Bean
  @ConditionalOnExpression("!'${argus.kafka.tail-topic:argus-tail}'.equalsIgnoreCase('none')")
  KafkaTailBus kafkaTailBus(
      KafkaWal wal, TailHub hub, ObjectMapper mapper, ObjectProvider<ArgusMetrics> metrics) {
    return new KafkaTailBus(wal, hub, mapper, metrics.getIfAvailable(ArgusMetrics::noop));
  }

  /** Sends what a local WAL still holds (D6); finds nothing when there is none. */
  @Bean
  @Conditional(WalConfig.Drained.class)
  WalForwarder walForwarder(
      LocalSinkProperties local,
      ArchiveProperties archive,
      KafkaProperties props,
      LogSink kafkaSink,
      ObjectMapper mapper,
      InstanceProperties instance,
      Environment env) {
    Set<WalSink> routed = EnumSet.noneOf(WalSink.class);
    if (archiveRouted(env)) {
      routed.add(WalSink.ARCHIVE);
    }
    if (hotRouted(env)) {
      routed.add(WalSink.HOT);
    }
    boolean delete = routed.contains(WalSink.ARCHIVE) && archive.deleteAfterUpload();
    return new WalForwarder(
        new WalSegments(local.root(), routed, delete, instance.id()),
        kafkaSink,
        mapper,
        props.retryInitial(),
        props.retryMax());
  }

  @Bean
  MeterBinder kafkaGauges(
      KafkaWal wal,
      ObjectProvider<KafkaWalCapacity> capacity,
      ObjectProvider<ArchiveConsumer> archive,
      ObjectProvider<HotConsumer> hot) {
    return registry -> {
      new KafkaClientMetrics(wal.producer()).bindTo(registry);
      capacity.ifAvailable(
          c -> {
            for (String group : c.groups()) {
              Gauge.builder("argus.kafka.lag", c, x -> x.lag(group))
                  .tag("group", group)
                  .baseUnit("records")
                  .description("Records of the WAL topic this sink's group hasn't committed")
                  .register(registry);
            }
            Gauge.builder("argus.wal.full", c, x -> x.isFull() ? 1 : 0)
                .description("1 while ingest waits for the sinks to drain the WAL")
                .register(registry);
          });
      archive.ifAvailable(
          a ->
              Gauge.builder(
                      "argus.kafka.lag.age",
                      a,
                      x -> age(capacity, "argus-archive", x.lastRecordMillis()))
                  .tag("group", "argus-archive")
                  .baseUnit("seconds")
                  .description("Age of the last record handled while the group has lag")
                  .register(registry));
      hot.ifAvailable(
          h ->
              Gauge.builder(
                      "argus.kafka.lag.age",
                      h,
                      x -> age(capacity, "argus-hot", x.lastRecordMillis()))
                  .tag("group", "argus-hot")
                  .baseUnit("seconds")
                  .description("Age of the last record handled while the group has lag")
                  .register(registry));
    };
  }

  private static double age(
      ObjectProvider<KafkaWalCapacity> capacity, String group, long lastRecordMillis) {
    KafkaWalCapacity c = capacity.getIfAvailable();
    if (c == null || c.lag(group) == 0 || lastRecordMillis <= 0) {
      return 0;
    }
    return Math.max(0, System.currentTimeMillis() - lastRecordMillis) / 1e3;
  }
}
