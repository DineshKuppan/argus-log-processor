package dev.dinesh.argus.archive;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;

/**
 * An {@link ObjectStore} on an S3 bucket (AWS S3 or an S3-compatible store). Large files are
 * uploaded in parts by the SDK's multipart client, read from disk part by part, so memory doesn't
 * grow with the file. Transient errors (throttling, 5xx) are retried by the SDK; a call that still
 * fails throws, and the caller retries later.
 */
public final class S3ObjectStore implements ObjectStore {

  /** Files above this go multipart, in parts of this size (S3's minimum part is 5MB). */
  static final long PART_BYTES = 16L * 1024 * 1024;

  /** Part data buffered in flight across concurrent part uploads. */
  private static final long IN_FLIGHT_BYTES = 2 * PART_BYTES;

  private final S3AsyncClient client;
  private final String bucket;

  /**
   * @param client should be built with {@code multipartEnabled(true)}; owned by the caller
   */
  public S3ObjectStore(S3AsyncClient client, String bucket) {
    this.client = client;
    this.bucket = bucket;
  }

  /**
   * Builds the async client this store needs.
   *
   * @param endpoint null for AWS; otherwise an S3-compatible endpoint
   * @param region null or blank to use the SDK's region chain (with an endpoint: {@code us-east-1})
   * @param checksumsWhenRequired send request checksums only when an API requires them (some
   *     S3-compatible stores reject the checksums SDK 2.30+ sends by default)
   * @param credentials null for the SDK's default chain (environment, profile, IRSA, instance role)
   */
  public static S3AsyncClient newClient(
      URI endpoint,
      String region,
      boolean pathStyle,
      boolean checksumsWhenRequired,
      AwsCredentialsProvider credentials) {
    S3AsyncClientBuilder builder =
        S3AsyncClient.builder()
            .multipartEnabled(true)
            .multipartConfiguration(
                MultipartConfiguration.builder()
                    .thresholdInBytes(PART_BYTES)
                    .minimumPartSizeInBytes(PART_BYTES)
                    .apiCallBufferSizeInBytes(IN_FLIGHT_BYTES)
                    .build())
            .forcePathStyle(pathStyle)
            .credentialsProvider(
                credentials != null ? credentials : DefaultCredentialsProvider.create())
            .requestChecksumCalculation(
                checksumsWhenRequired
                    ? RequestChecksumCalculation.WHEN_REQUIRED
                    : RequestChecksumCalculation.WHEN_SUPPORTED);
    if (endpoint != null) {
      builder.endpointOverride(endpoint);
    }
    if (region != null && !region.isBlank()) {
      builder.region(Region.of(region));
    } else if (endpoint != null) {
      builder.region(Region.US_EAST_1);
    }
    return builder.build();
  }

  @Override
  public String describe() {
    return "s3://" + bucket;
  }

  @Override
  public void put(String key, Path file) throws IOException {
    PutObjectRequest request =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(ObjectKeys.requireValid(key))
            .contentType(PARQUET_CONTENT_TYPE)
            .build();
    await(client.putObject(request, AsyncRequestBody.fromFile(file)), "put " + key);
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    List<String> keys = new ArrayList<>();
    ListObjectsV2Request request =
        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
    await(
        client.listObjectsV2Paginator(request).contents().subscribe(o -> keys.add(o.key())),
        "list " + prefix);
    return keys;
  }

  @Override
  public void delete(String key) throws IOException {
    DeleteObjectRequest request =
        DeleteObjectRequest.builder().bucket(bucket).key(ObjectKeys.requireValid(key)).build();
    await(client.deleteObject(request), "delete " + key);
  }

  private <T> T await(CompletableFuture<T> call, String what) throws IOException {
    try {
      return call.get();
    } catch (InterruptedException e) {
      call.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted: " + what + " in " + describe(), e);
    } catch (ExecutionException | CompletionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new IOException("Failed to " + what + " in " + describe() + ": " + cause, cause);
    }
  }
}
