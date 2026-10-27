package dev.dinesh.argus.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SeverityTest {

  @ParameterizedTest
  @CsvSource({
    "TRACE, TRACE",
    "debug, DEBUG",
    "' Info ', INFO",
    "notice, INFO",
    "warning, WARN",
    "WARN, WARN",
    "err, ERROR",
    "SEVERE, ERROR",
    "critical, FATAL",
    "panic, FATAL",
    "30, INFO",
    "50, ERROR",
    "60, FATAL",
    "loud, UNSPECIFIED",
    "35, UNSPECIFIED"
  })
  void mapsCommonLevelSpellings(String level, Severity expected) {
    assertEquals(expected, Severity.of(level));
  }

  @ParameterizedTest
  @CsvSource({"TRACE, 1", "DEBUG, 5", "INFO, 9", "WARN, 13", "ERROR, 17", "FATAL, 21"})
  void usesOpenTelemetryNumbers(Severity severity, int number) {
    assertEquals(number, severity.number());
    assertEquals(Severity.UNSPECIFIED, Severity.of(null));
  }
}
