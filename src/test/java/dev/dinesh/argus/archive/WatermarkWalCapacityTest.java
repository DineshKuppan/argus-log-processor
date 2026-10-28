package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.config.WalProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class WatermarkWalCapacityTest {

  private final AtomicLong pending = new AtomicLong();

  /** High 80, low 60; re-measured on every call. */
  private WatermarkWalCapacity capacity() {
    return new WatermarkWalCapacity(pending::get, 80, 60, Duration.ZERO);
  }

  @Test
  void fullFromTheHighWatermarkUntilBelowTheLow() {
    WatermarkWalCapacity capacity = capacity();

    pending.set(79);
    assertFalse(capacity.isFull());
    pending.set(80);
    assertTrue(capacity.isFull());
    pending.set(60); // between the watermarks: still full
    assertTrue(capacity.isFull());
    pending.set(59);
    assertFalse(capacity.isFull());
    pending.set(70); // between the watermarks again: still free
    assertFalse(capacity.isFull());
  }

  @Test
  void awaitRoomReturnsAtOnceWhenThereIsRoom() {
    List<Boolean> events = new ArrayList<>();

    assertTrue(capacity().awaitRoom(() -> false, events::add));
    assertEquals(List.of(), events);
  }

  @Test
  void awaitRoomWaitsUntilTheWalDrainsBelowTheLowWatermark() throws Exception {
    WatermarkWalCapacity capacity = capacity();
    pending.set(90);
    List<Boolean> events = new ArrayList<>();
    CompletableFuture<Boolean> waiting =
        CompletableFuture.supplyAsync(() -> capacity.awaitRoom(() -> false, events::add));

    Thread.sleep(50);
    assertFalse(waiting.isDone());
    pending.set(70);
    Thread.sleep(50);
    assertFalse(waiting.isDone(), "70 is above the low watermark");
    pending.set(10);

    assertTrue(waiting.get(5, TimeUnit.SECONDS));
    assertEquals(List.of(true, false), events);
  }

  @Test
  void awaitRoomGivesUpWhenTheRunStops() throws Exception {
    WatermarkWalCapacity capacity = capacity();
    pending.set(90);
    AtomicBoolean cancelled = new AtomicBoolean();
    List<Boolean> events = new ArrayList<>();
    CompletableFuture<Boolean> waiting =
        CompletableFuture.supplyAsync(() -> capacity.awaitRoom(cancelled::get, events::add));

    Thread.sleep(50);
    cancelled.set(true);

    assertFalse(waiting.get(5, TimeUnit.SECONDS));
    assertEquals(List.of(true, false), events);
  }

  @Test
  void measuresPendingSegmentsOnly(@TempDir Path root) throws Exception {
    WalFixture wal = new WalFixture(root, LocalSinkProperties.Compression.NONE);
    Path segment = wal.segment("acme", "app", 0, 0, 50, Instant.EPOCH);
    long size = segment.toFile().length();
    WalProperties limits = new WalProperties(DataSize.ofBytes(size), 1.0, 0.5);

    assertTrue(new WatermarkWalCapacity(new WalSegments(root), limits).isFull());
    new WalSegments(root).markArchived(new WalSegments(root).pending().get(0));
    assertFalse(new WatermarkWalCapacity(new WalSegments(root), limits).isFull());
  }

  @Test
  void rejectsWatermarksOutOfOrder() {
    assertThrows(IllegalArgumentException.class, () -> new WalProperties(null, 0.5, 0.6));
    assertThrows(IllegalArgumentException.class, () -> new WalProperties(null, 1.2, 0.6));
    assertThrows(
        IllegalArgumentException.class, () -> new WalProperties(DataSize.ofBytes(0), null, null));
    WalProperties defaults = new WalProperties(null, null, null);
    assertEquals(24L * 1024 * 1024 * 1024, defaults.highBytes());
    assertEquals(18L * 1024 * 1024 * 1024, defaults.lowBytes());
  }
}
