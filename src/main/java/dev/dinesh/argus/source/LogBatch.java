package dev.dinesh.argus.source;

import dev.dinesh.argus.model.LogRecord;
import java.util.List;

/**
 * Parsed records for the source bytes {@code [startOffset, endOffset)}.
 *
 * @param rejected lines that could not be parsed
 */
public record LogBatch(
    String sourceId,
    int segmentIndex,
    long sequence,
    List<LogRecord> records,
    long startOffset,
    long endOffset,
    boolean lastInSegment,
    int rejected) {}
