package dev.dinesh.argus.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.tail.TailBus;
import dev.dinesh.argus.tail.TailEvent;
import dev.dinesh.argus.tail.TailHub;
import dev.dinesh.argus.tenant.TenantIds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.utils.Utils;
import org.springframework.context.SmartLifecycle;

/**
 * The shared live tail ({@code P6_PLAN.md} D10): every instance's tail lines go through the tail
 * topic, keyed and partitioned by tenant, and every instance reads every partition from its end
 * into its {@link TailHub}'s rings. A tenant is watched while some instance sent a {@code watch}
 * heartbeat for it in the last {@link #WATCH_TTL}, so ingest still taps only tailed tenants.
 *
 * <p>Never waits on Kafka (guardrail 7): the producer has {@code max.block.ms=0} and a small
 * buffer, and a line it can't take is dropped and counted ({@code argus_tail_bus_dropped_total}).
 * Event ids are {@code p<partition>-<offset>}, the same on every instance.
 */
@Slf4j
public final class KafkaTailBus implements TailBus, SmartLifecycle {

  static final String TYPE = "argus-tail";
  static final String WATCH = "watch";
  static final Duration HEARTBEAT = Duration.ofSeconds(5);
  static final Duration WATCH_TTL = Duration.ofSeconds(15);

  private static final Duration POLL = Duration.ofMillis(100);
  private static final long STOP_TIMEOUT_SECONDS = 10;
  private static final int BUFFER_BYTES = 8 * 1024 * 1024;

  private final KafkaWal wal;
  private final String topic;
  private final TailHub hub;
  private final ArgusMetrics metrics;
  private final ObjectWriter writer;
  private final ObjectReader reader;
  private final Function<Map<String, Object>, Producer<byte[], byte[]>> producers;
  private final Function<Map<String, Object>, Consumer<byte[], byte[]>> consumers;
  private final Map<String, Long> watchedUntil = new ConcurrentHashMap<>();

  private volatile int partitions;
  private volatile boolean running;
  private volatile Producer<byte[], byte[]> producer;
  private volatile Consumer<byte[], byte[]> consumer;
  private Thread worker;

  public KafkaTailBus(KafkaWal wal, TailHub hub, ObjectMapper mapper, ArgusMetrics metrics) {
    this(wal, hub, mapper, metrics, KafkaProducer::new, KafkaConsumer::new);
  }

  KafkaTailBus(
      KafkaWal wal,
      TailHub hub,
      ObjectMapper mapper,
      ArgusMetrics metrics,
      Function<Map<String, Object>, Producer<byte[], byte[]>> producers,
      Function<Map<String, Object>, Consumer<byte[], byte[]>> consumers) {
    this.wal = wal;
    this.topic = wal.props().tailTopic();
    this.hub = hub;
    this.metrics = metrics;
    this.writer = mapper.writerFor(TailEvent.class);
    this.reader = mapper.readerFor(TailEvent.class);
    this.producers = producers;
    this.consumers = consumers;
  }

