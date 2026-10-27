package dev.dinesh.argus.source;

import dev.dinesh.argus.model.LogEnvelope;
import java.util.List;

/**
 * Parsed records for the source bytes {@code [startOffset, endOffset)}.
 *
 * @param rejected lines no parser understood that were dropped ({@code keep-unparsed=false})
 * @param unparsed lines no parser understood that were kept as raw records
 * @param fallbackParsed lines parsed by a parser other than the source's primary one
 */
public record LogBatch(
    String sourceId,
    int segmentIndex,
    long sequence,
    List<LogEnvelope> records,
    long startOffset,
    long endOffset,
    boolean lastInSegment,
    int rejected,
    int unparsed,
    int fallbackParsed) {}
