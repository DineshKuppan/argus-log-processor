package dev.dinesh.argus.sink;

import dev.dinesh.argus.source.LogBatch;
import java.io.IOException;

/**
 * Destination for parsed records, written one segment at a time. A segment becomes visible only on
 * {@link SegmentWriter#commit}; re-committing the same segment must replace it, never duplicate it.
 */
public interface LogSink {

  /** Removes uncommitted output a crashed run left behind for {@code sourceId}. */
  void discardUncommitted(String sourceId) throws IOException;

  SegmentWriter open(String sourceId, long startOffset) throws IOException;

  /** Writes one segment. Not thread-safe: owned by a single writer thread. */
  interface SegmentWriter {

    void write(LogBatch batch) throws IOException;

    /**
     * Makes the segment durable and visible.
     *
     * @return location of the committed segment, or null if it held no records
     */
    String commit(long endOffset) throws IOException;

    /** Discards the segment; safe to call after a failed write or commit. */
    void abort();
  }
}
