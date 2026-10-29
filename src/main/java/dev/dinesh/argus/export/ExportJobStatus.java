package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dinesh.argus.ingest.IngestJob;
import java.time.Instant;

/**
 * JSON view of an {@link ExportJob}.
 *
 * @param pages pages in the spool (this run, including before a resume)
 * @param duplicatePages pages fetched again after an interruption, which the spool already held
 * @param paused why the running job waits ({@code SPOOL_FULL}, {@code WAL_FULL}), or null
 * @param position where the next page starts
 */
public record ExportJobStatus(
    String jobId,
    String tenantId,
    String source,
    String streamId,
    Instant from,
    Instant to,
    String lane,
    IngestJob.State state,
    boolean resumed,
    String paused,
    long pausedMs,
    long pages,
    long lines,
    long bytes,
    long duplicatePages,
    JsonNode position,
    Instant lastPageAt,
    String error,
    Instant createdAt,
    Instant finishedAt) {

  public static ExportJobStatus of(ExportJob job) {
    return new ExportJobStatus(
        job.id(),
        job.tenantId(),
        job.source(),
        job.streamId(),
        job.query().from(),
        job.query().to(),
        "REPLAY",
        job.state(),
        job.resumed(),
        job.paused(),
        job.pausedMs(),
        job.pages(),
        job.lines(),
        job.bytes(),
        job.duplicatePages(),
        job.position(),
        job.lastPageAt(),
        job.error(),
        job.createdAt(),
        job.finishedAt());
  }
}
