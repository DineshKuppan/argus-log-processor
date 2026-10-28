package dev.dinesh.argus.sink;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Whether the WAL has room for another segment (P2 D7). Ingest asks before each new segment and
 * waits while it is full, so a sink outage pauses ingest (backpressure, guardrail 2) instead of
 * failing it. {@link #UNBOUNDED} when nothing drains the WAL (no archive store).
 */
public interface WalCapacity {

  /** Never full: the local segments are the output, limited only by {@code min-free-bytes}. */
  WalCapacity UNBOUNDED =
      new WalCapacity() {
        @Override
        public boolean isFull() {
          return false;
        }

        @Override
        public boolean awaitRoom(BooleanSupplier stopping, Consumer<Boolean> paused) {
          return true;
        }
      };

  /** True while new segments would wait (between crossing the high and the low watermark). */
  boolean isFull();

  /**
   * Returns once there is room; blocks while the WAL is full.
   *
   * @param stopping checked while waiting; when true, gives up
   * @param paused told {@code true} when the wait starts and {@code false} when it ends
   * @return false if it gave up because {@code stopping} became true
   */
  boolean awaitRoom(BooleanSupplier stopping, Consumer<Boolean> paused);
}
