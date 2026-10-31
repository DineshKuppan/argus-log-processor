package dev.dinesh.argus.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.config.KafkaProperties;
import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.sink.LogSink.SegmentWriter;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.wal.WalFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The WAL topic's records and markers, on Apache Kafka and Redpanda. */
class KafkaSinkTest {

  private static final Instant T = Instant.parse("2026-10-31T12:00:00Z");

  @TempDir Path dir;

  private KafkaWal wal(Broker broker, String topic) {
    return new KafkaWal(KafkaContainers.props(broker, topic, dir));
  }

  private static String text(byte[] b) {
    return b == null ? null : new String(b, StandardCharsets.UTF_8);
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aSegmentIsItsLinesThenACommitMarkerInOnePartition(Broker broker) throws Exception {
    String topic = KafkaContainers.topic();
    try (KafkaWal wal = wal(broker, topic)) {
      KafkaSink sink = new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop());
      String location = new WalFixture(sink).write("acme", "app", 2, 100, 5, T);
      assertTrue(location.startsWith("kafka:" + topic + "/"), location);

      List<ConsumerRecord<byte[], byte[]>> records = KafkaContainers.readAll(broker, topic, 6);
      assertEquals(6, records.size());
      assertEquals(1, records.stream().map(ConsumerRecord::partition).distinct().count());
      for (int i = 0; i < 5; i++) {
        ConsumerRecord<byte[], byte[]> r = records.get(i);
        assertEquals("acme/app", text(r.key()));
        assertEquals("2:100", KafkaWal.header(r.headers(), KafkaWal.SEGMENT));
        assertEquals(Long.toString(100 + 10L * i), KafkaWal.header(r.headers(), KafkaWal.OFFSET));
        assertTrue(text(r.value()).contains("\"line " + i + "\""));
        assertNull(KafkaWal.header(r.headers(), KafkaWal.TYPE));
      }
      ConsumerRecord<byte[], byte[]> marker = records.get(5);
      assertNull(marker.value());
      assertEquals(KafkaWal.COMMIT, KafkaWal.header(marker.headers(), KafkaWal.TYPE));
      assertEquals("150", KafkaWal.header(marker.headers(), KafkaWal.END));
      assertEquals("5", KafkaWal.header(marker.headers(), KafkaWal.LINES));
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void anEmptySegmentSendsNothingAndAnAbortSendsAMarker(Broker broker) throws Exception {
    String topic = KafkaContainers.topic();
    try (KafkaWal wal = wal(broker, topic)) {
      KafkaSink sink = new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop());
      assertNull(sink.open("acme", "app", 0, 0).commit(0));

      SegmentWriter writer = sink.open("acme", "app", 0, 0);
      writer.write(
          new LogBatch(
              "app",
              0,
              0,
              WalFixture.envelopes("acme", "app", 0, 0, WalFixture.times(2, T)),
              0,
              20,
              false,
              0,
              0,
              0));
      writer.abort();

      List<ConsumerRecord<byte[], byte[]>> records = KafkaContainers.readAll(broker, topic, 3);
      assertEquals(3, records.size());
      assertEquals(KafkaWal.ABORT, KafkaWal.header(records.get(2).headers(), KafkaWal.TYPE));
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aTopicThatCouldLoseLinesFailsTheCheck(Broker broker) throws Exception {
    String topic = KafkaContainers.topic();
    try (Admin admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaContainers.bootstrap(broker)))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic(topic, 1, (short) 1)
                      .configs(
                          Map.of(
                              "cleanup.policy", "compact",
                              "retention.ms", "3600000",
                              "retention.bytes", "1000000"))))
          .all()
          .get();
    }
    try (KafkaWal wal = wal(broker, topic)) {
      IllegalStateException e = assertThrows(IllegalStateException.class, wal::ensureTopic);
      assertTrue(e.getMessage().contains("cleanup.policy=compact"), e.getMessage());
      assertTrue(e.getMessage().contains("retention.ms=3600000"), e.getMessage());
      assertTrue(e.getMessage().contains("retention.bytes=1000000"), e.getMessage());
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aMissingTopicIsCreatedSafelyOrRefused(Broker broker) throws Exception {
    String topic = KafkaContainers.topic();
    KafkaProperties p = KafkaContainers.props(broker, topic, dir);
    KafkaProperties noCreate =
        new KafkaProperties(
            p.bootstrapServers(),
            p.topic(),
            false,
            p.checkTopic(),
            p.partitions(),
            p.replicationFactor(),
            p.minRetention(),
            p.maxOutage(),
            p.maxLagRecords(),
            p.orphanTimeout(),
            p.commitInterval(),
            p.hotLinger(),
            p.retryInitial(),
            p.retryMax(),
            p.stagingDir(),
            p.properties());
    try (KafkaWal wal = new KafkaWal(noCreate)) {
      assertThrows(IllegalStateException.class, wal::ensureTopic);
    }
    try (KafkaWal wal = wal(broker, topic)) {
      wal.ensureTopic(); // created, then passes its own checks
      wal.ensureTopic();
    }
  }
}
