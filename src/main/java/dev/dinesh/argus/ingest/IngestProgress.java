package dev.dinesh.argus.ingest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Live counters for one ingest run, updated by pipeline threads and read by the job API. */
public final class IngestProgress {

  private final AtomicLong startOffset = new AtomicLong();
  private final AtomicLong totalBytes = new AtomicLong();
  private final AtomicLong bytesRead = new AtomicLong();
  private final AtomicLong lines = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong unparsed = new AtomicLong();
  private final AtomicLong fallbackParsed = new AtomicLong();
  private final AtomicLong truncated = new AtomicLong();
  private final AtomicLong segmentsCommitted = new AtomicLong();
  private final AtomicLong committedOffset = new AtomicLong();
  private final List<String> outputs = new CopyOnWriteArrayList<>();
  private volatile String resumeReason;
  private volatile String format;
  private volatile String formatSource;
  // Guarded by this: the writer resumes while the job API reads, and a wait must count once.
  private long pausedNanos;
  private long pausedSince;
  private boolean paused;

  void started(long offset, long total, String reason) {
    startOffset.set(offset);
    bytesRead.set(offset);
    committedOffset.set(offset);
    totalBytes.set(total);
    resumeReason = reason;
  }

  void read(long endOffset, int truncatedLines) {
    bytesRead.set(endOffset);
    truncated.addAndGet(truncatedLines);
  }

  void format(String name, String source) {
    format = name;
    formatSource = source;
  }

  void parsed(int records, int rejectedLines, int unparsedLines, int fallbackLines) {
    lines.addAndGet(records);
    rejected.addAndGet(rejectedLines);
    unparsed.addAndGet(unparsedLines);
    fallbackParsed.addAndGet(fallbackLines);
  }

  /** The writer waits for WAL room ({@code true}) or resumes ({@code false}). */
  synchronized void paused(boolean nowPaused) {
    if (nowPaused) {
      pausedSince = System.nanoTime();
      paused = true;
    } else if (paused) {
      pausedNanos += System.nanoTime() - pausedSince;
      paused = false;
    }
  }

  /** Why the run is waiting right now, or null if it isn't. */
  public synchronized String pauseReason() {
    return paused ? "WAL_FULL" : null;
  }

  /** Time spent waiting for WAL room, including a wait in progress. */
  public synchronized long pausedMs() {
    long nanos = pausedNanos;
    if (paused) {
      nanos += System.nanoTime() - pausedSince;
    }
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  void committed(long endOffset, String location) {
    segmentsCommitted.incrementAndGet();
    committedOffset.set(endOffset);
    if (location != null) {
      outputs.add(location);
    }
  }

  public long startOffset() {
    return startOffset.get();
  }

  public long totalBytes() {
    return totalBytes.get();
  }

  public long bytesRead() {
    return bytesRead.get();
  }

  public long lines() {
    return lines.get();
  }

  public long rejected() {
    return rejected.get();
  }

  public long unparsed() {
    return unparsed.get();
  }

  public long fallbackParsed() {
    return fallbackParsed.get();
  }

  /** Primary format of the source, or null before the run has started. */
  public String format() {
    return format;
  }

  /** {@code requested} or {@code detected}. */
  public String formatSource() {
    return formatSource;
  }

  public long truncated() {
    return truncated.get();
  }

  public long segmentsCommitted() {
    return segmentsCommitted.get();
  }

  public long committedOffset() {
    return committedOffset.get();
  }

  public List<String> outputs() {
    return List.copyOf(outputs);
  }

  public String resumeReason() {
    return resumeReason;
  }
}
