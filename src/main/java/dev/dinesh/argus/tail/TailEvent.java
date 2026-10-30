package dev.dinesh.argus.tail;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dinesh.argus.model.LogEnvelope;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * One line on the live tail: its envelope without the tenant (the caller's own), as sent to tail
 * clients.
 *
 * @param eventId the id the archive holds; null for pushed lines, whose generation is assigned only
 *     when the stream runner ingests the spool
 * @param offset the line's byte offset in its file; null for pushed lines
 * @param weight estimated heap bytes held while the event is in a ring
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

  /** Fixed cost of an envelope, its maps and this event. */
  static final int OVERHEAD_BYTES = 256;

  /** Cost of a map entry (node, key and value objects) on top of its characters. */
  static final int ENTRY_BYTES = 96;

  /** Cost of a value that isn't text, a map or a list (a boxed number or boolean). */
  static final int VALUE_BYTES = 32;

  /** A line of a file job, with the {@code eventId} and offset the archive will hold. */
  public static TailEvent ofFile(LogEnvelope envelope) {
    return of(envelope, envelope.eventId(), envelope.source().offset());
  }

  /** A pushed line, at receive time. */
  public static TailEvent ofPush(LogEnvelope envelope) {
    return of(envelope, null, null);
  }

  private static TailEvent of(LogEnvelope e, String eventId, Long offset) {
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
        weight(e));
  }

  /**
   * Estimated heap the event keeps alive: its body and every attribute, not just the line's length
   * (a JSON line's parsed fields can hold several times the line). Strings count two bytes a char.
   */
  static int weight(LogEnvelope e) {
    long bytes = OVERHEAD_BYTES + chars(e.body()) + chars(e.traceId()) + chars(e.spanId());
    bytes += mapWeight(e.resource()) + mapWeight(e.attributes());
    return (int) Math.min(Integer.MAX_VALUE, bytes);
  }

  private static long mapWeight(Map<?, ?> map) {
    if (map == null) {
      return 0;
    }
    long bytes = 0;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      bytes += ENTRY_BYTES + valueWeight(entry.getKey()) + valueWeight(entry.getValue());
    }
    return bytes;
  }

  /** Nested JSON objects and arrays count in full: their size comes from the client's line. */
  private static long valueWeight(Object value) {
    if (value instanceof CharSequence text) {
      return chars(text);
    }
    if (value instanceof Map<?, ?> map) {
      return VALUE_BYTES + mapWeight(map);
    }
    if (value instanceof Collection<?> items) {
      long bytes = VALUE_BYTES;
      for (Object item : items) {
        bytes += ENTRY_BYTES / 2 + valueWeight(item);
      }
      return bytes;
    }
    return value == null ? 0 : VALUE_BYTES;
  }

  private static long chars(CharSequence text) {
    return text == null ? 0 : 2L * text.length();
  }
}
