package dev.dinesh.argus.search;

import dev.dinesh.argus.config.SearchProperties;
import dev.dinesh.argus.model.Severity;
import dev.dinesh.argus.source.SourceOffset;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One validated search ({@code P5_PLAN.md} D6). Every bound is checked here, before anything is
 * sent to the engine; the tenant comes from the caller's API key, never from a parameter.
 *
 * @param minSeverity lines at least this severe (an OpenTelemetry number); 0 for every line
 * @param attributes {@code attributes.<key>} must equal the value
 */
public record SearchQuery(
    String tenantId,
    Instant from,
    Instant to,
    String q,
    int minSeverity,
    String source,
    String service,
    List<Attribute> attributes,
    int size) {

  public record Attribute(String key, String value) {}

  public static final int MAX_Q_CHARS = 1024;
  public static final int MAX_ATTRIBUTES = 10;
  public static final int MAX_VALUE_CHARS = 1024;
  public static final int DEFAULT_SIZE = 100;
  private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[\\w.-]{1,128}");

  /**
   * @param oldest the start of the hot window: a range starting earlier is in the archive only
   * @throws IllegalArgumentException for any parameter out of bounds
   */
  public static SearchQuery parse(
      String tenantId,
      String from,
      String to,
      String q,
      String level,
      String source,
      String service,
      List<String> attributes,
      Integer size,
      Instant oldest,
      SearchProperties props) {
    Instant start = instant("from", from);
    Instant end = instant("to", to);
    if (!end.isAfter(start)) {
      throw new IllegalArgumentException("to must be after from");
    }
    if (Duration.between(start, end).compareTo(props.maxRange()) > 0) {
      throw new IllegalArgumentException(
          "from and to are more than " + props.maxRange().toDays() + " days apart");
    }
    if (start.isBefore(oldest)) {
      throw new IllegalArgumentException(
          "from is before "
              + oldest
              + ", the start of the hot tier; older lines are only in the archive");
    }
    String query = q == null || q.isBlank() ? null : q;
    if (query != null && query.length() > MAX_Q_CHARS) {
      throw new IllegalArgumentException("q is longer than " + MAX_Q_CHARS + " characters");
    }
    int minSeverity = 0;
    if (level != null && !level.isBlank()) {
      Severity severity = Severity.of(level);
      if (severity == Severity.UNSPECIFIED) {
        throw new IllegalArgumentException("Unknown level: " + level);
      }
      minSeverity = severity.number();
    }
    String sourceId =
        source == null || source.isBlank() ? null : SourceOffset.requireValidSourceId(source);
    String serviceName = service == null || service.isBlank() ? null : bounded("service", service);
    List<Attribute> attrs = new ArrayList<>();
    if (attributes != null) {
      if (attributes.size() > MAX_ATTRIBUTES) {
        throw new IllegalArgumentException("At most " + MAX_ATTRIBUTES + " attr filters");
      }
      for (String a : attributes) {
        int colon = a.indexOf(':');
        String key = colon < 0 ? a : a.substring(0, colon);
        if (colon < 0 || !ATTRIBUTE_KEY.matcher(key).matches()) {
          throw new IllegalArgumentException(
              "attr must be key:value, the key letters, digits, '_', '.' or '-': " + a);
        }
        attrs.add(new Attribute(key, bounded("attr", a.substring(colon + 1))));
      }
    }
    int pageSize = size == null ? Math.min(DEFAULT_SIZE, props.maxSize()) : size;
    if (pageSize < 1 || pageSize > props.maxSize()) {
      throw new IllegalArgumentException("size must be between 1 and " + props.maxSize());
    }
    return new SearchQuery(
        tenantId,
        start,
        end,
        query,
        minSeverity,
        sourceId,
        serviceName,
        List.copyOf(attrs),
        pageSize);
  }

  private static Instant instant(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          name + " is required (ISO 8601, e.g. 2026-11-01T09:00:00Z)");
    }
    try {
      return Instant.parse(value.strip());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(name + " is not an ISO 8601 instant: " + value);
    }
  }

  private static String bounded(String name, String value) {
    if (value.length() > MAX_VALUE_CHARS) {
      throw new IllegalArgumentException(
          name + " is longer than " + MAX_VALUE_CHARS + " characters");
    }
    return value;
  }

  /** What the canonical line may say about the filters: never the text of {@code q}. */
  String describeFilters() {
    return "qChars="
        + (q == null ? 0 : q.length())
        + " minSeverity="
        + minSeverity
        + " source="
        + source
        + " service="
        + (service != null)
        + " attributes="
        + attributes.size();
  }
}
