package dev.dinesh.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SuppressionPropertiesTest {

  @Test
  void defaultsToTenSecondsInMemory() {
    SuppressionProperties p = new SuppressionProperties(null, 0, null, null);

    assertEquals(Duration.ofSeconds(10), p.window());
    assertEquals(100_000, p.maxKeys());
    assertEquals(SuppressionProperties.Store.LOCAL, p.store());
  }

  @Test
  void rejectsWindowsShorterThanRedisCanExpire() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SuppressionProperties(Duration.ofNanos(500_000), 0, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SuppressionProperties(Duration.ZERO, 0, null, null));
    assertEquals(
        Duration.ofMillis(1),
        new SuppressionProperties(Duration.ofMillis(1), 0, null, null).window());
  }
}
