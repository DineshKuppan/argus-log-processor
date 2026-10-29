package dev.dinesh.argus.export;

import java.time.Instant;

/** Builds export jobs for tests outside this package. */
public final class ExportJobs {

  private ExportJobs() {}

  public static ExportJob queued(String tenantId, String source, String streamId) {
    return new ExportJob(
        "job-1",
        tenantId,
        source,
        streamId,
        new ExportSource.Query(
            null, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z")),
        "identity",
        "run",
        false);
  }
}
