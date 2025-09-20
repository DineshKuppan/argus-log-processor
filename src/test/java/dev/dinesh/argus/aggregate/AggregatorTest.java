package dev.dinesh.argus.aggregate;


import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dinesh.argus.aggregate.impl.CountByLevelAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class AggregatorTest {

  @Test
  public void testCountByLevel() {
    List<LogRecord> records = List.of(
        new LogRecord(Instant.now(), "ERROR", "msg1", null),
        new LogRecord(Instant.now(), "INFO", "msg2", null),
        new LogRecord(Instant.now(), "ERROR", "msg3", null)
    );
    CountByLevelAggregator byLevelAggregator = new CountByLevelAggregator();
    Map<String, Long> result = byLevelAggregator.compute(records);
    assertEquals(2, result.get("ERROR"));
    assertEquals(1, result.get("INFO"));
  }
}