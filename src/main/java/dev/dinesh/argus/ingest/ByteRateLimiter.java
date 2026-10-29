package dev.dinesh.argus.ingest;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Paces bytes to an average rate, shared by every caller. Each call passes once the bytes before it
 * have had their time, so a burst is at most one call's bytes, and idle time saves up no credit.
 */
public final class ByteRateLimiter {

  /** Waits are cut into slices this long, so a stop request is seen promptly. */
  private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

  private final double nanosPerByte;
  private final LongSupplier nanoTime;
  private long nextFreeNanos;

  public ByteRateLimiter(long bytesPerSecond) {
    this(bytesPerSecond, System::nanoTime);
  }

  ByteRateLimiter(long bytesPerSecond, LongSupplier nanoTime) {
    if (bytesPerSecond <= 0) {
      throw new IllegalArgumentException("bytesPerSecond must be positive: " + bytesPerSecond);
    }
    this.nanosPerByte = 1e9 / bytesPerSecond;
    this.nanoTime = nanoTime;
    this.nextFreeNanos = nanoTime.getAsLong();
  }

  /**
   * Blocks until {@code bytes} may pass.
   *
   * @return false if {@code stop} became true while waiting (the bytes still count)
   * @throws InterruptedException if interrupted while waiting
   */
  public boolean acquire(long bytes, BooleanSupplier stop) throws InterruptedException {
    long passAt;
    synchronized (this) {
      passAt = Math.max(nextFreeNanos, nanoTime.getAsLong());
      nextFreeNanos = passAt + (long) (bytes * nanosPerByte);
    }
    for (long wait = passAt - nanoTime.getAsLong();
        wait > 0;
        wait = passAt - nanoTime.getAsLong()) {
      if (stop.getAsBoolean()) {
        return false;
      }
      TimeUnit.NANOSECONDS.sleep(Math.min(wait, SLICE_NANOS));
    }
    return true;
  }
}
