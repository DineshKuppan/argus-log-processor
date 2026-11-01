package dev.dinesh.argus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.sink.LogSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The sink ingest writes segments to: the local volume (the WAL since P2, the output without a
 * store), unless {@code argus.wal.store=kafka} ({@link KafkaConfig}).
 */
@Configuration
public class SinkConfig {

  /** {@code argus.wal.store} is {@code local}, or not set. */
  static final String LOCAL_WAL = "argus.wal.store";

  @Bean
  @ConditionalOnProperty(name = LOCAL_WAL, havingValue = "local", matchIfMissing = true)
  LogSink localVolumeSink(LocalSinkProperties props, ObjectMapper mapper) {
    return new LocalVolumeSink(props, mapper);
  }
}
