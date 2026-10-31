package dev.dinesh.argus.kafka;

import dev.dinesh.argus.config.WalProperties;
import dev.dinesh.argus.sink.WalCapacity;
import dev.dinesh.argus.wal.WatermarkWalCapacity;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * {@link WalCapacity} from the sinks' consumer lag ({@code P6_PLAN.md} D5). Kafka deletes by
 * retention, not by acknowledgement, so a sink that falls far enough behind would lose lines:
 * ingest pauses when any routed group's lag reaches {@code high-watermark × max-lag-records}, and
 * resumes below the low watermark, with the same waits, statuses and 429s as the local WAL's
 * watermarks.
 *
 * <p>Lag is measured with the admin client every {@code refresh} on its own thread, so a push
 * request asking {@link #isFull} never waits on Kafka. If Kafka can't be asked, the last
 * measurement stands (none: not full): a broker outage is handled by blocking the writers (D5), not
 * here.
 */
@Slf4j
public final class KafkaWalCapacity implements WalCapacity, SmartLifecycle {

  private static final Duration REFRESH = Duration.ofSeconds(5);
  private static final Duration WARN_EVERY = Duration.ofMinutes(1);

  private final KafkaWal wal;
  private final Set<String> groups;
  private final WatermarkWalCapacity watermarks;
  private final Duration refresh;

  private volatile Map<String, Long> lag = Map.of();
  private long lastWarnAt = System.nanoTime() - WARN_EVERY.toNanos();
  private ScheduledExecutorService measurer;

  public KafkaWalCapacity(KafkaWal wal, Set<String> groups, WalProperties limits) {
    this(wal, groups, limits, REFRESH);
  }

  KafkaWalCapacity(KafkaWal wal, Set<String> groups, WalProperties limits, Duration refresh) {
    this.wal = wal;
    this.groups = Set.copyOf(groups);
    this.refresh = refresh;
    long max = wal.props().maxLagRecords();
    this.watermarks =
        new WatermarkWalCapacity(
            this::maxLag,
            (long) (max * limits.highWatermark()),
            (long) (max * limits.lowWatermark()),
            Duration.ofMillis(Math.min(1000, refresh.toMillis())),
            "records of consumer lag");
  }

  @Override
  public synchronized void start() {
    if (measurer != null) {
      return;
    }
    measurer =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "kafka-lag");
              t.setDaemon(true);
              return t;
            });
    measurer.scheduleWithFixedDelay(this::measure, 0, refresh.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (measurer != null) {
      measurer.shutdownNow();
      measurer = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return measurer != null;
  }

  @Override
  public boolean isFull() {
    return watermarks.isFull();
  }

  @Override
  public boolean awaitRoom(BooleanSupplier stopping, Consumer<Boolean> paused) {
    return watermarks.awaitRoom(stopping, paused);
  }

  /** The routed sinks' consumer groups. */
  public Set<String> groups() {
    return groups;
  }

  /** Each routed group's lag in records, as last measured. */
  public Map<String, Long> lag() {
    return lag;
  }

  public long lag(String group) {
    return lag.getOrDefault(group, 0L);
  }

  private long maxLag() {
    return lag.values().stream().mapToLong(Long::longValue).max().orElse(0);
  }

  /** One measurement; on failure the last one stands. */
  void measure() {
    try {
      lag = Map.copyOf(new LinkedHashMap<>(wal.lag(groups)));
    } catch (IOException | RuntimeException e) {
      long now = System.nanoTime();
      if (now - lastWarnAt >= WARN_EVERY.toNanos()) {
        lastWarnAt = now;
        log.warn(
            "Could not measure Kafka consumer lag; keeping the last measurement: {}", e.toString());
      }
    }
  }
}
