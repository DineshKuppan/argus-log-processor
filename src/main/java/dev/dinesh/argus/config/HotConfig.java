package dev.dinesh.argus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.hot.HotIndexer;
import dev.dinesh.argus.hot.HotIndices;
import dev.dinesh.argus.hot.HotTemplates;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.wal.WalSegments;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the hot search tier ({@code P5_PLAN.md}) when {@code argus.hot.store} names an engine. With
 * {@code none} (the default) nothing here is created, and search answers 404.
 */
@Slf4j
@Configuration
@Conditional(HotConfig.Enabled.class)
public class HotConfig {

  /** {@code argus.hot.store} names an engine (not blank, not {@code none}). */
  static final class Enabled extends StoreCondition {
    Enabled() {
      super("argus.hot.store");
    }
  }

  /**
   * A cluster that answers but is the wrong engine or too old fails startup; one that doesn't
   * answer only warns: the indexer retries, and the WAL holds the lines meanwhile.
   */
  @Bean
  SearchEngineClient hotClient(HotProperties props, ObjectMapper mapper) {
    SearchEngineClient client =
        new SearchEngineClient(
            props.url(),
            props.apiKey(),
            props.username(),
            props.password(),
            props.requestTimeout(),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            mapper);
    try {
      HotTemplates.requireSupported(client.engine(), props.store());
    } catch (IOException e) {
      log.warn(
          "Hot tier {} not reachable at startup ({}); indexing starts once it is",
          client.base(),
          e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return client;
  }

  @Bean
  HotIndices hotIndices(HotProperties props) {
    return new HotIndices(props);
  }

  @Bean
  HotIndexer hotIndexer(
      SearchEngineClient hotClient,
      WalSegments wal,
      HotIndices indices,
      HotProperties props,
      ObjectProvider<ArgusMetrics> metrics) {
    return new HotIndexer(
        hotClient, wal, indices, props, metrics.getIfAvailable(ArgusMetrics::noop));
  }
}
