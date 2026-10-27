package dev.dinesh.argus.ingest;

import java.time.Instant;
import java.util.List;

/** JSON view of an {@link IngestJob} returned by the ingest API. */
public record IngestJobStatus(
    String jobId,
    String sourceId,
    String path,
    IngestJob.State state,
    String resumeReason,
    long startOffset,
    long totalBytes,
    long bytesRead,
    double percent,
    long lines,
    long rejected,
    long truncated,
    long segmentsCommitted,
    long committedOffset,
    List<String> outputs,
    String error,
    Instant createdAt,
    Instant finishedAt) {

  public static IngestJobStatus of(IngestJob job) {
    IngestProgress p = job.progress();
    long toRead = p.totalBytes() - p.startOffset();
    double percent;
    if (job.state() == IngestJob.State.COMPLETED) {
      percent = 100.0;
    } else if (toRead <= 0) {
      // Not started yet (sizes unknown), or nothing new to read.
      percent = 0.0;
    } else {
      percent = Math.min(100.0, 100.0 * (p.bytesRead() - p.startOffset()) / toRead);
    }
    return new IngestJobStatus(
        job.id(),
        job.sourceId(),
        job.path().toString(),
        job.state(),
        p.resumeReason(),
        p.startOffset(),
        p.totalBytes(),
        p.bytesRead(),
        Math.round(percent * 10) / 10.0,
        p.lines(),
        p.rejected(),
        p.truncated(),
        p.segmentsCommitted(),
        p.committedOffset(),
        p.outputs(),
        job.error(),
        job.createdAt(),
        job.finishedAt());
  }
}
