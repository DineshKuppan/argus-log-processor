package dev.dinesh.argus.aggregate.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TopNEndpointsAggregatorTest {

  /** The endpoint rule v1 always had: the second token of {@code split(" ")}, else "-". */
  private static String bySplit(String message) {
    String[] parts = message.split(" ");
    return parts.length >= 2 ? parts[1] : "-";
  }

  @Test
  void endpointMatchesTheSplitRuleOnRandomMessages() {
    Random random = new Random(42);
    String alphabet = "  ab/";
    for (int i = 0; i < 20_000; i++) {
      StringBuilder sb = new StringBuilder();
      for (int j = random.nextInt(8); j > 0; j--) {
        sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String message = sb.toString();
      assertEquals(
          bySplit(message), TopNEndpointsAggregator.endpointOf(message), "'" + message + "'");
    }
    assertEquals("/api", TopNEndpointsAggregator.endpointOf("GET /api HTTP/1.1"));
  }

  @Test
  void rejectsANegativeTopNBeforeAnyRecordIsRead() {
    assertThrows(IllegalArgumentException.class, () -> new TopNEndpointsAggregator(-1));
  }
}
