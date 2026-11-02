package dev.dinesh.argus.framing;

import dev.dinesh.argus.parser.ParserChain;

/**
 * Joins parsed lines into records ({@code P7_PLAN.md} D1): a head line and the continuation lines
 * after it, such as a stack trace's frames. One instance per run, since it holds the open record
 * across batches; not thread-safe.
 *
 * <p>A record's offset is its head line's, so a single-line record keeps the offset (and {@code
 * eventId}) it had before framing existed (D3).
 */
public interface RecordFramer {

  /** Number of lines in a multiline record; absent on single-line records. */
  String LINES_ATTRIBUTE = "argus.lines";

  /** Set when continuation lines past the record's limits were dropped. */
  String TRUNCATED_ATTRIBUTE = "argus.multiline.truncated";

  /** How many continuation lines were dropped. */
  String DROPPED_ATTRIBUTE = "argus.multiline.dropped";

  /** {@value #ORPHAN} on a record whose first line was a continuation with no record to join. */
  String FRAMING_ATTRIBUTE = "argus.framing";

  String ORPHAN = "orphan";

  /**
   * {@value #CONTINUED} on a pushed record whose lines continue the stream's previous request's
   * last record: it follows that record's suppression decision ({@code P7_PLAN.md} D5).
   */
  String CONTINUED = "continued";

  /** OpenTelemetry's semantic convention for an exception's class name. */
  String EXCEPTION_TYPE_ATTRIBUTE = "exception.type";

  /**
   * Offers the next line of the source.
   *
   * @param parsed the line parsed with the source's chain, or null when no parser understood it and
   *     unparsed lines are dropped; a dropped line can still continue a record
   * @return the record this line completed, or null; a line completes at most one
   */
  FramedRecord offer(String line, long offset, ParserChain.Parsed parsed);

  /** The open record's offset (its head line's), or -1 when no record is open. */
  long openOffset();

  /** Lines in the open record's body, or 0 when no record is open. */
  int openLines();

  /**
   * Whether the line last offered became part of the open record (kept or dropped past the limits)
   * rather than starting one or being discarded.
   */
  boolean lastJoined();

  /** Completes the open record: the source has no more lines for it. Null when none is open. */
  FramedRecord flush();

  /**
   * @param maxLines lines in a record, head included; later continuation lines are dropped
   * @param maxBytes UTF-8 bytes of a record's body; later continuation lines are dropped
   */
  record Limits(int maxLines, int maxBytes) {
    public Limits {
      if (maxLines < 1 || maxBytes < 1) {
        throw new IllegalArgumentException("Framing limits must be positive");
      }
    }
  }
}
