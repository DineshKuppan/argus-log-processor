package dev.dinesh.argus.parser;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/** Lenient timestamp parsing for the field values structured logs use. */
final class Timestamps {

  // Epoch values below these are seconds, milliseconds and microseconds respectively.
  private static final long MAX_EPOCH_SECONDS = 100_000_000_000L;
  private static final long MAX_EPOCH_MILLIS = 100_000_000_000_000L;
  private static final long MAX_EPOCH_MICROS = 100_000_000_000_000_000L;

  /** {@code 2025-10-09 01:11:40}, optionally with a fraction after '.' or ','; read as UTC. */
  private static final DateTimeFormatter LOCAL_DATE_TIME =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd[ ]['T']HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
          .optionalEnd()
          .toFormatter();

  private Timestamps() {}

  /** Parses an ISO-8601 string, a "yyyy-MM-dd HH:mm:ss" string (UTC) or an epoch number. */
  static Instant parse(Object value) {
    if (value instanceof Number n) {
      return fromEpoch(n.longValue());
    }
    if (!(value instanceof String s) || s.isBlank()) {
      return null;
    }
    String text = s.trim().replace(',', '.');
    if (isDigits(text)) {
      return fromEpoch(Long.parseLong(text));
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeException e) {
      // not ISO-8601 with an offset; try without one
    }
    try {
      return LocalDateTime.parse(text, LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
    } catch (DateTimeException e) {
      return null;
    }
  }

  private static Instant fromEpoch(long value) {
    if (value < MAX_EPOCH_SECONDS) {
      return Instant.ofEpochSecond(value);
    }
    if (value < MAX_EPOCH_MILLIS) {
      return Instant.ofEpochMilli(value);
    }
    if (value < MAX_EPOCH_MICROS) {
      return Instant.ofEpochSecond(value / 1_000_000, (value % 1_000_000) * 1_000);
    }
    return Instant.ofEpochSecond(value / 1_000_000_000, value % 1_000_000_000);
  }

  private static boolean isDigits(String s) {
    if (s.length() > 18) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
