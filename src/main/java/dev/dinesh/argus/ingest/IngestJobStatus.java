package dev.dinesh.argus.ingest;

import java.time.Instant;
import java.util.List;

/**
 * JSON view of an {@link IngestJob} returned by the ingest API.
 *
 * @param paused why a running job is waiting ({@code WAL_FULL}), or null
 * @param pausedMs total time the job has waited for WAL room
 * @param framing the requested framing, or null for {@code argus.framing.mode}
 * @param lines records written (a multiline record counts once)
 * @param heldBackBytes a multiline record open at the end of the file, left for the next run
 */
public record IngestJobStatus(
    String jobId,
    String tenantId,
    String sourceId,
    String path,
    Lane lane,
    IngestJob.State state,
    String paused,
    long pausedMs,
    String resumeReason,
    long startOffset,
    long totalBytes,
    long bytesRead,
    double percent,
    String format,
    String formatSource,
    String framing,
    long lines,
    long multilineRecords,
    long fallbackParsed,
    long unparsed,
    long rejected,
    long truncated,
    long heldBackBytes,
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
        job.tenantId(),
        job.sourceId(),
        job.path().toString(),
        job.lane(),
        job.state(),
        p.pauseReason(),
        p.pausedMs(),
        p.resumeReason(),
        p.startOffset(),
        p.totalBytes(),
        p.bytesRead(),
        Math.round(percent * 10) / 10.0,
        p.format(),
        p.formatSource(),
        job.framing() == null ? null : job.framing().toString(),
        p.lines(),
        p.multilineRecords(),
        p.fallbackParsed(),
        p.unparsed(),
        p.rejected(),
        p.truncated(),
        p.heldBackBytes(),
        p.segmentsCommitted(),
        p.committedOffset(),
        p.outputs(),
        job.error(),
        job.createdAt(),
        job.finishedAt());
  }
}
