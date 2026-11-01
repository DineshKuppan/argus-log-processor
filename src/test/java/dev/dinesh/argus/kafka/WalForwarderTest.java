package dev.dinesh.argus.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.wal.WalFixture;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Switching from the local WAL to Kafka (D6), on Apache Kafka and Redpanda. */
class WalForwarderTest {

  private static final Instant T = Instant.parse("2026-10-31T12:00:00Z");

  @TempDir Path dir;

  @ParameterizedTest
  @EnumSource(Broker.class)
  void pendingLocalSegmentsAreSentAsSegmentsThenRemoved(Broker broker) throws Exception {
    Path root = dir.resolve("wal");
    WalFixture local = new WalFixture(root);
    Path pending = local.segment("acme", "app", 0, 0, 4, T);
    Path alsoPending = local.segment("globex", "web", 1, 100, 2, T);
    WalSegments wal = new WalSegments(root, Set.of(WalSink.ARCHIVE, WalSink.HOT), true);
    // Already archived, not yet indexed: still pending, so forwarded too.
    wal.acknowledge(wal.pending().get(0), WalSink.ARCHIVE);

    String topic = KafkaContainers.topic();
    try (KafkaWal kafka = new KafkaWal(KafkaContainers.props(broker, topic, dir))) {
      WalForwarder forwarder =
          new WalForwarder(
              wal,
              new KafkaSink(kafka, WalFixture.MAPPER, ArgusMetrics.noop()),
              WalFixture.MAPPER,
              Duration.ofMillis(100),
              Duration.ofSeconds(1));
      assertEquals(2, forwarder.forwardAll());

      List<ConsumerRecord<byte[], byte[]>> records = KafkaContainers.readAll(broker, topic, 8);
      assertEquals(8, records.size(), "4 + 2 lines and 2 markers");
      assertEquals(
          2,
          records.stream()
              .filter(r -> KafkaWal.COMMIT.equals(KafkaWal.header(r.headers(), KafkaWal.TYPE)))
              .count());
      assertFalse(Files.exists(pending));
      assertFalse(Files.exists(alsoPending));
      assertEquals(List.of(), wal.pending());
      assertEquals(0, forwarder.forwardAll(), "nothing left: a restart forwards nothing again");
    }
  }
}
