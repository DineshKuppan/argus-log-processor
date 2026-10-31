package dev.dinesh.argus.hot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.dinesh.argus.model.LogEnvelope;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;

/**
 * A {@link LogEnvelope} as a hot-tier document ({@code P5_PLAN.md} D5): fixed snake_case fields,
 * like the Parquet columns, with {@code attributes} and {@code resource} as one flattened field
 * each. Field names here and in {@link HotTemplates} must match.
 */
public final class HotDocument {

  /**
   * Longest string kept in a flattened leaf. Each leaf is indexed as a keyword, and Lucene rejects
   * terms over 32,766 bytes (a whole document with them); the cold tier keeps the full value.
   */
  static final int MAX_LEAF_CHARS = 8_000;

  static final String TIMESTAMP = "@timestamp";
  static final String OBSERVED = "observed_timestamp";
  static final String EVENT_TIME_MISSING = "event_time_missing";
  static final String TENANT = "tenant_id";
  static final String EVENT_ID = "event_id";
  static final String SEVERITY_TEXT = "severity_text";
  static final String SEVERITY_NUMBER = "severity_number";
  static final String BODY = "body";
  static final String TRACE_ID = "trace_id";
  static final String SPAN_ID = "span_id";
  static final String SERVICE = "service_name";
  static final String RESOURCE = "resource";
  static final String ATTRIBUTES = "attributes";
  static final String SOURCE_ID = "source_id";
  static final String SOURCE_GENERATION = "source_generation";
  static final String SOURCE_OFFSET = "source_offset";
  static final String SOURCE_FORMAT = "source_format";

  private static final DateTimeFormatter MILLIS = DateTimeFormatter.ISO_INSTANT;

  private HotDocument() {}

  /** The document for {@code envelope}, indexed at {@code now} (for the day rules of D4). */
  public static ObjectNode of(LogEnvelope e, Instant now, ObjectMapper mapper) {
    ObjectNode doc = mapper.createObjectNode();
    Instant eventTime = HotIndices.eventTime(e, now);
    Instant observed = e.observedTimestamp() != null ? e.observedTimestamp() : now;
    doc.put(TIMESTAMP, iso(eventTime != null ? eventTime : observed));
    doc.put(OBSERVED, iso(observed));
    if (eventTime == null) {
      doc.put(EVENT_TIME_MISSING, true);
    }
    doc.put(TENANT, e.tenantId());
    doc.put(EVENT_ID, e.eventId());
    putIfPresent(doc, SEVERITY_TEXT, e.severityText());
    doc.put(SEVERITY_NUMBER, e.severityNumber());
    putIfPresent(doc, BODY, e.body());
    putIfPresent(doc, TRACE_ID, e.traceId());
    putIfPresent(doc, SPAN_ID, e.spanId());
    if (e.resource() != null && !e.resource().isEmpty()) {
      putIfPresent(doc, SERVICE, e.resource().get("service.name"));
      doc.set(RESOURCE, flat(mapper.valueToTree(e.resource())));
    }
    if (e.attributes() != null && !e.attributes().isEmpty()) {
      doc.set(ATTRIBUTES, flat(mapper.valueToTree(e.attributes())));
    }
    if (e.source() != null) {
      putIfPresent(doc, SOURCE_ID, e.source().id());
      doc.put(SOURCE_GENERATION, e.source().generation());
      doc.put(SOURCE_OFFSET, e.source().offset());
      putIfPresent(doc, SOURCE_FORMAT, e.source().format());
    }
    return doc;
  }

  /** Millisecond precision: the {@code date} type keeps no more. */
  static String iso(Instant t) {
    return MILLIS.format(t.truncatedTo(ChronoUnit.MILLIS));
  }

  private static void putIfPresent(ObjectNode doc, String field, String value) {
    if (value != null) {
      doc.put(field, value);
    }
  }

  /** Long string leaves cut to {@link #MAX_LEAF_CHARS}, at any depth. */
  private static JsonNode flat(JsonNode node) {
    if (node.isTextual() && node.textValue().length() > MAX_LEAF_CHARS) {
      return TextNode.valueOf(node.textValue().substring(0, MAX_LEAF_CHARS));
    }
    if (node instanceof ObjectNode object) {
      for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> field = it.next();
        field.setValue(flat(field.getValue()));
      }
    } else if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, flat(array.get(i)));
      }
    }
    return node;
  }
}
