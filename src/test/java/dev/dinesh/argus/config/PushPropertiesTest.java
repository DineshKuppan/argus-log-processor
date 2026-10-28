package dev.dinesh.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class PushPropertiesTest {

  @Test
  void defaultsMatchThePlan() {
    PushProperties p = new PushProperties(null, null, 0, 0, null, null, null, 0, 0, 0, null);

    assertEquals(Path.of("data", "spool"), p.spoolDir());
    assertEquals(DataSize.ofMegabytes(8), p.maxRequestBytes());
    assertEquals(10_000, p.maxRequestLines());
    assertEquals(16, p.maxConcurrentRequests());
    assertEquals(Duration.ofMinutes(1), p.flushInterval());
    assertEquals(DataSize.ofGigabytes(2), p.maxSpoolBytes());
    assertEquals(DataSize.ofMegabytes(64), p.spoolRotateBytes());
    assertEquals(32, p.maxStreamsPerTenant());
    assertEquals(2, p.maxConcurrentRuns());
    assertEquals(1024, p.idempotencyWindow());
    assertTrue(p.suppress());
  }

  @Test
  void rejectsLimitsThatCannotWork() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PushProperties(
                null, DataSize.ofGigabytes(1), 0, 0, null, null, null, 0, 0, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PushProperties(null, null, 0, 0, Duration.ZERO, null, null, 0, 0, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PushProperties(null, null, 0, 0, null, null, DataSize.ofBytes(0), 0, 0, 0, null));
  }
}
