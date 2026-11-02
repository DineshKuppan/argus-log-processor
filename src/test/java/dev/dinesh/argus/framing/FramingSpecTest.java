package dev.dinesh.argus.framing;

import static dev.dinesh.argus.framing.FramerFixture.LIMITS;
import static dev.dinesh.argus.framing.FramerFixture.bodies;
import static dev.dinesh.argus.framing.FramerFixture.chain;
import static dev.dinesh.argus.framing.FramerFixture.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FramingSpecTest {

  private static FramingSpec spec(String value) {
    return FramingSpec.parse(value).orElseThrow();
  }

  @Test
  void parsesEachMode() {
    assertEquals(Optional.empty(), FramingSpec.parse(null));
    assertEquals(Optional.empty(), FramingSpec.parse(" "));
    assertSame(FramingSpec.AUTO, spec("auto"));
    assertSame(FramingSpec.NONE, spec("none"));
    FramingSpec start = spec("start:^\\d{4}-");
    assertEquals(FramingSpec.Mode.START, start.mode());
    assertEquals("start:^\\d{4}-", start.toString());
    assertEquals(start, spec("start:^\\d{4}-"));
  }

  @Test
  void rejectsUnknownValuesAndBadPatterns() {
    assertThrows(IllegalArgumentException.class, () -> FramingSpec.parse("multiline"));
    assertThrows(IllegalArgumentException.class, () -> FramingSpec.parse("AUTO"));
    assertThrows(IllegalArgumentException.class, () -> FramingSpec.parse("start:"));
    assertThrows(IllegalArgumentException.class, () -> FramingSpec.parse("start:("));
    assertThrows(
        IllegalArgumentException.class, () -> FramingSpec.parse("start:" + "a".repeat(257)));
    assertEquals(FramingSpec.Mode.START, spec("start:" + "a".repeat(256)).mode());
  }

  @Test
  void makesTheFramerForEachMode() {
    assertInstanceOf(StackTraceFramer.class, FramingSpec.AUTO.framer(LIMITS));
    assertSame(NoFraming.INSTANCE, FramingSpec.NONE.framer(LIMITS));
    assertInstanceOf(StartPatternFramer.class, spec("start:^x").framer(LIMITS));
    // Joining framers hold a run's open record: never shared between runs.
    assertNotSame(FramingSpec.AUTO.framer(LIMITS), FramingSpec.AUTO.framer(LIMITS));
  }

  @Test
  void noneKeepsEveryLineAsARecord() {
    List<FramedRecord> records =
        frame(
            FramingSpec.NONE.framer(LIMITS),
            chain("kv", false),
            "level=ERROR msg=a",
            "\tat x(X.java:1)",
            "msg=b");

    // The frame is dropped as unparsed, as before P7.
    assertEquals(List.of("a", "b"), bodies(records));
    assertEquals(1, records.get(1).lines());
  }

  @Test
  void startPatternStartsARecordWhereverItMatchesAndJoinsEverythingElse() {
    List<FramedRecord> records =
        frame(
            spec("start:^\\[\\d{4}-").framer(LIMITS),
            chain("raw", true),
            "continued before any start",
            "[2025-08-21 12:00:00] first",
            "  detail",
            "not indented either",
            "[2025-08-21 12:00:01] second");

    assertEquals(
        List.of(
            "continued before any start",
            "[2025-08-21 12:00:00] first\n  detail\nnot indented either",
            "[2025-08-21 12:00:01] second"),
        bodies(records));
    assertEquals(
        RecordFramer.ORPHAN,
        records.get(0).parsed().record().getMeta().get(RecordFramer.FRAMING_ATTRIBUTE));
  }

  @Test
  void startPatternIsSearchedInTheFirstCharactersOnly() {
    List<FramedRecord> records =
        frame(
            spec("start:START").framer(LIMITS),
            chain("raw", true),
            "START one",
            "x".repeat(300) + "START too late");

    assertEquals(1, records.size());
    assertTrue(bodies(records).get(0).endsWith("START too late"));
  }

  @Test
  void aBacktrackingStartPatternIsAbandonedNotRunForever() {
    StartPatternFramer framer = (StartPatternFramer) spec("start:(.*a){8}!").framer(LIMITS);
    // 7.7s for 40 characters on JDK 17; far longer for 60, without the budget.
    String line = "a".repeat(60);
    long started = System.nanoTime();

    List<FramedRecord> records = frame(framer, chain("raw", true), line, line, "b");

    assertTrue(System.nanoTime() - started < 5_000_000_000L, "took too long");
    assertEquals(2, framer.abandoned());
    // An abandoned search starts a record, as with framing none.
    assertEquals(List.of(line, line + "\nb"), bodies(records));
  }
}
