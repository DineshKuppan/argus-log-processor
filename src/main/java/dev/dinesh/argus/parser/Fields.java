package dev.dinesh.argus.parser;

import java.util.List;
import java.util.Map;

/** Extracts well-known fields (timestamp, level, trace id, ...) from a parsed attribute map. */
public final class Fields {

  private Fields() {}

  /** Removes and returns the value of the first key present, even if that value is null. */
  public static Object take(Map<String, Object> fields, List<String> keys) {
    for (String key : keys) {
      if (fields.containsKey(key)) {
        return fields.remove(key);
      }
    }
    return null;
  }

  /**
   * Removes and returns, as text, the first value under {@code keys} that is a string, number or
   * boolean. Objects and arrays stay in place: their {@code toString()} is not a usable value.
   */
  public static String takeText(Map<String, Object> fields, List<String> keys) {
    for (String key : keys) {
      Object value = fields.get(key);
      if (isScalar(value)) {
        fields.remove(key);
        return value.toString();
      }
    }
    return null;
  }

  public static String asText(Object value) {
    return value == null ? null : value.toString();
  }

  private static boolean isScalar(Object value) {
    return value instanceof CharSequence || value instanceof Number || value instanceof Boolean;
  }
}
