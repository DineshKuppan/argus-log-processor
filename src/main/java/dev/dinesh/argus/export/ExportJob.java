package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dinesh.argus.ingest.IngestJob.State;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One export run and its live state. */
public final class ExportJob {

  private final String id;
  private final String tenantId;
  private final String source;
  private final String streamId;
  private final ExportSource.Query query;
  private final String identity;
  private final String runId;
  private final boolean resumed;
  private final Instant createdAt = Instant.now();
  private final AtomicBoolean cancel = new AtomicBoolean();

  /** Totals of this export run, including pages exported before a resume. */
  final AtomicLong pages = new AtomicLong();

  final AtomicLong lines = new AtomicLong();
  final AtomicLong bytes = new AtomicLong();

  /** Pages whose key the spool already held: re-fetched after a crash, written once. */
  final AtomicLong duplicatePages = new AtomicLong();

  final AtomicLong pausedMs = new AtomicLong();
  volatile String paused;
  volatile JsonNode position;
  volatile Instant lastPageAt;
  private volatile State state = State.QUEUED;
  private volatile String error;
  private volatile Instant finishedAt;

  ExportJob(
      String id,
      String tenantId,
      String source,
      String streamId,
      ExportSource.Query query,
      String identity,
      String runId,
      boolean resumed) {
    this.id = id;
    this.tenantId = tenantId;
    this.source = source;
    this.streamId = streamId;
    this.query = query;
    this.identity = identity;
    this.runId = runId;
    this.resumed = resumed;
  }

  void running() {
    state = State.RUNNING;
  }

  void finished(State finalState, String errorMessage) {
    error = errorMessage;
    finishedAt = Instant.now();
    paused = null;
    state = finalState;
  }

  public String id() {
    return id;
  }

  public String tenantId() {
    return tenantId;
  }

  public String source() {
    return source;
  }

  public String streamId() {
    return streamId;
  }

  public ExportSource.Query query() {
    return query;
  }

  String identity() {
    return identity;
  }

  String runId() {
    return runId;
  }

  /** The job continues an export interrupted earlier, from its saved cursor. */
  public boolean resumed() {
    return resumed;
  }

  public long pages() {
    return pages.get();
  }

  public long lines() {
    return lines.get();
  }

  public long bytes() {
    return bytes.get();
  }

  public long duplicatePages() {
    return duplicatePages.get();
  }

  public long pausedMs() {
    return pausedMs.get();
  }

  /** Why the running job is waiting ({@code SPOOL_FULL}, {@code WAL_FULL}), or null. */
  public String paused() {
    return paused;
  }

  /** Where the next page starts, or null before the first page. */
  public JsonNode position() {
    return position;
  }

  public Instant lastPageAt() {
    return lastPageAt;
  }

  public State state() {
    return state;
  }

  public String error() {
    return error;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant finishedAt() {
    return finishedAt;
  }

  AtomicBoolean cancelFlag() {
    return cancel;
  }
}
