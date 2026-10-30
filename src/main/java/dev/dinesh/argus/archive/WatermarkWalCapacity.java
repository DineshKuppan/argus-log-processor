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
 * <p>{@link #awaitRoom} measures afresh at each segment boundary, so the WAL passes the high
 * watermark by at most the segment being written per job; {@link #isFull} (new-job checks) and the
 * polling while paused reuse a measurement for up to {@code refresh}. A WAL scan is cheap: it holds
 * tens of segments.
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
  public boolean isFull() {
    return isFull(false);
  }

  /** Pending WAL bytes, as last measured (at most {@code refresh} old). */
  public synchronized long pendingBytes() {
    isFull(false);
    return lastBytes;
  }

  /** {@code fresh}: measure now, ignoring the cached size. */
  private synchronized boolean isFull(boolean fresh) {
    long now = System.nanoTime();
    if (fresh || !measured || now - measuredAt >= refreshNanos) {
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
    // Fresh at each segment boundary: a cached size from before the last commits could let
    // another whole segment past the high watermark.
    if (!isFull(true)) {
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
