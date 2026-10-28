package dev.dinesh.argus.archive;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The GCS store against fake-gcs-server. Skipped when Docker is not available.
 *
 * <p>fake-gcs-server puts its own URL in each resumable session's {@code Location}, so it must know
 * the port the test reaches it on: hence a fixed host port. It also finalizes an upload on a status
 * query ({@code bytes *}{@code /N}) where GCS answers 308, so the resume path is tested in {@link
 * GcsResumableUploadTest} instead.
 */
class GcsObjectStoreTest extends ObjectStoreContractTest {

  private static final String BUCKET = "archive";

  private static FixedHostPortGenericContainer<?> gcs;
  private static URI endpoint;

  @BeforeAll
  @SuppressWarnings("deprecation") // FixedHostPortGenericContainer: see the class comment
  static void start() throws Exception {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    gcs =
        new FixedHostPortGenericContainer<>("fsouza/fake-gcs-server:1.52.2")
            .withFixedExposedPort(port, 4443)
            .withCommand(
                "-scheme",
                "http",
                "-port",
                "4443",
                "-backend",
                "memory",
                "-external-url",
                "http://localhost:" + port)
            .waitingFor(Wait.forHttp("/storage/v1/b").forPort(4443));
    gcs.start();
    endpoint = URI.create("http://localhost:" + port);
    HttpResponse<String> created =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(endpoint + "/storage/v1/b?project=test"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + BUCKET + "\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    if (created.statusCode() != 200) {
      throw new IOException("Could not create the bucket: " + created.body());
    }
  }

  @AfterAll
  static void stop() {
    if (gcs != null) {
      gcs.stop();
    }
  }

  @Override
  protected ObjectStore store() {
    return new GcsObjectStore(
        endpoint, BUCKET, null, GcsObjectStore.DEFAULT_CHUNK_BYTES, Duration.ofMillis(10));
  }

  @Override
  protected byte[] read(String key) throws Exception {
    HttpResponse<byte[]> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            endpoint
                                + "/storage/v1/b/"
                                + BUCKET
                                + "/o/"
                                + URLEncoder.encode(key, StandardCharsets.UTF_8)
                                + "?alt=media"))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("GET " + key + ": HTTP " + response.statusCode());
    }
    return response.body();
  }
}
