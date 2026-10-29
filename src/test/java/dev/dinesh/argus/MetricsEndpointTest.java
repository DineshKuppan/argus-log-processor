package dev.dinesh.argus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The Prometheus scrape and health on the management port; probes also on the API port. Tests turn
 * metrics export off unless asked: {@code @AutoConfigureObservability}.
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "spring.data.redis.port=1"})
class MetricsEndpointTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort int apiPort;
  @LocalManagementPort int managementPort;

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void prometheusListsArgusMetersOnTheManagementPortOnly() throws Exception {
    HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
    assertEquals(200, scrape.statusCode());
    assertTrue(scrape.body().contains("argus_spool_pending_bytes"), scrape.body());
    assertTrue(scrape.body().contains("argus_ingest_jobs_active{lane=\"replay\"}"));
    assertTrue(scrape.body().contains("jvm_memory_used_bytes"));
    assertEquals(404, get(apiPort, "/actuator/prometheus").statusCode());
  }

  /** Redis is unreachable here (port 1), and with the local stores that doesn't matter. */
  @Test
  void healthIsUpWithoutRedisAndProbesAreOnTheApiPort() throws Exception {
    HttpResponse<String> health = get(managementPort, "/actuator/health");
    assertEquals(200, health.statusCode(), health.body());
    assertTrue(health.body().contains("\"status\":\"UP\""), health.body());
    assertEquals(200, get(apiPort, "/readyz").statusCode());
    assertEquals(200, get(apiPort, "/livez").statusCode());
  }
}
