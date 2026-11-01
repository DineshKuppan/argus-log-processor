package dev.dinesh.argus.kafka;

import com.fasterxml.jackson.databind.ObjectReader;
import dev.dinesh.argus.config.KafkaProperties;
import dev.dinesh.argus.hot.HotBulkWriter;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.utils.Backoff;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.SmartLifecycle;

/**
 * The hot tier as consumer group {@code argus-hot} ({@code P6_PLAN.md} D4): records (markers
 * skipped) fill a bulk that is sent when full or after {@code hot-linger}, and the offsets past it
 * are committed only once every item was indexed, rejected or outside the retention. A failed bulk
 * pauses the partitions, backs off and is sent again whole ({@code _id = eventId} makes that
 * harmless); nothing is committed meanwhile. One {@code Hot bulk finished …} canonical line per
 * bulk.
 */
@Slf4j
public final class HotConsumer implements SmartLifecycle {

  private static final Duration POLL = Duration.ofMillis(200);
  private static final long STOP_TIMEOUT_SECONDS = 30;

  private final KafkaWal wal;
  private final KafkaProperties props;
  private final HotBulkWriter writer;
  private final ObjectReader envelopes;
  private final ArgusMetrics metrics;
  private final Clock eventClock;
  private final Clock clock;
  private final Function<Map<String, Object>, Consumer<byte[], byte[]>> consumers;

  private volatile boolean running;
  private volatile Consumer<byte[], byte[]> consumer;
  private volatile long lastRecordMillis;
  private Thread worker;

  // Owned by the worker thread.
  private final Deque<ConsumerRecord<byte[], byte[]>> pending = new ArrayDeque<>();
  private final Map<TopicPartition, Long> next = new HashMap<>();
  private HotBulkWriter.Bulk bulk;
  private HotBulkWriter.Tally tally;
  private Instant bulkStarted;
  private int failures;
  private Instant retryAt = Instant.MIN;
  private Instant lastCommit = Instant.MIN;

  public HotConsumer(
      KafkaWal wal, KafkaProperties props, HotBulkWriter writer, ArgusMetrics metrics) {
    this(wal, props, writer, metrics, KafkaConsumer::new);
  }

