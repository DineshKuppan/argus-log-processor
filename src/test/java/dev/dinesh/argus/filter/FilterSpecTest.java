package dev.dinesh.argus.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class FilterSpecTest {

  @Test
  public void testToPredicate() {
    Instant now = Instant.now();
    LogRecord record = new LogRecord(now, "ERROR", "Something failed", null);
    FilterSpec spec = new FilterSpec(
        now.minusSeconds(10),
        now.plusSeconds(10),
        Set.of("ERROR"),
        Pattern.compile("failed")
    );
    assertTrue(spec.toPredicate().test(record));
  }

  @Test
  public void testToPredicateFails() {
    Instant now = Instant.now();
    LogRecord record = new LogRecord(now, "INFO", "All good", null);
    FilterSpec spec = new FilterSpec(
        now.minusSeconds(10),
        now.plusSeconds(10),
        Set.of("ERROR"),
        Pattern.compile("failed")
    );
    assertFalse(spec.toPredicate().test(record));
  }
}