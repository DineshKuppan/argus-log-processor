package dev.dinesh.argus.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka as the WAL ({@code P6_PLAN.md}), used with {@code argus.wal.store=kafka}.
 *
 * @param bootstrapServers the cluster; Apache Kafka 3.x or Redpanda
 * @param topic the WAL topic, shared by every tenant (records are keyed by tenant and source)
 * @param createTopics create the topic when it is missing (development); otherwise it must exist
 * @param checkTopic {@code fail} or {@code warn} when the topic's settings could lose lines (D5)
 * @param partitions and {@code replicationFactor}: for a topic Argus creates
 * @param minRetention the topic must keep records at least this long (D5)
 * @param maxOutage how long a writer blocks on an unreachable cluster before its segment is aborted
 * @param maxLagRecords the lag the WAL watermarks ({@code argus.wal.*-watermark}) are fractions of
 * @param orphanTimeout a staged segment without a record for this long is discarded (D3)
 * @param commitInterval consumers commit their offsets at least this often
 * @param hotLinger the hot consumer sends a partial bulk after this long (D4)
 * @param retryInitial first wait after a failure; doubles up to {@code retryMax}
 * @param stagingDir where the archive consumer reassembles segments; a working area, never the WAL
 * @param properties passed to every Kafka client: {@code security.protocol}, {@code sasl.*}, {@code
 *     ssl.*} (from the environment)
 */
@ConfigurationProperties("argus.kafka")
public record KafkaProperties(
    String bootstrapServers,
    String topic,
    boolean createTopics,
    Check checkTopic,
    int partitions,
    short replicationFactor,
    Duration minRetention,
    Duration maxOutage,
    long maxLagRecords,
    Duration orphanTimeout,
    Duration commitInterval,
    Duration hotLinger,
    Duration retryInitial,
    Duration retryMax,
    Path stagingDir,
    Map<String, String> properties) {

  public enum Check {
    FAIL,
    WARN
  }

  /** The consumer groups of the two sinks (D3, D4). */
  public static final String ARCHIVE_GROUP = "argus-archive";

  public static final String HOT_GROUP = "argus-hot";

  public KafkaProperties {
    topic = topic == null || topic.isBlank() ? "argus-wal" : topic;
    checkTopic = checkTopic != null ? checkTopic : Check.FAIL;
    partitions = partitions > 0 ? partitions : 12;
    replicationFactor = replicationFactor > 0 ? replicationFactor : 3;
    minRetention = minRetention != null ? minRetention : Duration.ofDays(7);
    maxOutage = maxOutage != null ? maxOutage : Duration.ofMinutes(10);
    maxLagRecords = maxLagRecords > 0 ? maxLagRecords : 100_000_000L;
    orphanTimeout = orphanTimeout != null ? orphanTimeout : Duration.ofMinutes(15);
    commitInterval = commitInterval != null ? commitInterval : Duration.ofSeconds(5);
    hotLinger = hotLinger != null ? hotLinger : Duration.ofSeconds(1);
    retryInitial = retryInitial != null ? retryInitial : Duration.ofSeconds(1);
    retryMax = retryMax != null ? retryMax : Duration.ofMinutes(5);
    stagingDir = stagingDir != null ? stagingDir : Path.of("data", "kafka-staging");
    properties = properties != null ? Map.copyOf(properties) : Map.of();
    if (maxOutage.toMillis() < 60_000) {
      throw new IllegalArgumentException(
          "argus.kafka.max-outage must be at least 1m (it bounds the producer's delivery timeout): "
              + maxOutage);
    }
  }

  /** Never prints {@code properties}: they may hold SASL credentials. */
  @Override
  public String toString() {
    return "KafkaProperties[bootstrapServers="
        + bootstrapServers
        + ", topic="
        + topic
        + ", partitions="
        + partitions
        + ", replicationFactor="
        + replicationFactor
        + "]";
  }
}
