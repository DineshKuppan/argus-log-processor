package dev.dinesh.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KafkaPropertiesTest {

  private static KafkaProperties props(Duration maxOutage, Duration orphanTimeout) {
    return new KafkaProperties(
        "localhost:9092",
        null,
        false,
        null,
        0,
        (short) 0,
        null,
        maxOutage,
        0,
        orphanTimeout,
        null,
        null,
        null,
        null,
        null,
        Map.of("sasl.jaas.config", "secret"));
  }

  @Test
  void defaultsMatchThePlan() {
    KafkaProperties p = props(null, null);

    assertEquals("argus-wal", p.topic());
    assertEquals(KafkaProperties.Check.FAIL, p.checkTopic());
    assertEquals(12, p.partitions());
    assertEquals(3, p.replicationFactor());
    assertEquals(Duration.ofDays(7), p.minRetention());
    assertEquals(Duration.ofMinutes(10), p.maxOutage());
    assertEquals(100_000_000L, p.maxLagRecords());
    assertEquals(Duration.ofMinutes(15), p.orphanTimeout());
  }

  @Test
  void anOrphanTimeoutNotLongerThanTheMaxOutageIsRefused() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> props(Duration.ofMinutes(10), Duration.ofMinutes(10)));
    assertTrue(e.getMessage().contains("orphan-timeout"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> props(Duration.ofSeconds(30), null));
  }

  @Test
  void toStringHidesTheClientProperties() {
    assertFalse(props(null, null).toString().contains("secret"));
  }
}