  HotConsumer(
      KafkaWal wal,
      KafkaProperties props,
      HotBulkWriter writer,
      ArgusMetrics metrics,
      Function<Map<String, Object>, Consumer<byte[], byte[]>> consumers) {
    this.wal = wal;
    this.props = props;
    this.writer = writer;
    this.envelopes = writer.mapper().readerFor(LogEnvelope.class);
    this.metrics = metrics;
    // Event days and retention follow the writer's clock; linger, backoff and commits real time.
    this.eventClock = writer.clock();
    this.clock = Clock.systemUTC();
    this.consumers = consumers;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    worker = new Thread(this::run, "kafka-hot");
    worker.setDaemon(true);
    worker.start();
    log.info("Kafka hot consumer started: {} → {}", wal.topic(), writer.client().base());
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
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  public long lastRecordMillis() {
    return lastRecordMillis;
  }

  private void run() {
    int restarts = 0;
    while (running) {
      try {
        wal.ensureTopic();
        writer.maintain();
        consume();
        restarts = 0;
      } catch (WakeupException e) {
        // stop()
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Throwable t) {
        if (!running) {
          break;
        }
        Duration wait = Backoff.jittered(props.retryInitial(), props.retryMax(), ++restarts);
        log.error(
            "Kafka hot consumer failed ({} in a row); restarting from the committed offsets in"
                + " {}ms",
            restarts,
            wait.toMillis(),
            t);
        sleep(wait);
      } finally {
        reset();
        pending.clear();
      }
    }
  }

  private void consume() throws IOException, InterruptedException {
    try (Consumer<byte[], byte[]> c =
        consumers.apply(wal.consumerConfig(KafkaProperties.HOT_GROUP, "argus-hot"))) {
      consumer = c;
      c.subscribe(List.of(wal.topic()), new Rebalance(c));
      while (running) {
        if (failures > 0) {
          if (clock.instant().isBefore(retryAt)) {
            c.poll(POLL); // paused: keeps the group membership
            continue;
          }
          if (!flush(c)) {
            continue;
          }
          c.resume(c.paused());
        }
        if (pending.isEmpty()) {
          for (ConsumerRecord<byte[], byte[]> r : c.poll(POLL)) {
            pending.add(r);
          }
        }
        while (!pending.isEmpty() && failures == 0) {
          ConsumerRecord<byte[], byte[]> r = pending.poll();
          add(r);
          if (bulk.full() && !flush(c)) {
            break;
          }
        }
        Instant now = clock.instant();
        if (failures == 0
            && bulk != null
            && !bulk.isEmpty()
            && Duration.between(bulkStarted, now).compareTo(props.hotLinger()) >= 0) {
          flush(c);
        }
        if (failures == 0
            && (bulk == null || bulk.isEmpty())
            && !next.isEmpty()
            && Duration.between(lastCommit, now).compareTo(props.commitInterval()) >= 0) {
          // Only markers or skipped records since the last bulk: move past them.
          commit(c);
        }
        writer.maintain();
      }
      if (failures == 0 && bulk != null && !bulk.isEmpty()) {
        flush(c);
      }
    } finally {
      consumer = null;
    }
  }

  private void add(ConsumerRecord<byte[], byte[]> r) throws IOException {
    lastRecordMillis = r.timestamp();
    if (bulk == null) {
      bulk = writer.newBulk();
      tally = new HotBulkWriter.Tally();
      bulkStarted = clock.instant();
    }
    next.put(new TopicPartition(r.topic(), r.partition()), r.offset() + 1);
    if (r.value() == null || KafkaWal.header(r.headers(), KafkaWal.TYPE) != null) {
      return; // a segment marker
    }
    LogEnvelope e;
    try {
      e = envelopes.readValue(r.value());
    } catch (IOException ex) {
      tally.rejected++;
      log.warn(
          "Skipped a record that isn't a log envelope: partition={} offset={} error={}",
          r.partition(),
          r.offset(),
          ex.getMessage());
      return;
    }
    bulk.add(e, eventClock.instant(), tally);
  }

  /** Sends the bulk and commits past it; on failure pauses and schedules the retry. */
  private boolean flush(Consumer<byte[], byte[]> c) throws InterruptedException {
    if (bulk == null || bulk.isEmpty()) {
      return true;
    }
    long start = System.nanoTime();
    String outcome = "FAILED";
    Throwable error = null;
    try {
      writer.send(bulk, tally);
      outcome = "INDEXED";
      failures = 0;
      retryAt = Instant.MIN;
      commit(c);
      return true;
    } catch (IOException | RuntimeException e) {
      error = e;
      failures++;
      Duration wait = Backoff.jittered(props.retryInitial(), props.retryMax(), failures);
      retryAt = clock.instant().plus(wait);
      c.pause(c.assignment());
      log.warn(
          "Hot bulk failed ({} in a row); partitions paused, retrying in {}ms",
          failures,
          wait.toMillis());
      return false;
    } finally {
      // Canonical line: one per bulk sent (guardrail 9).
      log.info(
          "Hot bulk finished outcome={} documents={} indexed={} rejected={} outsideRetention={}"
              + " itemRetries={} firstRejection={} attempt={} error={} durationMs={}",
          outcome,
          bulk.size(),
          tally.indexed,
          tally.rejected,
          tally.skipped,
          tally.retries,
          tally.firstRejection,
          failures == 0 ? 1 : failures,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      if (error == null) {
        tally.record(metrics, outcome);
        reset();
      }
    }
  }

  private void reset() {
    bulk = null;
    tally = null;
    bulkStarted = null;
  }

  private void commit(Consumer<byte[], byte[]> c) {
    lastCommit = clock.instant();
    if (next.isEmpty()) {
      return;
    }
    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    Set<TopicPartition> owned = c.assignment();
    next.forEach(
        (tp, offset) -> {
          if (owned.contains(tp)) {
            offsets.put(tp, new OffsetAndMetadata(offset));
          }
        });
    next.clear();
    try {
      c.commitSync(offsets);
    } catch (WakeupException e) {
      throw e;
    } catch (KafkaException e) {
      log.warn("Kafka hot offset commit failed: {}", e.toString());
    }
  }

  /**
   * On revocation the bulk is sent once more (its records may belong to the moved partitions); if
   * that fails, the partitions kept are rewound to their committed offsets, so nothing is skipped.
   */
  private final class Rebalance implements ConsumerRebalanceListener {
    private final Consumer<byte[], byte[]> c;

    Rebalance(Consumer<byte[], byte[]> c) {
      this.c = c;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
      if (partitions.isEmpty()) {
        return;
      }
      boolean sent;
      try {
        sent = failures == 0 && flush(c);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        sent = false;
      }
      if (!sent) {
        rewind(partitions);
      }
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
      rewind(partitions);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
      if (!partitions.isEmpty()) {
        log.info("Kafka hot consumer assigned {}", partitions);
      }
    }

    private void rewind(Collection<TopicPartition> gone) {
      reset();
      pending.clear();
      next.clear();
      failures = 0;
      retryAt = Instant.MIN;
      Set<TopicPartition> kept = new HashSet<>(c.assignment());
      kept.removeAll(gone);
      if (!kept.isEmpty()) {
        c.committed(kept)
            .forEach(
                (tp, offset) -> {
                  if (offset != null) {
                    c.seek(tp, offset);
                  } else {
                    c.seekToBeginning(List.of(tp));
                  }
                });
        c.resume(kept);
      }
    }
  }

  private void sleep(Duration wait) {
    long until = System.nanoTime() + wait.toNanos();
    try {
      while (running && System.nanoTime() < until) {
        Thread.sleep(Math.min(200, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
