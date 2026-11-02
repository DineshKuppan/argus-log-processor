package dev.dinesh.argus.framing;

import dev.dinesh.argus.parser.ParserChain;

/**
 * One record out of a {@link RecordFramer}.
 *
 * @param parsed the head line's parse result; for a multiline record its message is the whole body
 * @param offset the head line's byte offset in the source
 * @param lines lines kept in the body, head included
 * @param truncated cut at the limits: the next record ({@code argus.framing=split}) continues it
 */
public record FramedRecord(ParserChain.Parsed parsed, long offset, int lines, boolean truncated) {}
