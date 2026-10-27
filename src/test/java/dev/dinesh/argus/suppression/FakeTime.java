package dev.dinesh.argus.suppression;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A clock and a ticker that only move when told to, so window boundaries can be hit exactly. */
final class FakeTime extends Clock implements Ticker {

  private final Instant start;
  private final AtomicLong elapsedNanos = new AtomicLong();

  FakeTime(Instant start) {
    this.start = start;
  }

  void advance(Duration d) {
    elapsedNanos.addAndGet(d.toNanos());
  }

  @Override
  public long read() {
    return elapsedNanos.get();
  }

  @Override
  public Instant instant() {
    return start.plusNanos(elapsedNanos.get());
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException();
  }
}
