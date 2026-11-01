package dev.dinesh.argus.kafka;

import dev.dinesh.argus.config.KafkaProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.redpanda.RedpandaContainer;

/**
 * One Apache Kafka 3.8 (KRaft) and one Redpanda 24.2 container per test JVM, started on first use
 * and shared by the P6 broker tests (Testcontainers' reaper stops them). Tests using them are
 * skipped without Docker. Each test uses its own topic.
 */
public final class KafkaContainers {

  /** The brokers every P6 broker test runs against. */
  public enum Broker {
    KAFKA,
    REDPANDA
  }

  private static final Map<Broker, GenericContainer<?>> STARTED = new EnumMap<>(Broker.class);

  private KafkaContainers() {}

  public static void assumeDocker() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
  }

  public static synchronized String bootstrap(Broker broker) {
    assumeDocker();
    GenericContainer<?> c = STARTED.computeIfAbsent(broker, KafkaContainers::start);
    return c instanceof RedpandaContainer r
        ? r.getBootstrapServers()
        : ((org.testcontainers.kafka.KafkaContainer) c).getBootstrapServers();
  }

  /** For {@code docker pause} / {@code unpause} in outage tests. */
  public static synchronized String containerId(Broker broker) {
    bootstrap(broker);
    return STARTED.get(broker).getContainerId();
  }

  private static GenericContainer<?> start(Broker broker) {
    GenericContainer<?> c =
        broker == Broker.REDPANDA
            ? new RedpandaContainer("redpandadata/redpanda:v24.2.18")
            : new org.testcontainers.kafka.KafkaContainer("apache/kafka:3.8.1")
                .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");
    c.withStartupTimeout(Duration.ofMinutes(3));
    c.start();
    return c;
  }

  /** Settings for a fresh topic on {@code broker}: one replica, created by Argus. */
  public static KafkaProperties props(Broker broker, String topic, Path stagingDir) {
    return props(broker, topic, stagingDir, 3, 100_000_000L);
  }

  public static KafkaProperties props(
      Broker broker, String topic, Path stagingDir, int partitions, long maxLagRecords) {
    return new KafkaProperties(
        bootstrap(broker),
        topic,
        true,
        KafkaProperties.Check.FAIL,
        partitions,
        (short) 1,
        null,
        Duration.ofMinutes(1),
        maxLagRecords,
        null,
        Duration.ofMillis(200),
        Duration.ofMillis(100),
        Duration.ofMillis(100),
        Duration.ofSeconds(1),
        stagingDir,
        null);
  }

  public static String topic() {
    return "t-" + UUID.randomUUID();
  }

  /** Every record of {@code topic} written so far, in partition order. */
  public static List<ConsumerRecord<byte[], byte[]>> readAll(Broker broker, String topic, int min) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(broker));
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "read-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    List<ConsumerRecord<byte[], byte[]>> records = new java.util.ArrayList<>();
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      int idle = 0;
      while (System.nanoTime() < deadline && (records.size() < min || idle < 3)) {
        int before = records.size();
        consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        idle = records.size() == before ? idle + 1 : 0;
      }
    }
    return records;
  }
}
