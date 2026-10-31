package dev.dinesh.argus.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.dinesh.argus.model.LogEnvelope;
import java.time.Instant;
import java.util.Map;

/**
 * One line found by a search: its envelope without the tenant (the caller's own).
 *
 * @param timestamp the line's event time; null if it had none (then it was found by its observed
 *     time)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchHit(
    String eventId,
    Instant timestamp,
    Instant observedTimestamp,
    String severityText,
    int severityNumber,
    String body,
    String traceId,
    String spanId,
    Map<String, Object> resource,
    Map<String, Object> attributes,
    LogEnvelope.Source source) {}
