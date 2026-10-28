package dev.dinesh.argus.config;

import dev.dinesh.argus.archive.ObjectKeys;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The Parquet archive (P2): where committed WAL segments are uploaded, and how.
 *
 * @param prefix key prefix inside the store (e.g. {@code argus/prod}); empty for none
 * @param deleteAfterUpload remove a segment from the WAL once it is archived; false keeps it, with
 *     a {@code .archived} marker so it isn't uploaded again
 * @param scanInterval how often the WAL is scanned for segments to archive
 * @param stagingDir where a segment's Parquet file is written before upload (one at a time)
 * @param rowGroupSize Parquet row group; an open writer buffers one on the heap
 * @param retryInitial first wait after a failed upload; doubles per failure up to {@code retryMax}
 */
@ConfigurationProperties("argus.archive")
public record ArchiveProperties(
    Store store,
    String prefix,
    Boolean deleteAfterUpload,
    Duration scanInterval,
    Path stagingDir,
    DataSize rowGroupSize,
    Duration retryInitial,
    Duration retryMax,
    FileSystem filesystem,
    S3 s3,
    Gcs gcs) {

  public enum Store {
    /** No archive: the local NDJSON segments are the output (the behaviour before P2). */
    NONE,
    /** A directory, e.g. a Kubernetes PersistentVolume. */
    FILESYSTEM,
    /** AWS S3 or an S3-compatible store. */
    S3,
    /** Google Cloud Storage, through its JSON API. */
    GCS
  }

  /**
   * @param root directory objects are written under
   */
  public record FileSystem(Path root) {}

  /**
   * @param endpoint an S3-compatible endpoint; null for AWS
   * @param region null for the SDK's region chain
   * @param pathStyle {@code http://host/bucket/key} addressing, which most S3-compatible stores
   *     need
   * @param checksums {@code WHEN_REQUIRED} for stores that reject the checksums AWS SDK 2.30+ sends
   */
  public record S3(
      String bucket, String region, URI endpoint, boolean pathStyle, Checksums checksums) {

    public enum Checksums {
      WHEN_SUPPORTED,
      WHEN_REQUIRED
    }
  }

  /**
   * @param endpoint an emulator such as fake-gcs-server; null for {@code storage.googleapis.com}
   * @param anonymous send no credentials (emulators only); otherwise Application Default
   *     Credentials (Workload Identity, a service account, {@code gcloud})
   */
  public record Gcs(String bucket, URI endpoint, boolean anonymous) {}

  public ArchiveProperties {
    store = store != null ? store : Store.NONE;
    prefix = normalizePrefix(prefix);
    deleteAfterUpload = deleteAfterUpload == null || deleteAfterUpload;
    scanInterval = positive(scanInterval, Duration.ofSeconds(10), "scan-interval");
    stagingDir = stagingDir != null ? stagingDir : Path.of("data", "archive-staging");
    rowGroupSize = rowGroupSize != null ? rowGroupSize : DataSize.ofMegabytes(64);
    if (rowGroupSize.toBytes() <= 0) {
      throw new IllegalArgumentException(
          "argus.archive.row-group-size must be positive: " + rowGroupSize);
    }
    retryInitial = positive(retryInitial, Duration.ofSeconds(1), "retry-initial");
    retryMax = positive(retryMax, Duration.ofMinutes(5), "retry-max");
    if (retryMax.compareTo(retryInitial) < 0) {
      throw new IllegalArgumentException(
          "argus.archive.retry-max must not be shorter than retry-initial");
    }
    filesystem =
        filesystem != null && filesystem.root() != null
            ? filesystem
            : new FileSystem(Path.of("data", "archive"));
    s3 = s3 != null ? s3 : new S3(null, null, null, false, null);
    if (s3.checksums() == null) {
      s3 =
          new S3(
              s3.bucket(), s3.region(), s3.endpoint(), s3.pathStyle(), S3.Checksums.WHEN_SUPPORTED);
    }
    if (store == Store.S3 && (s3.bucket() == null || s3.bucket().isBlank())) {
      throw new IllegalArgumentException("argus.archive.store=s3 needs argus.archive.s3.bucket");
    }
    gcs = gcs != null ? gcs : new Gcs(null, null, false);
    if (store == Store.GCS && (gcs.bucket() == null || gcs.bucket().isBlank())) {
      throw new IllegalArgumentException("argus.archive.store=gcs needs argus.archive.gcs.bucket");
    }
  }

  /** {@code ""} or a prefix ending in {@code /}, so keys are {@code prefix + key}. */
  private static String normalizePrefix(String prefix) {
    if (prefix == null) {
      return "";
    }
    String trimmed = prefix.strip().replaceAll("^/+|/+$", "");
    // Checked now, not on the first upload: a bad prefix would make every upload fail.
    return trimmed.isEmpty() ? "" : ObjectKeys.requireValid(trimmed) + "/";
  }

  private static Duration positive(Duration value, Duration fallback, String name) {
    if (value == null) {
      return fallback;
    }
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException("argus.archive." + name + " must be positive: " + value);
    }
    return value;
  }
}
