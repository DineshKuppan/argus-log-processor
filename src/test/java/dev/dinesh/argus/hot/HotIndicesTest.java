package dev.dinesh.argus.hot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.model.LogEnvelope;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HotIndicesTest {

  static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  static HotProperties props(List<String> dedicated) {
    return new HotProperties(
        HotProperties.Store.OPENSEARCH,
        URI.create("http://localhost:9200"),
        null,
        null,
        null,
        null,
        dedicated,
        Duration.ofDays(14),
        0,
        0,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        null);
  }

  static LogEnvelope envelope(Instant timestamp, Instant observed) {
    return new LogEnvelope(
        "id",
        "acme",
        timestamp,
        observed,
        "INFO",
        9,
        "b",
        null,
        null,
        null,
        null,
        new LogEnvelope.Source("app", 0, 0, "json"));
  }

  private final HotIndices indices = new HotIndices(props(List.of("bigco")));

  @Test
  void namesPutTheDayFirstAndDedicatedTenantsApart() {
    LocalDate day = LocalDate.of(2026, 11, 1);
    assertEquals("argus-logs-2026.11.01-shared", indices.index("acme", day));
    assertEquals("argus-logs-2026.11.01-shared", indices.index("shared", day));
    assertEquals("argus-logs-2026.11.01-t-bigco", indices.index("bigco", day));
    assertEquals("acme", indices.routing("acme"));
    assertNull(indices.routing("bigco"));
  }

  @Test
  void theDayIsTheEventTimesElseTheObservedTime() {
    Instant observed = NOW.minusSeconds(5);
    assertEquals(
        LocalDate.of(2026, 10, 31),
        HotIndices.day(envelope(Instant.parse("2026-10-31T23:59:59Z"), observed), NOW));
    assertEquals(LocalDate.of(2026, 11, 1), HotIndices.day(envelope(null, observed), NOW));
    assertEquals(
        LocalDate.of(2026, 11, 1),
        HotIndices.day(envelope(NOW.plus(Duration.ofDays(2)), observed), NOW),
        "far future: the observed day");
    assertNull(
        HotIndices.eventTime(envelope(Instant.parse("+12026-01-01T00:00:00Z"), observed), NOW));
  }

  @Test
  void onlyOwnNamesParseAndRetentionKeepsFourteenDays() {
    assertEquals(
        Optional.of(LocalDate.of(2026, 10, 1)), indices.dayOf("argus-logs-2026.10.01-shared"));
    assertEquals(
        Optional.of(LocalDate.of(2026, 10, 1)), indices.dayOf("argus-logs-2026.10.01-t-bigco"));
    assertEquals(Optional.empty(), indices.dayOf("argus-logs-2026.10.01-other"));
    assertEquals(Optional.empty(), indices.dayOf("argus-logs-2026.13.01-shared"));
    assertEquals(Optional.empty(), indices.dayOf("other-2026.10.01-shared"));
    assertEquals(Optional.empty(), indices.dayOf("argus-logs-2026.10.01-shared-copy"));
    assertEquals(LocalDate.of(2026, 10, 18), indices.oldestKept(NOW));
  }

  @Test
  void aSearchReadsEachDaysSharedIndexAndADedicatedTenantsOwn() {
    Instant from = Instant.parse("2026-10-30T22:00:00Z");
    Instant to = Instant.parse("2026-11-01T00:00:00Z");
    assertEquals(
        List.of("argus-logs-2026.10.30-shared", "argus-logs-2026.10.31-shared"),
        indices.searchIndices("acme", from, to),
        "to is exclusive");
    assertEquals(
        List.of(
            "argus-logs-2026.10.30-shared",
            "argus-logs-2026.10.30-t-bigco",
            "argus-logs-2026.10.31-shared",
            "argus-logs-2026.10.31-t-bigco"),
        indices.searchIndices("bigco", from, to));
  }

  @Test
  void dedicatedTenantsMustBeLowercase() {
    assertThrows(IllegalArgumentException.class, () -> props(List.of("BigCo")));
    assertTrue(new HotIndices(props(List.of())).index("Acme", LocalDate.EPOCH).endsWith("-shared"));
  }
}
