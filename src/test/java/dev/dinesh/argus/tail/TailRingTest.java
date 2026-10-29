package dev.dinesh.argus.tail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TailRingTest {

  static TailEvent event(String body, int severity, int weight) {
    return new TailEvent(
        null, "app", "json", null, null, null, null, severity, body, null, null, null, null,
        weight);
  }

  static List<TailEvent> events(int count) {
    return IntStream.range(0, count).mapToObj(i -> event("line " + i, 9, 10)).toList();
  }

  @Test
  void readsFromACursorInOrder() throws InterruptedException {
    TailRing ring = new TailRing(10, 1 << 20);
    ring.append(events(4));

    TailRing.Read read = ring.read(1, 2, 0);

    assertEquals(1, read.first());
    assertEquals(List.of("line 1", "line 2"), read.events().stream().map(TailEvent::body).toList());
    assertEquals(0, read.dropped());
    assertEquals(3, read.next());
  }

  @Test
  void anOvertakenCursorReportsWhatItMissed() throws InterruptedException {
    TailRing ring = new TailRing(3, 1 << 20);
    ring.append(events(5));

    TailRing.Read read = ring.read(0, 10, 0);

    assertEquals(2, read.dropped());
    assertEquals(2, read.first());
    assertEquals(
        List.of("line 2", "line 3", "line 4"),
        read.events().stream().map(TailEvent::body).toList());
  }

  @Test
  void theByteBoundDropsTheOldestButKeepsTheNewestEvenIfLarger() throws InterruptedException {
    TailRing ring = new TailRing(100, 25);
    ring.append(events(5));
    assertEquals(2, ring.size());
    assertEquals(20, ring.bytes());

    ring.append(List.of(event("huge", 9, 1000)));

    assertEquals(1, ring.size());
    assertEquals("huge", ring.read(0, 10, 0).events().get(0).body());
  }

  @Test
  void aReaderWaitsUntilAnAppendAndCloseEndsTheWait() throws Exception {
    TailRing ring = new TailRing(10, 1 << 20);
    CompletableFuture<TailRing.Read> waiting =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return ring.read(0, 10, 10_000);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
    Thread.sleep(50);
    ring.append(events(1));
    assertEquals(1, waiting.get(2, TimeUnit.SECONDS).events().size());

    CompletableFuture<TailRing.Read> closing =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return ring.read(1, 10, 10_000);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
    Thread.sleep(50);
    ring.close();
    assertNull(closing.get(2, TimeUnit.SECONDS));
  }

  @Test
  void aTimedOutReadIsEmpty() throws InterruptedException {
    TailRing ring = new TailRing(10, 1 << 20);
    long start = System.nanoTime();
    TailRing.Read read = ring.read(0, 10, 50);
    assertTrue(read.events().isEmpty());
    assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(45));
  }
}
