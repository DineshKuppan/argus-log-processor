package dev.dinesh.argus.config;

import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.hot.HotIndexer;
import dev.dinesh.argus.ingest.IngestJobService;
import dev.dinesh.argus.ingest.ReplayLane;
import dev.dinesh.argus.ingest.StreamRegistry;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.quota.QuotaStore;
import dev.dinesh.argus.quota.RedisQuotaStore;
import dev.dinesh.argus.suppression.RedisSuppressionStore;
import dev.dinesh.argus.suppression.SuppressionStore;
import dev.dinesh.argus.tail.TailHub;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import dev.dinesh.argus.wal.WatermarkWalCapacity;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import java.io.IOException;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Argus's meters ({@code P4_PLAN.md} D5): the {@link ArgusMetrics} counters, gauges over state the
 * components already keep, and a cap on the {@code tenant} tag's values.
 */
@Configuration
public class MetricsConfig {

  @Bean
  ArgusMetrics argusMetrics(MeterRegistry registry) {
    return new ArgusMetrics(registry);
  }

  /**
   * Tenants come from configured API keys, so they are few; {@code header} mode accepts any tenant
   * name, and each would otherwise create meters forever.
   */
  @Bean
  MeterFilter tenantTagLimit(@Value("${argus.metrics.max-tenants:100}") int maxTenants) {
    return MeterFilter.maximumAllowableTags(
        ArgusMetrics.PREFIX, ArgusMetrics.TENANT, maxTenants, MeterFilter.deny());
  }

  @Bean
  MeterBinder argusGauges(
      StreamRegistry streams,
      IngestJobService jobs,
      ReplayLane replay,
      ObjectProvider<WatermarkWalCapacity> wal,
      ObjectProvider<WalSegments> walSegments,
      ObjectProvider<Archiver> archiver,
      ObjectProvider<HotIndexer> hotIndexer,
      SuppressionStore suppressionStore,
      QuotaStore quotaStore,
      TailHub tail) {
    return registry -> {
      Gauge.builder("argus.tail.connections", tail, TailHub::connections)
          .description("Open live tail connections")
          .register(registry);
      Gauge.builder("argus.spool.pending", streams, StreamRegistry::pendingBytes)
          .baseUnit("bytes")
          .description("Acknowledged push bytes not yet ingested")
          .register(registry);
      Gauge.builder("argus.ingest.jobs.active", jobs, IngestJobService::activeJobs)
          .tag("lane", "live")
          .register(registry);
      Gauge.builder("argus.ingest.jobs.active", replay, ReplayLane::activeJobs)
          .tag("lane", "replay")
          .register(registry);
      wal.ifAvailable(
          capacity -> {
            Gauge.builder("argus.wal.pending", capacity, WatermarkWalCapacity::pendingBytes)
                .baseUnit("bytes")
                .description("WAL bytes some routed sink hasn't acknowledged")
                .register(registry);
            Gauge.builder("argus.wal.full", capacity, c -> c.isFull() ? 1 : 0)
                .description("1 while ingest waits for the sinks to drain the WAL")
                .register(registry);
          });
      walSegments.ifAvailable(
          segments -> {
            for (WalSink sink : segments.routed()) {
              Gauge.builder("argus.wal.sink.pending", segments, w -> sinkPendingBytes(w, sink))
                  .tag("sink", sink.name().toLowerCase(Locale.ROOT))
                  .baseUnit("bytes")
                  .description("WAL bytes this sink hasn't acknowledged")
                  .register(registry);
            }
          });
      archiver.ifAvailable(
          a ->
              Gauge.builder("argus.archive.oldest.pending", a, Archiver::oldestPendingSeconds)
                  .baseUnit("seconds")
                  .description("Age of the oldest WAL segment not yet archived; 0 when none")
                  .register(registry));
      hotIndexer.ifAvailable(
          h ->
              Gauge.builder("argus.hot.lag", h, HotIndexer::lagSeconds)
                  .baseUnit("seconds")
                  .description("Age of the oldest WAL segment not yet indexed; 0 when none")
                  .register(registry));
      if (suppressionStore instanceof RedisSuppressionStore redis) {
        FunctionCounter.builder(
                "argus.store.fail.open", redis, RedisSuppressionStore::failOpenCount)
            .tag("store", "suppression")
            .description("Decisions allowed because Redis failed")
            .register(registry);
      }
      if (quotaStore instanceof RedisQuotaStore redis) {
        FunctionCounter.builder("argus.store.fail.open", redis, RedisQuotaStore::failOpenCount)
            .tag("store", "quota")
            .description("Requests allowed because Redis failed")
            .register(registry);
      }
    };
  }

  private static double sinkPendingBytes(WalSegments wal, WalSink sink) {
    try {
      return WalSegments.bytes(wal.pending(sink));
    } catch (IOException | RuntimeException e) {
      return Double.NaN;
    }
  }
}
