package dev.dinesh.argus.pipeline;

import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.model.Severity;
import dev.dinesh.argus.parser.Fields;
import dev.dinesh.argus.parser.ParserChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Normalizes parsed lines of one source into {@link LogEnvelope}s: assigns the deterministic {@code
 * eventId}, maps the severity, and lifts trace context and the service name out of the attributes.
 * Thread-safe.
 */
public final class EnvelopeFactory {

  private static final List<String> TRACE_ID_KEYS = List.of("trace_id", "traceId", "trace.id");
  private static final List<String> SPAN_ID_KEYS = List.of("span_id", "spanId", "span.id");
  private static final List<String> SERVICE_KEYS =
      List.of("service.name", "service", "app", "application");

  private final String tenantId;
  private final String sourceId;
  private final int generation;

  public EnvelopeFactory(String tenantId, String sourceId, int generation) {
    this.tenantId = tenantId;
    this.sourceId = sourceId;
    this.generation = generation;
  }

  /**
   * Builds the envelope for one line. Takes ownership of the record's attribute map, from which
   * trace and service fields are removed.
   */
  public LogEnvelope create(ParserChain.Parsed parsed, long lineOffset, Instant observedAt) {
    LogRecord record = parsed.record();
    Map<String, Object> attributes = record.getMeta();
    String service = serviceName(attributes);
    return new LogEnvelope(
        eventId(lineOffset),
        tenantId,
        record.getTimestamp(),
        observedAt,
        record.getLevel(),
        Severity.of(record.getLevel()).number(),
        record.getMessage(),
        Fields.takeText(attributes, TRACE_ID_KEYS),
        Fields.takeText(attributes, SPAN_ID_KEYS),
        service == null ? null : Map.of("service.name", service),
        attributes,
        new LogEnvelope.Source(sourceId, generation, lineOffset, parsed.format()));
  }

  /** Same tenant, source, generation and offset always give the same id (guardrail 3). */
  String eventId(long lineOffset) {
    return eventId(tenantId, sourceId, generation, lineOffset);
  }

  /**
   * The one definition of {@code eventId}. The Parquet archive stores only these four inputs (its
   * {@code tenant_id}, {@code source_id}, {@code source_generation} and {@code source_offset}
   * columns), so a reader re-derives the id with this.
   */
  public static String eventId(String tenantId, String sourceId, int generation, long lineOffset) {
    String key = tenantId + '\n' + sourceId + '\n' + generation + '\n' + lineOffset;
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * A flat {@code service.name}/{@code service}/{@code app} value is moved into the resource. An
   * ECS {@code service} object ({@code {"name": ..., "version": ...}}) only lends its name and
   * stays in the attributes, so its other fields are kept.
   */
  private static String serviceName(Map<String, Object> attributes) {
    String service = Fields.takeText(attributes, SERVICE_KEYS);
    if (service == null && attributes.get("service") instanceof Map<?, ?> ecs) {
      Object name = ecs.get("name");
      service = name instanceof String s ? s : null;
    }
    return service;
  }
}
