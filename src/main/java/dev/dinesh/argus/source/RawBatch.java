package dev.dinesh.argus.source;

import java.util.List;

/**
 * Unparsed lines read from a source, covering source bytes {@code [startOffset, endOffset)}.
 *
 * @param lineOffsets byte offset of the first byte of each line in {@code lines} (same order); an
 *     array rather than a {@code List<Long>} to avoid boxing 10,000 values per batch
 * @param lastInSegment true when this batch closes its segment (or ends the source)
 * @param truncatedLines lines cut to the configured maximum line length
 */
public record RawBatch(
    String sourceId,
    int segmentIndex,
    long sequence,
    List<String> lines,
    long[] lineOffsets,
    long startOffset,
    long endOffset,
    boolean lastInSegment,
    int truncatedLines) {}
