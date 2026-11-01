package dev.dinesh.argus.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * The canonical record every format is normalized to, following the OpenTelemetry log data model.
 * Written one per line by the sinks; the same fields become Parquet columns and OpenSearch mappings
 * in later phases.
 *
 * @param eventId deterministic id derived from tenant, source, generation and byte offset, so a
 *     replayed line gets the same id
 * @param timestamp event time from the line itself; null if the line has none
 * @param observedTimestamp when Argus read the line
 * @param severityText level as written in the line (e.g. {@code warning}, {@code 30})
 * @param severityNumber OpenTelemetry severity number; 0 if unknown
 * @param resource describes the producer, e.g. {@code service.name}
 * @param source where the line came from
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogEnvelope(
    String eventId,
    String tenantId,
    Instant timestamp,
    Instant observedTimestamp,
    String severityText,
    int severityNumber,
    String body,
    String traceId,
    String spanId,
    Map<String, String> resource,
    Map<String, Object> attributes,
    Source source) {

  /**
   * @param offset byte offset of the line's first byte in the source file
   * @param format parser that produced the record
   * @param instance the instance that ingested the line ({@code argus.instance.id}, P6 D8); null
   *     with a single instance, and then not written, so single-instance lines are unchanged
   */
  public record Source(
      String id,
      int generation,
      long offset,
      String format,
      @JsonInclude(JsonInclude.Include.NON_NULL) String instance) {

    public Source(String id, int generation, long offset, String format) {
      this(id, generation, offset, format, null);
    }
  }
}
