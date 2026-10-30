package dev.dinesh.argus.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ArgusMetricsTest {

  /** {@code header} mode accepts any tenant: past the cap, a new tenant's meters record nothing. */
  @Test
  void theTenantTagIsCapped() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    registry
        .config()
        .meterFilter(
            MeterFilter.maximumAllowableTags(
                ArgusMetrics.PREFIX, ArgusMetrics.TENANT, 2, MeterFilter.deny()));
    ArgusMetrics metrics = new ArgusMetrics(registry);

    for (String tenant : new String[] {"a", "b", "c", "a"}) {
      metrics.suppressionDecision(tenant, true);
    }

    assertEquals(2, registry.find("argus.suppression.decisions").counters().size());
    assertEquals(
        2, registry.get("argus.suppression.decisions").tag("tenant", "a").counter().count());
    assertTrue(
        registry.find("argus.suppression.decisions").tag("tenant", "c").counters().isEmpty());
  }

  @Test
  void zeroCountsCreateNoMeters() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new ArgusMetrics(registry).ingestBatch("acme", "live", 5, 0, 0, 0, 100);

    assertEquals(1, registry.find("argus.ingest.lines").counters().size());
    assertEquals(100, registry.get("argus.ingest.bytes").counter().count());
  }

  @Test
  void archiveBytesCountOnlyArchivedSegments() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ArgusMetrics metrics = new ArgusMetrics(registry);

    metrics.archiveSegment("FAILED", 50, 0);
    metrics.archiveSegment("FAILED", 50, 0);
    metrics.archiveSegment("ARCHIVED", 50, 1_000);

    assertEquals(50, registry.get("argus.archive.bytes").counter().count());
    assertEquals(
        2, registry.get("argus.archive.segments").tag("outcome", "failed").counter().count());
  }
}
