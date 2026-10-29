package dev.dinesh.argus.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * HTTP push ingest ({@code P3_PLAN.md} D1–D4): every limit on the push path, and how the spool
 * batches pushes into runs.
 *
 * @param spoolDir where each stream's spool lives ({@code spool-dir/tenant=T/stream=S/})
 * @param maxRequestBytes largest request body, after gzip decompression (guards against gzip bombs)
 * @param maxRequestLines most lines in one request (guardrail 1's micro-batch)
 * @param maxConcurrentRequests requests being read and spooled at once; with {@code
 *     maxRequestBytes} this bounds the heap push requests use
 * @param flushInterval the longest an acknowledged line waits in the spool before a run starts
 * @param maxSpoolBytes acknowledged bytes not yet ingested, across every stream; above it pushes
 *     get 429
 * @param spoolRotateBytes a spool file is sealed at this size, and deleted once ingested
 * @param maxStreamsPerTenant a request for a new stream beyond this gets 409
 * @param maxConcurrentRuns stream runs at once; other due streams wait, their spool absorbing it
 * @param idempotencyWindow {@code Idempotency-Key}s remembered per stream
 * @param suppress apply the 10s suppression window to pushed lines (at receive time)
 */
@ConfigurationProperties("argus.push")
public record PushProperties(
    Path spoolDir,
    DataSize maxRequestBytes,
    int maxRequestLines,
    int maxConcurrentRequests,
    Duration flushInterval,
    DataSize maxSpoolBytes,
    DataSize spoolRotateBytes,
    int maxStreamsPerTenant,
    int maxConcurrentRuns,
    int idempotencyWindow,
    Boolean suppress) {

  /** Bodies are held in one array. */
  private static final DataSize MAX_REQUEST_BYTES_LIMIT = DataSize.ofMegabytes(512);

  public PushProperties {
    // Absolute and normalized: spool paths are part of each stream checkpoint's identity, so
    // another spelling of the same directory must not look like new spool files.
    spoolDir =
        (spoolDir != null ? spoolDir : Path.of("data", "spool")).toAbsolutePath().normalize();
    maxRequestBytes = maxRequestBytes != null ? maxRequestBytes : DataSize.ofMegabytes(8);
    maxRequestLines = maxRequestLines > 0 ? maxRequestLines : 10_000;
    maxConcurrentRequests = maxConcurrentRequests > 0 ? maxConcurrentRequests : 16;
    flushInterval = flushInterval != null ? flushInterval : Duration.ofMinutes(1);
    maxSpoolBytes = maxSpoolBytes != null ? maxSpoolBytes : DataSize.ofGigabytes(2);
    spoolRotateBytes = spoolRotateBytes != null ? spoolRotateBytes : DataSize.ofMegabytes(64);
    maxStreamsPerTenant = maxStreamsPerTenant > 0 ? maxStreamsPerTenant : 32;
    maxConcurrentRuns = maxConcurrentRuns > 0 ? maxConcurrentRuns : 2;
    idempotencyWindow = idempotencyWindow > 0 ? idempotencyWindow : 1024;
    suppress = suppress == null || suppress;
    if (maxRequestBytes.toBytes() <= 0 || maxRequestBytes.compareTo(MAX_REQUEST_BYTES_LIMIT) > 0) {
      throw new IllegalArgumentException(
          "argus.push.max-request-bytes must be between 1B and 512MB: " + maxRequestBytes);
    }
    if (flushInterval.isNegative() || flushInterval.isZero()) {
      throw new IllegalArgumentException("argus.push.flush-interval must be positive");
    }
    if (spoolRotateBytes.toBytes() <= 0) {
      throw new IllegalArgumentException("argus.push.spool-rotate-bytes must be positive");
    }
  }
}
