package dev.dinesh.argus.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.engine.SearchEngineClient.Flavor;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One OpenSearch 2.17 and one Elasticsearch 8.15 container per test JVM, started on first use and
 * shared by the hot-tier tests (Testcontainers' reaper stops them). Tests using them are skipped
 * without Docker.
 */
public final class EngineContainers {

  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private static final Map<Flavor, GenericContainer<?>> STARTED = new EnumMap<>(Flavor.class);

  private EngineContainers() {}

  public static void assumeDocker() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
  }

  public static synchronized String url(Flavor flavor) {
    assumeDocker();
    GenericContainer<?> c = STARTED.computeIfAbsent(flavor, EngineContainers::start);
    return "http://" + c.getHost() + ":" + c.getMappedPort(9200);
  }

  public static SearchEngineClient client(Flavor flavor) {
    return new SearchEngineClient(
        URI.create(url(flavor)),
        null,
        null,
        null,
        Duration.ofSeconds(30),
        HttpClient.newHttpClient(),
        MAPPER);
  }

  private static GenericContainer<?> start(Flavor flavor) {
    GenericContainer<?> c =
        flavor == Flavor.ELASTICSEARCH
            ? new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.15.3")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            : new GenericContainer<>("opensearchproject/opensearch:2.17.1")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");
    c.withEnv("discovery.type", "single-node")
        .withExposedPorts(9200)
        .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));
    c.start();
    return c;
  }
}
