package dev.dinesh.argus.sink;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The one rule for turning an event time into partition directories, shared by the WAL ({@code
 * dt=}) and the archive ({@code dt=}/{@code hour=}) so the two always agree: UTC, and {@code
 * unknown} for records without an event time. Event time, not ingest time, so a replay lands on the
 * same path.
 */
public final class EventTimePartition {

  public static final String UNKNOWN = "unknown";

  private EventTimePartition() {}

  /** {@code YYYY-MM-DD} in UTC, or {@code unknown}. */
  public static String day(Instant eventTime) {
    return eventTime == null ? UNKNOWN : eventTime.atZone(ZoneOffset.UTC).toLocalDate().toString();
  }

  /** {@code dt=YYYY-MM-DD/hour=HH} in UTC, or {@code dt=unknown/hour=unknown}. */
  public static String dayAndHour(Instant eventTime) {
    if (eventTime == null) {
      return "dt=" + UNKNOWN + "/hour=" + UNKNOWN;
    }
    ZonedDateTime utc = eventTime.atZone(ZoneOffset.UTC);
    return "dt=" + utc.toLocalDate() + "/hour=" + String.format("%02d", utc.getHour());
  }
}
