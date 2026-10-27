package dev.dinesh.argus.ingest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** One submitted ingest run and its live state. */
public final class IngestJob {

  public enum State {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    boolean isFinished() {
      return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
  }

  private final String id;
  private final String sourceId;
  private final Path path;
  private final boolean fromBeginning;
  private final Instant createdAt = Instant.now();
  private final IngestProgress progress = new IngestProgress();
  private final AtomicBoolean cancel = new AtomicBoolean();
  private volatile State state = State.QUEUED;
  private volatile String error;
  private volatile Instant finishedAt;

  IngestJob(String id, String sourceId, Path path, boolean fromBeginning) {
    this.id = id;
    this.sourceId = sourceId;
    this.path = path;
    this.fromBeginning = fromBeginning;
  }

  void running() {
    state = State.RUNNING;
  }

  void finished(State finalState, String errorMessage) {
    error = errorMessage;
    finishedAt = Instant.now();
    state = finalState;
  }

  public String id() {
    return id;
  }

  public String sourceId() {
    return sourceId;
  }

  public Path path() {
    return path;
  }

  public boolean fromBeginning() {
    return fromBeginning;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant finishedAt() {
    return finishedAt;
  }

  public IngestProgress progress() {
    return progress;
  }

  AtomicBoolean cancelFlag() {
    return cancel;
  }

  public State state() {
    return state;
  }

  public String error() {
    return error;
  }
}
