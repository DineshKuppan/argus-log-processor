package dev.dinesh.argus.archive;

import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * The S3 store against S3Mock, an S3-compatible server (MinIO no longer publishes images to Docker
 * Hub). Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3ObjectStoreTest extends ObjectStoreContractTest {

  private static final String BUCKET = "archive";

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>(DockerImageName.parse("adobe/s3mock:4.1.1"))
          .withExposedPorts(9090)
          .waitingFor(
              Wait.forHttp("/favicon.ico").forPort(9090).forStatusCodeMatching(c -> c < 500));

  private static S3AsyncClient client;

  @BeforeAll
  static void connect() {
    client =
        S3ObjectStore.newClient(
            URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9090)),
            null,
            true,
            false,
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
    client.createBucket(b -> b.bucket(BUCKET)).join();
  }

  @AfterAll
  static void disconnect() {
    client.close();
  }

  @Override
  protected ObjectStore store() {
    return new S3ObjectStore(client, BUCKET);
  }

  @Override
  protected byte[] read(String key) {
    return client
        .getObject(b -> b.bucket(BUCKET).key(key), AsyncResponseTransformer.toBytes())
        .join()
        .asByteArray();
  }
}
