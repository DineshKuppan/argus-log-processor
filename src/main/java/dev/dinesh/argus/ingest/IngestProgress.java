package dev.dinesh.argus.ingest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Live counters for one ingest run, updated by pipeline threads and read by the job API. */
public final class IngestProgress {

  private final AtomicLong startOffset = new AtomicLong();
  private final AtomicLong totalBytes = new AtomicLong();
  private final AtomicLong bytesRead = new AtomicLong();
  private final AtomicLong lines = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong truncated = new AtomicLong();
  private final AtomicLong segmentsCommitted = new AtomicLong();
  private final AtomicLong committedOffset = new AtomicLong();
  private final List<String> outputs = new CopyOnWriteArrayList<>();
  private volatile String resumeReason;

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

  void parsed(int parsedLines, int rejectedLines) {
    lines.addAndGet(parsedLines);
    rejected.addAndGet(rejectedLines);
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
