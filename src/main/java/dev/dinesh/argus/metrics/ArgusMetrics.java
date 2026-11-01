package dev.dinesh.argus.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Argus's own meters ({@code P4_PLAN.md} D5). Each method is called once per batch, request,
 * segment or page, where that unit's canonical line is emitted and from the same values, so the
 * metrics and the logs never disagree. Thread-safe.
 */
public class ArgusMetrics {

  private static final long BLOCKED_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

  public static final String PREFIX = "argus.";
  public static final String TENANT = "tenant";

  private final MeterRegistry registry;

  public ArgusMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** Records nothing: for components built outside Spring (tests, load runners). */
  public static ArgusMetrics noop() {
    return new ArgusMetrics(new CompositeMeterRegistry());
  }

  public MeterRegistry registry() {
    return registry;
  }

  /** One parsed batch of a pipeline run. */
  public void ingestBatch(
      String tenant,
      String lane,
      int primary,
      int fallback,
      int unparsed,
      int rejected,
      long bytes) {
    Tags tags = Tags.of(TENANT, tenant, "lane", lane);
    count("argus.ingest.lines", tags.and("outcome", "primary"), primary);
    count("argus.ingest.lines", tags.and("outcome", "fallback"), fallback);
    count("argus.ingest.lines", tags.and("outcome", "unparsed"), unparsed);
    count("argus.ingest.lines", tags.and("outcome", "rejected"), rejected);
    count("argus.ingest.bytes", tags, bytes);
  }

  /** A WAL segment made durable and checkpointed. */
  public void segmentCommitted(String tenant, long commitNanos) {
    registry.counter("argus.ingest.segments", TENANT, tenant).increment();
    registry.timer("argus.ingest.segment.commit").record(commitNanos, TimeUnit.NANOSECONDS);
  }

  /** One push request, accepted or not. */
  public void pushRequest(
      String tenant, String outcome, long nanos, int accepted, int suppressed, int truncated) {
    String value = outcome.toLowerCase(Locale.ROOT);
    registry.counter("argus.push.requests", TENANT, tenant, "outcome", value).increment();
    registry.timer("argus.push.request", "outcome", value).record(nanos, TimeUnit.NANOSECONDS);
    Tags tags = Tags.of(TENANT, tenant);
    count("argus.push.lines", tags.and("result", "accepted"), accepted);
    count("argus.push.lines", tags.and("result", "suppressed"), suppressed);
    count("argus.push.lines", tags.and("result", "truncated"), truncated);
  }

  public void suppressionDecision(String tenant, boolean allowed) {
    registry
        .counter(
            "argus.suppression.decisions", TENANT, tenant, "decision", allowed ? "allow" : "deny")
        .increment();
  }

  /**
   * One WAL segment's archive attempt. Bytes count only for an archived segment, so retries during
   * an outage don't look like throughput.
   */
  public void archiveSegment(String outcome, long parquetBytes, long uploadNanos) {
    String tag = outcome.toLowerCase(Locale.ROOT);
    registry.counter("argus.archive.segments", "outcome", tag).increment();
    if (tag.equals("archived")) {
      count("argus.archive.bytes", Tags.empty(), parquetBytes);
    }
    if (uploadNanos > 0) {
      registry.timer("argus.archive.upload").record(uploadNanos, TimeUnit.NANOSECONDS);
    }
  }

  /** One export page appended to its stream. */
  public void exportPage(String tenant, String source, int lines) {
    registry.counter("argus.export.pages", TENANT, tenant, "source", source).increment();
    count("argus.export.lines", Tags.of(TENANT, tenant, "source", source), lines);
  }

  public void exportRetry(String tenant, String source) {
    registry.counter("argus.export.retries", TENANT, tenant, "source", source).increment();
  }

  /** Time a replay-lane reader waited for the shared read budget. */
  public void replayWait(long nanos) {
    registry.timer("argus.replay.wait").record(nanos, TimeUnit.NANOSECONDS);
  }

  public void tailSent(String tenant, long lines) {
    count("argus.tail.lines.sent", Tags.of(TENANT, tenant), lines);
  }

  public void tailDropped(String tenant, long lines) {
    count("argus.tail.lines.dropped", Tags.of(TENANT, tenant), lines);
  }

  /** Tail lines the bus couldn't take (its buffer full or the topic unavailable, P6 D10). */
  public void tailBusDropped(String tenant, long lines) {
    count("argus.tail.bus.dropped", Tags.of(TENANT, tenant), lines);
  }

  /** One WAL segment indexed into the hot tier ({@code P5_PLAN.md} D2). */
  public void hotSegment(String tenant, String outcome, long indexed, long rejected, long skipped) {
    registry.counter("argus.hot.segments", "outcome", outcome.toLowerCase(Locale.ROOT)).increment();
    count("argus.hot.documents", Tags.of(TENANT, tenant, "outcome", "indexed"), indexed);
    count("argus.hot.documents", Tags.of(TENANT, tenant, "outcome", "rejected"), rejected);
    count("argus.hot.documents", Tags.of(TENANT, tenant, "outcome", "outside_retention"), skipped);
  }

  /** One {@code _bulk} request. */
  public void hotBulk(boolean ok, long nanos) {
    registry
        .timer("argus.hot.bulk", "outcome", ok ? "ok" : "failed")
        .record(nanos, TimeUnit.NANOSECONDS);
  }

  /**
   * A WAL record send that waited for room in the producer's buffer ({@code P6_PLAN.md} D5); sends
   * that didn't wait (under 1ms) aren't recorded, so the timer counts only backpressure.
   */
  public void kafkaSendBlocked(long nanos) {
    if (nanos >= BLOCKED_NANOS) {
      registry.timer("argus.kafka.send.blocked").record(nanos, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * A WAL segment on the topic: {@code side} {@code producer} (committed, aborted) or {@code
   * archive} (archived, orphaned, ...).
   */
  public void kafkaSegment(String side, String outcome) {
    registry
        .counter("argus.kafka.segments", "side", side, "outcome", outcome.toLowerCase(Locale.ROOT))
        .increment();
  }

  /** One search request ({@code P5_PLAN.md} D6). */
  public void search(String tenant, String outcome, long nanos) {
    registry
        .timer("argus.search.requests", TENANT, tenant, "outcome", outcome)
        .record(nanos, TimeUnit.NANOSECONDS);
  }

  private void count(String name, Tags tags, long amount) {
    if (amount > 0) {
      registry.counter(name, tags).increment(amount);
    }
  }
}
