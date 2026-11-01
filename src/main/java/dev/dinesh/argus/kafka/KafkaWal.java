package dev.dinesh.argus.kafka;

import dev.dinesh.argus.config.KafkaProperties;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * The WAL topic ({@code P6_PLAN.md} D1, D5, D6): the shared producer, an admin client, the topic
 * checks, and the settings every consumer of it uses. One per application.
 *
 * <p>Records: key {@code <tenant>/<source>}, value the {@code LogEnvelope} JSON (the local WAL's
 * NDJSON line), headers {@link #SEGMENT} ({@code <generation>:<startOffset>}) and {@link #OFFSET}
 * (the line's source offset). A segment ends with a marker: same key, no value, {@link #TYPE}
 * {@code commit} (with {@link #END}, {@link #LINES} and {@link #FIRST}, the Kafka offset of the
 * segment's first line) or {@code abort}.
 */
@Slf4j
public final class KafkaWal implements AutoCloseable {

  public static final String SEGMENT = "argus-seg";
  public static final String OFFSET = "argus-off";
  public static final String TYPE = "argus-type";
  public static final String END = "argus-end";
  public static final String LINES = "argus-lines";
  public static final String FIRST = "argus-first";
  public static final String VERSION = "argus-v";
  public static final String COMMIT = "commit";
  public static final String ABORT = "abort";

  /**
   * A record holds one envelope; a 1MB line is up to ~2MB of escaped JSON, so twice that leaves
   * room. The topic's {@code max.message.bytes} must be at least this.
   */
  static final int MAX_RECORD_BYTES = 4 * 1024 * 1024;

  private static final long ADMIN_TIMEOUT_SECONDS = 10;
  private static final int CONSUMER_FETCH_BYTES = 16 * 1024 * 1024;

  private final KafkaProperties props;
  private final Producer<byte[], byte[]> producer;
  private final Admin admin;
  private volatile boolean topicReady;

  public KafkaWal(KafkaProperties props) {
    this(
        props,
        new KafkaProducer<>(producerConfig(props)),
        Admin.create(common(props, "argus-admin")));
  }

  KafkaWal(KafkaProperties props, Producer<byte[], byte[]> producer, Admin admin) {
    if (props.bootstrapServers() == null || props.bootstrapServers().isBlank()) {
      throw new IllegalArgumentException(
          "argus.wal.store=kafka needs argus.kafka.bootstrap-servers");
    }
    this.props = props;
    this.producer = producer;
    this.admin = admin;
  }

  public KafkaProperties props() {
    return props;
  }

  public String topic() {
    return props.topic();
  }

  public Producer<byte[], byte[]> producer() {
    return producer;
  }

  private static Map<String, Object> common(KafkaProperties props, String clientId) {
    Map<String, Object> config = new HashMap<>(props.properties());
    config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, props.bootstrapServers());
    config.put(AdminClientConfig.CLIENT_ID_CONFIG, clientId);
    return config;
  }

  /**
   * Idempotent and ordered per partition (lines before their marker), compressed in batches. A
   * cluster that doesn't answer blocks {@code send} and {@code flush} for up to {@code max-outage}:
   * that is backpressure on the writer, not a failure (D5).
   */
  static Map<String, Object> producerConfig(KafkaProperties props) {
    Map<String, Object> config = common(props, "argus-wal-producer");
    long outage = props.maxOutage().toMillis();
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
    config.put(ProducerConfig.LINGER_MS_CONFIG, 20);
    config.put(ProducerConfig.BATCH_SIZE_CONFIG, 1024 * 1024);
    config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, MAX_RECORD_BYTES);
    config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 32L * 1024 * 1024);
    config.put(
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) Math.min(outage, Integer.MAX_VALUE));
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, outage);
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return config;
  }

  /**
   * A consumer of the WAL topic in {@code group}: offsets committed only by Argus, after the sink
   * has the data; cooperative rebalances (only moved partitions are revoked); long polls allowed
   * for uploads.
   */
  public Map<String, Object> consumerConfig(String group, String clientId) {
    Map<String, Object> config = common(props, clientId);
    config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(
        ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
        CooperativeStickyAssignor.class.getName());
    config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);
    config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000);
    config.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, CONSUMER_FETCH_BYTES);
    config.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, MAX_RECORD_BYTES);
    config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    return config;
  }

  /**
   * Creates the topic if allowed and it is missing, then checks it can't lose lines (D5): deletion
   * by time only, after {@code min-retention}, and records as large as Argus sends. Once it
   * succeeds it isn't repeated.
   *
   * @throws IOException the cluster didn't answer
   * @throws IllegalStateException the topic is missing or unsafe ({@code check-topic=fail})
   */
  public void ensureTopic() throws IOException {
    if (topicReady) {
      return;
    }
    synchronized (this) {
      if (topicReady) {
        return;
      }
      if (!exists()) {
        if (!props.createTopics()) {
          throw new IllegalStateException(
              "Kafka topic "
                  + props.topic()
                  + " does not exist; create it (D6) or set argus.kafka.create-topics=true");
        }
        create();
      }
      List<String> problems = problems(config());
      if (!problems.isEmpty()) {
        String message =
            "Kafka topic " + props.topic() + " could lose lines: " + String.join("; ", problems);
        if (props.checkTopic() == KafkaProperties.Check.FAIL) {
          throw new IllegalStateException(message + " (or set argus.kafka.check-topic=warn)");
        }
        log.warn(message);
      }
      topicReady = true;
      log.info("Kafka WAL topic {} ready at {}", props.topic(), props.bootstrapServers());
    }
  }

  private boolean exists() throws IOException {
    try {
      await(admin.describeTopics(List.of(props.topic())).allTopicNames());
      return true;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof UnknownTopicOrPartitionException) {
        return false;
      }
      throw new IOException("Describing Kafka topic " + props.topic() + " failed", e.getCause());
    }
  }

  private void create() throws IOException {
    Map<String, String> configs = new LinkedHashMap<>();
    configs.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE);
    configs.put(TopicConfig.RETENTION_MS_CONFIG, Long.toString(props.minRetention().toMillis()));
    configs.put(TopicConfig.RETENTION_BYTES_CONFIG, "-1");
    configs.put(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, Integer.toString(MAX_RECORD_BYTES));
    configs.put(
        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
        Integer.toString(Math.min(2, props.replicationFactor())));
    NewTopic topic =
        new NewTopic(props.topic(), props.partitions(), props.replicationFactor()).configs(configs);
    try {
      await(admin.createTopics(List.of(topic)).all());
      log.info(
          "Created Kafka topic {}: {} partitions, replication {}, {}",
          props.topic(),
          props.partitions(),
          props.replicationFactor(),
          configs);
    } catch (ExecutionException e) {
      if (!(e.getCause() instanceof TopicExistsException)) {
        throw new IOException("Creating Kafka topic " + props.topic() + " failed", e.getCause());
      }
    }
  }

  private Config config() throws IOException {
    ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, props.topic());
    try {
      return await(admin.describeConfigs(List.of(resource)).all()).get(resource);
    } catch (ExecutionException e) {
      throw new IOException("Reading Kafka topic " + props.topic() + " failed", e.getCause());
    }
  }

  /** What about the topic's settings could delete lines before every sink has them. */
  List<String> problems(Config config) {
    List<String> problems = new ArrayList<>();
    String policy = value(config, TopicConfig.CLEANUP_POLICY_CONFIG, "delete");
    if (policy.contains(TopicConfig.CLEANUP_POLICY_COMPACT)) {
      problems.add("cleanup.policy=" + policy + " (compaction drops lines; use delete)");
    }
    long retentionMs = Long.parseLong(value(config, TopicConfig.RETENTION_MS_CONFIG, "-1"));
    if (retentionMs >= 0 && retentionMs < props.minRetention().toMillis()) {
      problems.add(
          "retention.ms="
              + retentionMs
              + " is shorter than argus.kafka.min-retention ("
              + props.minRetention()
              + ")");
    }
    long retentionBytes = Long.parseLong(value(config, TopicConfig.RETENTION_BYTES_CONFIG, "-1"));
    if (retentionBytes >= 0) {
      problems.add(
          "retention.bytes="
              + retentionBytes
              + " deletes by size, whether or not the sinks have the lines; use -1");
    }
    long maxMessage =
        Long.parseLong(
            value(
                config, TopicConfig.MAX_MESSAGE_BYTES_CONFIG, Integer.toString(MAX_RECORD_BYTES)));
    if (maxMessage < MAX_RECORD_BYTES) {
      problems.add(
          "max.message.bytes="
              + maxMessage
              + " is below "
              + MAX_RECORD_BYTES
              + ": the longest lines would be refused");
    }
    return problems;
  }

  private static String value(Config config, String name, String fallback) {
    ConfigEntry entry = config.get(name);
    return entry == null || entry.value() == null ? fallback : entry.value();
  }

  /**
   * Each group's lag in records: end offsets minus its committed offsets (from the partition's
   * start where it has committed none), summed over the topic's partitions.
   */
  public Map<String, Long> lag(Collection<String> groups) throws IOException {
    try {
      TopicDescription topic =
          await(admin.describeTopics(List.of(props.topic())).allTopicNames()).get(props.topic());
      Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
      Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
      topic
          .partitions()
          .forEach(
              p -> {
                TopicPartition tp = new TopicPartition(props.topic(), p.partition());
                latest.put(tp, OffsetSpec.latest());
                earliest.put(tp, OffsetSpec.earliest());
              });
      var ends = await(admin.listOffsets(latest).all());
      var starts = await(admin.listOffsets(earliest).all());
      Map<String, Long> lag = new LinkedHashMap<>();
      for (String group : groups) {
        ListConsumerGroupOffsetsResult result = admin.listConsumerGroupOffsets(group);
        Map<TopicPartition, OffsetAndMetadata> committed =
            await(result.partitionsToOffsetAndMetadata());
        long total = 0;
        for (TopicPartition tp : latest.keySet()) {
          OffsetAndMetadata c = committed.get(tp);
          long from = c != null ? c.offset() : starts.get(tp).offset();
          total += Math.max(0, ends.get(tp).offset() - from);
        }
        lag.put(group, total);
      }
      return lag;
    } catch (ExecutionException e) {
      throw new IOException("Measuring Kafka consumer lag failed", e.getCause());
    }
  }

  private static <T> T await(org.apache.kafka.common.KafkaFuture<T> future)
      throws IOException, ExecutionException {
    try {
      return future.get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new IOException("Kafka did not answer within " + ADMIN_TIMEOUT_SECONDS + "s", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted waiting for Kafka");
    } catch (KafkaException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** A header's value as text, or null if the record hasn't it. */
  public static String header(Headers headers, String name) {
    Header header = headers.lastHeader(name);
    return header == null || header.value() == null
        ? null
        : new String(header.value(), StandardCharsets.UTF_8);
  }

  /** The groups whose lag the watermarks measure: the routed sinks' (D5). */
  public static Set<String> groups(boolean archive, boolean hot) {
    Set<String> groups = new java.util.LinkedHashSet<>();
    if (archive) {
      groups.add(KafkaProperties.ARCHIVE_GROUP);
    }
    if (hot) {
      groups.add(KafkaProperties.HOT_GROUP);
    }
    return groups;
  }

  /** Flushes what the producer holds, waiting at most {@code timeout}, then closes the clients. */
  @Override
  public void close() {
    try {
      producer.close(Duration.ofSeconds(30));
    } finally {
      admin.close(Duration.ofSeconds(5));
    }
  }
}
