package dev.dinesh.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class LogPropertiesTest {

  @Test
  void zeroMeansTheDefault() {
    LogProperties p = new LogProperties(DataSize.ofBytes(0), 0, null);

    assertEquals(DataSize.ofMegabytes(50), p.maxFileSize());
    assertEquals(2, p.maxConcurrentAggregations());
    assertEquals(true, p.enforceAllowedRoots());
  }

  @Test
  void negativeValuesFailAtStartup() {
    assertThrows(IllegalArgumentException.class, () -> new LogProperties(null, -1, null));
    assertThrows(
        IllegalArgumentException.class, () -> new LogProperties(DataSize.ofBytes(-1), 0, null));
  }
}