  /**
   * Finds the topic's partitions, then attaches to the hub and starts reading. A cluster that
   * doesn't answer leaves the tail local until it does (retried in the background).
   */
  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    worker = new Thread(this::run, "kafka-tail");
    worker.setDaemon(true);
    worker.start();
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    Consumer<byte[], byte[]> c = consumer;
    if (c != null) {
      c.wakeup();
    }
    try {
      worker.join(TimeUnit.SECONDS.toMillis(STOP_TIMEOUT_SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    Producer<byte[], byte[]> p = producer;
    if (p != null) {
      p.close(Duration.ofSeconds(1));
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Before the hub stops, so its connections close after the bus. */
  @Override
  public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE + 1;
  }

  @Override
  public void publish(String tenantId, List<TailEvent> events) {
    Producer<byte[], byte[]> p = producer;
    if (p == null) {
      metrics.tailBusDropped(tenantId, events.size());
      return;
    }
    byte[] key = KafkaWal.utf8(tenantId);
    int partition = partition(tenantId);
    long dropped = 0;
    for (TailEvent event : events) {
      try {
        p.send(new ProducerRecord<>(topic, partition, key, writer.writeValueAsBytes(event)));
      } catch (IOException | KafkaException e) {
        // A full buffer (max.block.ms=0) or no metadata yet: the tail loses the line, not ingest.
        dropped++;
      }
    }
    metrics.tailBusDropped(tenantId, dropped);
  }

  @Override
  public boolean isWatched(String tenantId) {
    Long until = watchedUntil.get(tenantId);
    return until != null && System.nanoTime() - until < 0;
  }

  @Override
  public void watch(String tenantId) {
    Producer<byte[], byte[]> p = producer;
    if (p == null) {
      return;
    }
    RecordHeaders headers = new RecordHeaders();
    headers.add(TYPE, KafkaWal.utf8(WATCH));
    try {
      p.send(
          new ProducerRecord<>(
              topic, partition(tenantId), null, KafkaWal.utf8(tenantId), null, headers));
    } catch (KafkaException e) {
      log.debug("Watch heartbeat for {} not sent: {}", tenantId, e.toString());
    }
  }

  @Override
  public String ringId(String tenantId) {
    return "p" + partition(tenantId);
  }

  /** The default partitioner's choice for the key, fixed so ring ids are known before any line. */
  int partition(String tenantId) {
    return Utils.toPositive(Utils.murmur2(KafkaWal.utf8(tenantId))) % partitions;
  }

  private void run() {
    int failures = 0;
    while (running) {
      try {
        partitions = wal.ensureTailTopic();
        if (producer == null) {
          Producer<byte[], byte[]> p = producers.apply(producerConfig());
          warmUp(p);
          producer = p;
          hub.attach(this);
          log.info("Live tail shared through Kafka topic {} ({} partitions)", topic, partitions);
        }
        consume();
        failures = 0;
      } catch (WakeupException e) {
        // stop()
      } catch (Throwable t) {
        if (!running) {
          break;
        }
        failures++;
        long wait = Math.min(30_000, 1000L << Math.min(failures, 5));
        log.warn(
            "Kafka tail bus failed ({} in a row); retrying in {}ms: {}",
            failures,
            wait,
            t.toString());
        sleep(wait);
      }
    }
  }

  /**
   * With {@code max.block.ms=0} a send fails while the producer has no metadata for the topic (it
   * then fetches it), so the metadata is fetched before the first line, and kept (see {@code
   * metadata.max.idle.ms}).
   */
  private void warmUp(Producer<byte[], byte[]> p) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (running && System.nanoTime() < deadline) {
      try {
        p.partitionsFor(topic);
        return;
      } catch (KafkaException e) {
        sleep(50);
      }
    }
    log.warn("No metadata for Kafka tail topic {} yet; the first tail lines may be dropped", topic);
  }

  private void consume() {
    try (Consumer<byte[], byte[]> c = consumers.apply(consumerConfig())) {
      consumer = c;
      List<TopicPartition> all = new ArrayList<>();
      for (int p = 0; p < partitions; p++) {
        all.add(new TopicPartition(topic, p));
      }
      c.assign(all);
      c.seekToEnd(all); // a tail shows lines from now on
      long nextHeartbeat = 0;
      while (running) {
        long now = System.nanoTime();
        if (now - nextHeartbeat >= 0) {
          nextHeartbeat = now + HEARTBEAT.toNanos();
          hub.tailedTenants().forEach(this::watch);
        }
        deliver(c.poll(POLL));
      }
    } finally {
      consumer = null;
    }
  }

  /** Groups the records by tenant, in order, and hands them to the hub. */
  private void deliver(Iterable<ConsumerRecord<byte[], byte[]>> records) {
    Map<String, List<ConsumerRecord<byte[], byte[]>>> byTenant = new LinkedHashMap<>();
    for (ConsumerRecord<byte[], byte[]> r : records) {
      if (r.key() == null) {
        continue;
      }
      String tenant = new String(r.key(), StandardCharsets.UTF_8);
      if (WATCH.equals(KafkaWal.header(r.headers(), TYPE))) {
        watchedUntil.put(tenant, System.nanoTime() + WATCH_TTL.toNanos());
      } else if (r.value() != null && hub.hasRing(tenant)) {
        byTenant.computeIfAbsent(tenant, t -> new ArrayList<>()).add(r);
      }
    }
    byTenant.forEach(
        (tenant, rs) -> {
          List<TailEvent> events = new ArrayList<>(rs.size());
          long[] positions = new long[rs.size()];
          int n = 0;
          for (ConsumerRecord<byte[], byte[]> r : rs) {
            try {
              TailEvent e = reader.readValue(r.value());
              events.add(e.withWeight(TailEvent.weightOfJson(r.value().length)));
              positions[n++] = r.offset();
            } catch (IOException e) {
              log.debug("Skipped a tail record that isn't a tail event: offset={}", r.offset());
            }
          }
          if (n > 0) {
            hub.deliver(
                TenantIds.requireValid(tenant),
                events,
                n == positions.length ? positions : Arrays.copyOf(positions, n));
          }
        });
    watchedUntil.entrySet().removeIf(e -> System.nanoTime() - e.getValue() > 0);
  }

  private Map<String, Object> producerConfig() {
    Map<String, Object> config = new HashMap<>(wal.props().properties());
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, wal.props().bootstrapServers());
    config.put(ProducerConfig.CLIENT_ID_CONFIG, wal.clientId("argus-tail-producer"));
    config.put(ProducerConfig.ACKS_CONFIG, "1");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
    config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
    config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, (long) BUFFER_BYTES);
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 0L);
    // An idle topic's metadata would expire after 5m, and the next line be dropped.
    config.put(ProducerConfig.METADATA_MAX_IDLE_CONFIG, Duration.ofDays(1).toMillis());
    config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, KafkaWal.MAX_RECORD_BYTES);
    config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return config;
  }

  private Map<String, Object> consumerConfig() {
    Map<String, Object> config = new HashMap<>(wal.props().properties());
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, wal.props().bootstrapServers());
    config.put(ConsumerConfig.CLIENT_ID_CONFIG, wal.clientId("argus-tail"));
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
    config.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 50);
    config.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, KafkaWal.MAX_RECORD_BYTES);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    return config;
  }

  private void sleep(long millis) {
    long until = System.nanoTime() + millis * 1_000_000;
    try {
      while (running && System.nanoTime() < until) {
        Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
