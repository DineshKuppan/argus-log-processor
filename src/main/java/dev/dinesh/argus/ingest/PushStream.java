package dev.dinesh.argus.ingest;

import dev.dinesh.argus.framing.FramingSpec;
import dev.dinesh.argus.parser.LogParser;
import dev.dinesh.argus.source.StreamSpool;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One push stream: its spool, its format, its counters and its latest run. */
public final class PushStream {

  /**
   * A stream run, finished or running.
   *
   * @param outcome {@code RUNNING}, {@code COMPLETED}, {@code CANCELLED} or {@code FAILED}
   * @param finishedAt null while running
   */
  public record Run(
      String outcome,
      String error,
      int spool,
      Instant startedAt,
      Instant finishedAt,
      IngestProgress progress) {}

  private final String tenantId;
  private final String streamId;
  private final StreamSpool spool;
  private final Instant openedAt = Instant.now();

  final AtomicLong requests = new AtomicLong();
  final AtomicLong acceptedLines = new AtomicLong();
  final AtomicLong suppressedLines = new AtomicLong();
  final AtomicLong truncatedLines = new AtomicLong();
  final AtomicLong duplicates = new AtomicLong();
  final AtomicLong runs = new AtomicLong();

  /** Set while a run is queued or running, so a stream has at most one. */
  final AtomicBoolean scheduled = new AtomicBoolean();

  volatile long retryNotBeforeNanos;
  volatile Instant lastRequestAt;
  volatile Run lastRun;

  /** The format the latest request named, or null to detect it. */
  private volatile LogParser parser;

  /** What push requests parse lines with to find their message: {@link #parser} or detected. */
  private volatile LogParser messageParser;

  /** How the stream's lines join into records, or null for {@code argus.framing.mode}. */
  private volatile FramingSpec framing;

  /**
   * Whether the last record of the latest appended request was kept (not suppressed), or null when
   * unknown (no request yet, or a restart): continuation lines that start the next request follow
   * it ({@code P7_PLAN.md} D5).
   */
  volatile Boolean lastRecordAllowed;

  PushStream(String tenantId, String streamId, StreamSpool spool) {
    this.tenantId = tenantId;
    this.streamId = streamId;
    this.spool = spool;
  }

  public String tenantId() {
    return tenantId;
  }

  public String streamId() {
    return streamId;
  }

  public StreamSpool spool() {
    return spool;
  }

  public Instant openedAt() {
    return openedAt;
  }

  /** The requested format for runs, or null: the pipeline detects it from the spool's start. */
  public LogParser parser() {
    return parser;
  }

  public void useParser(LogParser requested) {
    parser = requested;
    messageParser = requested;
  }

  LogParser messageParser() {
    return messageParser;
  }

  void detectedMessageParser(LogParser detected) {
    if (messageParser == null) {
      messageParser = detected;
    }
  }

  public FramingSpec framing() {
    return framing;
  }

  public void useFraming(FramingSpec requested) {
    framing = requested;
  }

  public Run lastRun() {
    return lastRun;
  }
}
