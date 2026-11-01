package dev.dinesh.argus.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.config.KafkaProperties;
import dev.dinesh.argus.config.WalProperties;
import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.wal.WalFixture;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Watermarks over consumer lag (D5), on Apache Kafka and Redpanda. */
class KafkaWalCapacityTest {

  private static final Instant T = Instant.parse("2026-10-31T12:00:00Z");

  @TempDir Path dir;

  /** Commits {@code offset} on partition 0 for {@code group}, as a sink would. */
  private static void commit(Broker broker, String topic, String group, long offset) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaContainers.bootstrap(broker));
    config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(config)) {
      c.commitSync(Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(offset)));
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void fullAtTheHighWatermarkOfAnyGroupsLagAndFreeBelowTheLow(Broker broker) throws Exception {
    String topic = KafkaContainers.topic();
    KafkaProperties props = KafkaContainers.props(broker, topic, dir, 1, 10);
    try (KafkaWal wal = new KafkaWal(props)) {
      new WalFixture(new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop()))
          .write("acme", "app", 0, 0, 9, T); // 9 lines + a marker: 10 records
      Set<String> groups = Set.of("argus-archive", "argus-hot");
      KafkaWalCapacity capacity =
          new KafkaWalCapacity(wal, groups, new WalProperties(null, 0.8, 0.6), Duration.ofDays(1));

      commit(broker, topic, "argus-hot", 10);
      capacity.measure();
      assertEquals(Map.of("argus-archive", 10L, "argus-hot", 0L), capacity.lag());
      assertTrue(capacity.isFull(), "archive's lag 10 ≥ 0.8 × 10");

      commit(broker, topic, "argus-archive", 3); // lag 7: between the watermarks
      capacity.measure();
      Thread.sleep(1100);
      assertTrue(capacity.isFull(), "hysteresis: still full until below 6");

      commit(broker, topic, "argus-archive", 5); // lag 5
      capacity.measure();
      Thread.sleep(1100);
      assertFalse(capacity.isFull());
      assertEquals(5L, capacity.lag("argus-archive"));
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aClusterThatDoesNotAnswerKeepsTheLastMeasurement(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir, 1, 10);
    KafkaProperties unreachable =
        new KafkaProperties(
            "127.0.0.1:1",
            props.topic(),
            true,
            props.checkTopic(),
            1,
            (short) 1,
            null,
            Duration.ofMinutes(1),
            10,
            null,
            null,
            null,
            null,
            null,
            dir,
            Map.of("request.timeout.ms", "500", "default.api.timeout.ms", "1000"));
    try (KafkaWal wal = new KafkaWal(unreachable)) {
      KafkaWalCapacity capacity =
          new KafkaWalCapacity(
              wal, Set.of("argus-hot"), new WalProperties(null, 0.8, 0.6), Duration.ofDays(1));
      capacity.measure();
      assertEquals(Map.of(), capacity.lag());
      assertFalse(capacity.isFull(), "never measured: not full");
      assertEquals(List.of(), List.copyOf(capacity.lag().keySet()));
    }
  }
}
