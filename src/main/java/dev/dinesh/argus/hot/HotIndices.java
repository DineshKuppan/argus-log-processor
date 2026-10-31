package dev.dinesh.argus.hot;

import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.model.LogEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index names of the hot tier ({@code P5_PLAN.md} D3, D4): one index per UTC day of event time,
 * {@code <prefix>-YYYY.MM.DD-shared} or {@code <prefix>-YYYY.MM.DD-t-<tenant>} for a dedicated
 * tenant. The day comes first, so no tenant id can make a name another index's. A line maps to the
 * same index whenever it is indexed, so re-indexing overwrites rather than duplicates.
 */
public final class HotIndices {

  /** A line more than this far in the future is indexed under the day it was observed. */
  static final Duration MAX_FUTURE = Duration.ofDays(1);

  static final String SHARED = "shared";
  static final String DEDICATED = "t-";

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy.MM.dd");

  private final String prefix;
  private final Set<String> dedicated;
  private final long retentionDays;
  private final Pattern ownName;

  public HotIndices(HotProperties props) {
    this.prefix = props.indexPrefix();
    this.dedicated = Set.copyOf(props.dedicatedTenants());
    this.retentionDays = Math.max(1, props.retention().toDays());
    this.ownName =
        Pattern.compile(
            Pattern.quote(prefix)
                + "-(\\d{4}\\.\\d{2}\\.\\d{2})-("
                + SHARED
                + "|"
                + DEDICATED
                + "[a-z0-9][a-z0-9._-]{0,63})");
  }

  /** The index template's pattern. */
  public String pattern() {
    return prefix + "-*";
  }

  public String prefix() {
    return prefix;
  }

  public boolean isDedicated(String tenantId) {
    return dedicated.contains(tenantId);
  }

  public String index(String tenantId, LocalDate day) {
    return prefix
        + "-"
        + DAY.format(day)
        + "-"
        + (isDedicated(tenantId) ? DEDICATED + tenantId : SHARED);
  }

  /** Routing for a write: the tenant in the shared index (one shard per tenant), none otherwise. */
  public String routing(String tenantId) {
    return isDedicated(tenantId) ? null : tenantId;
  }

  /**
   * The event time Argus indexes, or null when the line has none usable: none at all, more than
   * {@link #MAX_FUTURE} ahead, or outside the years 0000–9999 that an ISO date can hold.
   */
  public static Instant eventTime(LogEnvelope envelope, Instant now) {
    Instant t = envelope.timestamp();
    if (t == null || t.isAfter(now.plus(MAX_FUTURE))) {
      return null;
    }
    int year = t.atOffset(ZoneOffset.UTC).getYear();
    return year < 0 || year > 9999 ? null : t;
  }

  /** The day a line is indexed under: its event time's, else its observed time's. */
  public static LocalDate day(LogEnvelope envelope, Instant now) {
    Instant t = eventTime(envelope, now);
    if (t == null) {
      t = envelope.observedTimestamp() != null ? envelope.observedTimestamp() : now;
    }
    return LocalDate.ofInstant(t, ZoneOffset.UTC);
  }

  /** The oldest day still kept; indices of earlier days are deleted. */
  public LocalDate oldestKept(Instant now) {
    return LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(retentionDays);
  }

  /** The day of one of this tier's index names; empty for any other name. */
  public Optional<LocalDate> dayOf(String indexName) {
    Matcher m = ownName.matcher(indexName);
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(LocalDate.parse(m.group(1), DAY));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * The indices a tenant's search over {@code [from, to)} reads: the shared ones for each day, and
   * a dedicated tenant's own (its days before it was dedicated stay in the shared indices).
   */
  public List<String> searchIndices(String tenantId, Instant from, Instant to) {
    LocalDate first = LocalDate.ofInstant(from, ZoneOffset.UTC);
    LocalDate last = LocalDate.ofInstant(to.minusNanos(1), ZoneOffset.UTC);
    List<String> names = new ArrayList<>();
    for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
      names.add(prefix + "-" + DAY.format(day) + "-" + SHARED);
      if (isDedicated(tenantId)) {
        names.add(prefix + "-" + DAY.format(day) + "-" + DEDICATED + tenantId);
      }
    }
    return names;
  }
}
