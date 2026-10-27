package dev.dinesh.argus.suppression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Scheduler;
import dev.dinesh.argus.suppression.LocalSuppressionStore.Summary;
import dev.dinesh.argus.suppression.SuppressionDecision.Decision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LocalSuppressionStoreTest {

  private static final Instant T0 = Instant.parse("2026-10-27T09:00:00Z");
  private static final Duration WINDOW = Duration.ofSeconds(10);

  private final FakeTime time = new FakeTime(T0);
  private final List<Summary> summaries = new CopyOnWriteArrayList<>();

  private LocalSuppressionStore store(long maxKeys) {
    return new LocalSuppressionStore(
        WINDOW, maxKeys, time, time, Scheduler.disabledScheduler(), Runnable::run, summaries::add);
  }

  @Test
  void deniesUntilTheWindowEndsAndAllowsExactlyAtItsEnd() {
    LocalSuppressionStore store = store(100);

    assertEquals(SuppressionDecision.allow(T0.plus(WINDOW)), store.acquire("k", "m"));
    time.advance(Duration.ofMillis(9_999));
    assertEquals(SuppressionDecision.deny(T0.plus(WINDOW), 1), store.acquire("k", "m"));
    time.advance(Duration.ofMillis(1));
    SuppressionDecision reopened = store.acquire("k", "m");
    assertEquals(Decision.ALLOW, reopened.decision());
    assertEquals(T0.plus(WINDOW).plus(WINDOW), reopened.windowEndsAt());
  }

  @Test
  void countsEveryDeniedCopyAndKeepsKeysApart() {
    LocalSuppressionStore store = store(100);
    store.acquire("a", "m");

    assertEquals(1, store.acquire("a", "m").suppressedCount());
    assertEquals(2, store.acquire("a", "m").suppressedCount());
    assertTrue(store.acquire("b", "m").allowed());
  }

  @Test
  void exactlyOneOfManyConcurrentIdenticalCallsIsAllowed() throws Exception {
    LocalSuppressionStore store = store(100);
    int threads = 32;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<SuppressionDecision>> calls =
          IntStream.range(0, threads)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            start.await();
                            return store.acquire("hot", "m");
                          }))
              .toList();
      start.countDown();
      long allowed = 0;
      long maxCount = 0;
      for (Future<SuppressionDecision> call : calls) {
        SuppressionDecision d = call.get();
        allowed += d.allowed() ? 1 : 0;
        maxCount = Math.max(maxCount, d.suppressedCount());
      }

      assertEquals(1, allowed);
      assertEquals(threads - 1, maxCount);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void summarizesWindowsThatDeniedCopiesWhenTheyEnd() {
    LocalSuppressionStore store = store(100);
    store.acquire("noisy", "disk full");
    store.acquire("noisy", "disk full");
    store.acquire("noisy", "disk full");
    store.acquire("quiet", "once");

    time.advance(WINDOW);
    store.cleanUp();

    assertEquals(List.of(new Summary("noisy", "disk full", 2, WINDOW)), summaries);
  }

  @Test
  void summariesCutLongMessages() {
    LocalSuppressionStore store = store(100);
    String longMessage = "x".repeat(1_000);
    store.acquire("k", longMessage);
    store.acquire("k", longMessage);

    time.advance(WINDOW);
    store.cleanUp();

    assertEquals(
        LocalSuppressionStore.SUMMARY_MESSAGE_CHARS + 1, summaries.get(0).message().length());
  }

  @Test
  void holdsAtMostMaxKeysWindows() {
    LocalSuppressionStore store = store(10);

    IntStream.range(0, 1_000).forEach(i -> store.acquire("k" + i, "m"));

    assertTrue(store.size() <= 10, "size " + store.size());
  }
}
