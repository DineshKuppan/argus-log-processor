package dev.dinesh.argus.config;

import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import dev.dinesh.argus.wal.WatermarkWalCapacity;
import java.util.EnumSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * The local WAL and its routed sinks ({@code P5_PLAN.md} D1, ADR-7), when an archive or a hot store
 * drains it ({@link KafkaConfig} for {@code argus.wal.store=kafka}). Without either, the local
 * segments are the output and nothing here exists ({@link
 * dev.dinesh.argus.sink.WalCapacity#UNBOUNDED}).
 */
@Slf4j
@Configuration
@Conditional(WalConfig.Drained.class)
@ConditionalOnProperty(name = SinkConfig.LOCAL_WAL, havingValue = "local", matchIfMissing = true)
public class WalConfig {

  /** True when {@code argus.archive.store} or {@code argus.hot.store} names a store. */
  static final class Drained
      extends org.springframework.boot.autoconfigure.condition.AnyNestedCondition {
    Drained() {
      super(ConfigurationPhase.REGISTER_BEAN);
    }

    @Conditional(ArchiveConfig.Enabled.class)
    static final class Archive {}

    @Conditional(HotConfig.Enabled.class)
    static final class Hot {}
  }

  /** One instance per WAL volume: its sinks' acknowledgements are decided under its lock. */
  @Bean
  WalSegments walSegments(
      LocalSinkProperties local,
      ArchiveProperties archive,
      HotProperties hot,
      InstanceProperties instance) {
    Set<WalSink> routed = EnumSet.noneOf(WalSink.class);
    if (archive.store() != ArchiveProperties.Store.NONE) {
      routed.add(WalSink.ARCHIVE);
    }
    if (hot.enabled()) {
      routed.add(WalSink.HOT);
    }
    // Without an archive the segments are the cold output: the hot sink only marks them.
    boolean delete = routed.contains(WalSink.ARCHIVE) && archive.deleteAfterUpload();
    log.info(
        "WAL {} routed to {} (delete when every sink has acknowledged: {})",
        local.root(),
        routed,
        delete);
    return new WalSegments(local.root(), routed, delete, instance.id());
  }

  /** Ingest waits on this before each segment (P2 D7): the sinks are what drain the WAL. */
  @Bean
  WatermarkWalCapacity walCapacity(WalSegments wal, WalProperties limits) {
    return new WatermarkWalCapacity(wal, limits);
  }
}
