package dev.dinesh.argus.tail;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dinesh.argus.model.LogEnvelope;
import java.time.Instant;
import java.util.Map;

/**
 * One line on the live tail: its envelope without the tenant (the caller's own), as sent to tail
 * clients.
 *
 * @param eventId the id the archive holds; null for pushed lines, whose generation is assigned only
 *     when the stream runner ingests the spool
 * @param offset the line's byte offset in its file; null for pushed lines
 * @param weight estimated bytes held while the event is in a ring
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TailEvent(
    String eventId,
    String source,
    String format,
    Long offset,
    Instant timestamp,
    Instant observedTimestamp,
    String severityText,
    int severityNumber,
    String body,
    String traceId,
    String spanId,
    Map<String, String> resource,
    Map<String, Object> attributes,
    @JsonIgnore int weight) {

  /** Fixed cost of an envelope, its maps and this event, on top of the line's characters. */
  static final int OVERHEAD_BYTES = 256;

  /** A line of a file job, with the {@code eventId} and offset the archive will hold. */
  public static TailEvent ofFile(LogEnvelope envelope, int lineChars) {
    return of(envelope, envelope.eventId(), envelope.source().offset(), lineChars);
  }

  /** A pushed line, at receive time. */
  public static TailEvent ofPush(LogEnvelope envelope, int lineChars) {
    return of(envelope, null, null, lineChars);
  }

  private static TailEvent of(LogEnvelope e, String eventId, Long offset, int lineChars) {
    return new TailEvent(
        eventId,
        e.source().id(),
        e.source().format(),
        offset,
        e.timestamp(),
        e.observedTimestamp(),
        e.severityText(),
        e.severityNumber(),
        e.body(),
        e.traceId(),
        e.spanId(),
        e.resource(),
        e.attributes(),
        lineChars + OVERHEAD_BYTES);
  }
}
