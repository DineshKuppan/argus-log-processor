package dev.dinesh.argus.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dinesh.argus.aggregate.impl.CountByLevelAggregator;
import dev.dinesh.argus.aggregate.impl.DurationAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class AggregatorTest {

  @Test
  public void testCountByLevel() {
    List<LogRecord> records =
        List.of(
            new LogRecord(Instant.now(), "ERROR", "msg1", null),
            new LogRecord(Instant.now(), "INFO", "msg2", null),
            new LogRecord(Instant.now(), "ERROR", "msg3", null));
    CountByLevelAggregator byLevelAggregator = new CountByLevelAggregator();
    Map<String, Long> result = byLevelAggregator.compute(records);
    assertEquals(2, result.get("ERROR"));
    assertEquals(1, result.get("INFO"));
  }

  @Test
  public void durationBucketsCountRecordsWithoutALevel() {
    Instant t = Instant.parse("2025-10-09T01:11:40Z");
    List<LogRecord> records =
        List.of(
            new LogRecord(t, null, "json line without a level", null),
            new LogRecord(t, "INFO", "msg", null));

    List<Map<String, Object>> result = new DurationAggregator(ChronoUnit.HOURS).compute(records);

    assertEquals(1, result.size());
    assertEquals(1L, result.get(0).get("-"));
    assertEquals(1L, result.get(0).get("INFO"));
  }
}
