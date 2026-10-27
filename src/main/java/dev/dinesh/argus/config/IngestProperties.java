package dev.dinesh.argus.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Tuning knobs for streaming ingestion. Defaults are sized for a 4GB RAM node: a batch is the
 * in-memory unit passed between stages, a segment is the durability unit (one output file, one
 * checkpoint).
 *
 * @param keepUnparsed store lines no parser understands as raw records (default) instead of
 *     dropping them
 */
@ConfigurationProperties("argus.ingest")
public record IngestProperties(
    int batchMaxLines,
    DataSize batchMaxBytes,
    DataSize segmentBytes,
    int rawQueueCapacity,
    int parsedQueueCapacity,
    DataSize maxLineBytes,
    int maxConcurrentJobs,
    List<Path> allowedRoots,
    Path checkpointDir,
    Boolean keepUnparsed) {

  public IngestProperties {
    batchMaxLines = batchMaxLines > 0 ? batchMaxLines : 10_000;
    batchMaxBytes = batchMaxBytes != null ? batchMaxBytes : DataSize.ofMegabytes(8);
    segmentBytes = segmentBytes != null ? segmentBytes : DataSize.ofMegabytes(500);
    rawQueueCapacity = rawQueueCapacity > 0 ? rawQueueCapacity : 2;
    parsedQueueCapacity = parsedQueueCapacity > 0 ? parsedQueueCapacity : 4;
    maxLineBytes = maxLineBytes != null ? maxLineBytes : DataSize.ofMegabytes(1);
    maxConcurrentJobs = maxConcurrentJobs > 0 ? maxConcurrentJobs : 2;
    allowedRoots = allowedRoots != null ? List.copyOf(allowedRoots) : List.of();
    checkpointDir = checkpointDir != null ? checkpointDir : Path.of("data", "checkpoints");
    keepUnparsed = keepUnparsed == null || keepUnparsed;
  }
}
