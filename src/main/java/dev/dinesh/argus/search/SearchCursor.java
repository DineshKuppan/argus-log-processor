package dev.dinesh.argus.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The opaque {@code cursor} of a search page ({@code P5_PLAN.md} D6): the last hit's sort values
 * ({@code search_after}) and a hash of the query, so a cursor only continues the search it came
 * from.
 */
final class SearchCursor {

  private SearchCursor() {}

  /** Identifies a search, page size included; not the cursor itself. */
  static String hash(SearchQuery query) {
    String identity =
        String.join(
            "\u0000",
            query.tenantId(),
            query.from().toString(),
            query.to().toString(),
            String.valueOf(query.q()),
            Integer.toString(query.minSeverity()),
            String.valueOf(query.source()),
            String.valueOf(query.service()),
            query.attributes().toString(),
            Integer.toString(query.size()));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String encode(SearchQuery query, JsonNode sortValues, ObjectMapper mapper)
      throws IOException {
    ObjectNode cursor = mapper.createObjectNode().put("h", hash(query));
    cursor.set("a", sortValues);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(cursor));
  }

  /**
   * The sort values to continue after.
   *
   * @throws IllegalArgumentException if the cursor is malformed or from another search
   */
  static ArrayNode decode(String cursor, SearchQuery query, ObjectMapper mapper) {
    JsonNode node;
    try {
      node = mapper.readTree(Base64.getUrlDecoder().decode(cursor.strip()));
    } catch (IOException | IllegalArgumentException e) {
      throw new IllegalArgumentException("cursor is not a cursor this API returned");
    }
    if (node == null
        || !hash(query).equals(node.path("h").asText())
        || !(node.get("a") instanceof ArrayNode after)
        || after.size() != 2) {
      throw new IllegalArgumentException(
          "cursor belongs to another search: repeat the same parameters with it");
    }
    return after;
  }
}
