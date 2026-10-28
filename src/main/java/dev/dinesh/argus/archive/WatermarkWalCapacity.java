package dev.dinesh.argus.archive;

import dev.dinesh.argus.config.WalProperties;
import dev.dinesh.argus.sink.WalCapacity;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link WalCapacity} from the bytes of pending (not yet archived) WAL segments, with hysteresis:
 * full once they reach the high watermark, and again free only below the low watermark.
 * Thread-safe; shared by every ingest job.
 *
 * <p>The size is re-measured at most once per {@code refresh}: the WAL holds tens of segments, so a
 * scan is cheap, but every job asks before each segment.
 */
@Slf4j
public final class WatermarkWalCapacity implements WalCapacity {

  private static final Duration REFRESH = Duration.ofSeconds(1);

  private final LongSupplier pendingBytes;
  private final long highBytes;
  private final long lowBytes;
  private final long refreshNanos;
  private final long pollMillis;

  private boolean full;
  private long measuredAt;
  private long lastBytes;
  private boolean measured;

  public WatermarkWalCapacity(WalSegments wal, WalProperties props) {
    this(() -> pendingBytes(wal), props.highBytes(), props.lowBytes(), REFRESH);
  }

  WatermarkWalCapacity(LongSupplier pendingBytes, long highBytes, long lowBytes, Duration refresh) {
    this.pendingBytes = pendingBytes;
    this.highBytes = highBytes;
    this.lowBytes = lowBytes;
    this.refreshNanos = refresh.toNanos();
    this.pollMillis = Math.max(1, refresh.toMillis());
  }

  @Override
  public synchronized boolean isFull() {
    long now = System.nanoTime();
    if (!measured || now - measuredAt >= refreshNanos) {
      lastBytes = pendingBytes.getAsLong();
      measuredAt = now;
      measured = true;
      if (!full && lastBytes >= highBytes) {
        full = true;
        log.warn(
            "WAL full: {} pending bytes ≥ high watermark {}; new segments wait until the archive"
                + " drains it below {}",
            lastBytes,
            highBytes,
            lowBytes);
      } else if (full && lastBytes < lowBytes) {
        full = false;
        log.info(
            "WAL below the low watermark ({} < {} bytes); ingest resumes", lastBytes, lowBytes);
      }
    }
    return full;
  }

  @Override
  public boolean awaitRoom(BooleanSupplier stopping, Consumer<Boolean> paused) {
    if (!isFull()) {
      return true;
    }
    paused.accept(true);
    try {
      while (isFull()) {
        if (stopping.getAsBoolean()) {
          return false;
        }
        Thread.sleep(pollMillis);
      }
      return !stopping.getAsBoolean();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      paused.accept(false);
    }
  }

  /** Fails open: if the WAL can't be measured, ingest isn't paused because of it. */
  private static long pendingBytes(WalSegments wal) {
    try {
      long total = 0;
      for (WalSegments.Segment segment : wal.pending()) {
        try {
          total += Files.size(segment.file());
        } catch (IOException e) {
          // archived (deleted) since the listing
        }
      }
      return total;
    } catch (IOException | RuntimeException e) {
      log.warn("Could not measure the WAL; not pausing ingest for it", e);
      return 0;
    }
  }
}
