package dev.dinesh.argus.ingest;

import dev.dinesh.argus.config.InstanceProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * The shutdown order of {@code P6_PLAN.md} D12, run when the context starts closing (SIGTERM),
 * before any lifecycle bean stops:
 *
 * <ol>
 *   <li>readiness goes down ({@code /readyz} 503), so the load balancer stops sending pushes;
 *   <li>pushes still arriving get 503 + {@code Retry-After: 1} (agents retry elsewhere), and those
 *       in progress finish;
 *   <li>every spool is ingested to its end (into the WAL: with Kafka, onto the topic).
 * </ol>
 *
 * Then the lifecycle beans stop as before: the consumers commit and leave their groups, and the
 * producer flushes on close. Bounded by {@code argus.instance.drain-timeout}; a spool not drained
 * by then stays on the instance's volume and is ingested at its next start. One canonical line.
 */
@Slf4j
@Component
public class InstanceDrain implements ApplicationListener<ContextClosedEvent> {

  private final ObjectProvider<PushIngestService> push;
  private final ObjectProvider<StreamRunner> runner;
  private final ApplicationEventPublisher events;
  private final Duration timeout;
  private final AtomicBoolean drained = new AtomicBoolean();

  public InstanceDrain(
      ObjectProvider<PushIngestService> push,
      ObjectProvider<StreamRunner> runner,
      ObjectProvider<InstanceProperties> instance,
      ApplicationEventPublisher events) {
    this.push = push;
    this.runner = runner;
    this.events = events;
    this.timeout = instance.getIfAvailable(() -> InstanceProperties.NONE).drainTimeout();
  }

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    // The management server's child context closes too, and its event reaches this listener.
    if (event.getApplicationContext().getParent() != null || !drained.compareAndSet(false, true)) {
      return;
    }
    long start = System.nanoTime();
    long deadline = start + timeout.toNanos();
    boolean pushesFinished = true;
    boolean spoolsDrained = true;
    Throwable error = null;
    try {
      AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
      PushIngestService p = push.getIfAvailable();
      if (p != null) {
        pushesFinished = p.drain(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
      }
      StreamRunner r = runner.getIfAvailable();
      if (r != null && r.isRunning()) {
        spoolsDrained = r.drain(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = e;
    } catch (RuntimeException e) {
      error = e;
    } finally {
      // Canonical line (guardrail 9).
      log.info(
          "Instance drain finished pushesFinished={} spoolsDrained={} error={} durationMs={}",
          pushesFinished,
          spoolsDrained,
          error == null ? null : error.toString(),
          (System.nanoTime() - start) / 1_000_000);
    }
  }
}
