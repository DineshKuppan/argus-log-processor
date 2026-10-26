package dev.dinesh.argus.source;

import java.util.List;

/**
 * Unparsed lines read from a source, covering source bytes {@code [startOffset, endOffset)}.
 *
 * @param lastInSegment true when this batch closes its segment (or ends the source)
 * @param truncatedLines lines cut to the configured maximum line length
 */
public record RawBatch(
    String sourceId,
    int segmentIndex,
    long sequence,
    List<String> lines,
    long startOffset,
    long endOffset,
    boolean lastInSegment,
    int truncatedLines) {}
