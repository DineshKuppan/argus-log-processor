package dev.dinesh.argus.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.aggregate.impl.CountAggregator;
import dev.dinesh.argus.aggregate.impl.CountByLevelAggregator;
import dev.dinesh.argus.aggregate.impl.DurationAggregator;
import dev.dinesh.argus.aggregate.impl.ErrorRateAggregator;
import dev.dinesh.argus.aggregate.impl.TopNEndpointsAggregator;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

  private static final Instant T0 = Instant.parse("2025-10-09T01:00:00Z");

  /** 300 access-style records over 3 hours, with mixed levels and a few without a timestamp. */
  private static List<LogRecord> sample() {
    return IntStream.range(0, 300)
        .mapToObj(
            i ->
                new LogRecord(
                    i % 50 == 0 ? null : T0.plusSeconds(i * 37L),
                    i % 7 == 0 ? "ERROR" : i % 11 == 0 ? null : "INFO",
                    "GET /api/" + (i % 13) + " HTTP/1.1",
                    null))
        .toList();
  }

  static Stream<StreamingAggregator<?, ?>> aggregators() {
    return Stream.of(
        new CountAggregator(),
        new CountByLevelAggregator(),
        new DurationAggregator(ChronoUnit.HOURS),
        new ErrorRateAggregator(),
        new ErrorRateAggregator(ChronoUnit.MINUTES, "minute"),
        new TopNEndpointsAggregator(5));
  }

  @ParameterizedTest
  @MethodSource("aggregators")
  void mergingTheStatesOfSplitInputsEqualsOnePass(StreamingAggregator<?, ?> aggregator) {
    assertEquals(aggregator.compute(sample()), splitAndMerge(aggregator, sample(), 113));
  }

  private static <A, T> T splitAndMerge(
      StreamingAggregator<A, T> aggregator, List<LogRecord> records, int at) {
    A left = aggregator.newState();
    A right = aggregator.newState();
    records.subList(0, at).forEach(r -> aggregator.accept(left, r));
    records.subList(at, records.size()).forEach(r -> aggregator.accept(right, r));
    return aggregator.result(aggregator.merge(left, right));
  }

  private static LogRecord request(String path) {
    return new LogRecord(T0, "INFO", "GET " + path + " HTTP/1.1", null);
  }

  @Test
  public void freeTextLevelsAreCappedSoTheStateStaysBounded() {
    List<LogRecord> records =
        IntStream.range(0, 1_000)
            .mapToObj(i -> new LogRecord(T0, "level-" + i, "m", null))
            .toList();

    Map<String, Long> byLevel = new CountByLevelAggregator().compute(records);

    assertEquals(CountByLevelAggregator.MAX_LEVELS + 1, byLevel.size());
    assertEquals(
        1_000L - CountByLevelAggregator.MAX_LEVELS,
        byLevel.get(CountByLevelAggregator.OTHER_LEVELS));
  }

  @Test
  public void topEndpointsAreExactUpToCapacity() {
    TopNEndpointsAggregator top = new TopNEndpointsAggregator(2, 3);
    List<LogRecord> records =
        Stream.of("/a", "/b", "/a", "/c", "/a", "/b").map(AggregatorTest::request).toList();

    assertEquals(List.of(Map.entry("/a", 3L), Map.entry("/b", 2L)), top.compute(records));
  }

  @Test
  public void topEndpointsKeepHeavyHittersWithBoundedErrorBeyondCapacity() {
    int capacity = 10;
    TopNEndpointsAggregator top = new TopNEndpointsAggregator(1, capacity);
    // 5,000 hits on /hot interleaved with 5,000 unique paths: far more distinct paths than
    // counters.
    List<LogRecord> records =
        IntStream.range(0, 10_000)
            .mapToObj(i -> request(i % 2 == 0 ? "/hot" : "/unique/" + i))
            .toList();

    List<Entry<String, Long>> result = top.compute(records);
    long maxError = records.size() / (capacity + 1);

    assertEquals("/hot", result.get(0).getKey());
    assertTrue(result.get(0).getValue() <= 5_000);
    assertTrue(result.get(0).getValue() >= 5_000 - maxError, "count " + result.get(0).getValue());
    Map<String, long[]> state = top.newState();
    records.forEach(r -> top.accept(state, r));
    assertTrue(state.size() <= capacity);
  }
}
