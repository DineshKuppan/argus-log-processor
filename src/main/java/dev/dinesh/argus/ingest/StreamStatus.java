package dev.dinesh.argus.ingest;

import dev.dinesh.argus.parser.LogParser;
import dev.dinesh.argus.source.StreamSpool;
import java.time.Instant;

/**
 * JSON view of a {@link PushStream} returned by the stream API.
 *
 * @param format the stream's requested format, or null when each spool file's is detected
 * @param pendingBytes acknowledged bytes not yet ingested
 * @param lastRun the latest run of the spool, or null if none has run since startup
 */
public record StreamStatus(
    String streamId,
    String tenantId,
    String format,
    int activeSpool,
    int spoolFiles,
    long spoolBytes,
    long pendingBytes,
    int idempotencyKeys,
    long requests,
    long acceptedLines,
    long suppressedLines,
    long truncatedLines,
    long duplicates,
    Instant lastRequestAt,
    long runs,
    RunStatus lastRun) {

  /**
   * @param outcome {@code COMPLETED}, {@code CANCELLED} or {@code FAILED}
   * @param paused why the run is waiting right now ({@code WAL_FULL}), or null
   */
  public record RunStatus(
      String outcome,
      String error,
      int spool,
      Instant startedAt,
      Instant finishedAt,
      long startOffset,
      long committedOffset,
      long lines,
      long segmentsCommitted,
      String paused,
      long pausedMs) {}

  public static StreamStatus of(PushStream stream) {
    StreamSpool.Status spool = stream.spool().status();
    LogParser parser = stream.parser();
    PushStream.Run run = stream.lastRun();
    RunStatus lastRun = null;
    if (run != null) {
      IngestProgress p = run.progress();
      lastRun =
          new RunStatus(
              run.outcome(),
              run.error(),
              run.spool(),
              run.startedAt(),
              run.finishedAt(),
              p.startOffset(),
              p.committedOffset(),
              p.lines(),
              p.segmentsCommitted(),
              p.pauseReason(),
              p.pausedMs());
    }
    return new StreamStatus(
        stream.streamId(),
        stream.tenantId(),
        parser == null ? null : parser.name(),
        spool.activeSpool(),
        spool.files(),
        spool.bytesOnDisk(),
        spool.pendingBytes(),
        spool.idempotencyKeys(),
        stream.requests.get(),
        stream.acceptedLines.get(),
        stream.suppressedLines.get(),
        stream.truncatedLines.get(),
        stream.duplicates.get(),
        stream.lastRequestAt,
        stream.runs.get(),
        lastRun);
  }
}
