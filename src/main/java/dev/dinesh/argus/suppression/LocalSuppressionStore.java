package dev.dinesh.argus.suppression;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Suppression windows in a bounded Caffeine cache (one node). A window that denied at least one
 * copy is summarized when it ends, so no suppressed signal disappears silently. A full cache evicts
 * the least valuable windows early, which can only allow a duplicate, never drop a message.
 */
@Slf4j
public final class LocalSuppressionStore implements SuppressionStore {

  /** Long messages are cut in summaries; the window itself is keyed on the whole message. */
  static final int SUMMARY_MESSAGE_CHARS = 200;

  /** A window that ended with at least one denied copy. */
  public record Summary(String key, String message, long suppressedCount, Duration window) {}

  private static final class Window {
    final String message;
    final Instant endsAt;
    final AtomicLong denied = new AtomicLong();

    Window(String message, Instant endsAt) {
      this.message = message;
      this.endsAt = endsAt;
    }
  }

  private final Cache<String, Window> windows;
  private final Duration window;
  private final Clock clock;

  public LocalSuppressionStore(Duration window, long maxKeys) {
    this(
        window,
        maxKeys,
        Clock.systemUTC(),
        Ticker.systemTicker(),
        Scheduler.systemScheduler(),
        ForkJoinPool.commonPool(),
        LocalSuppressionStore::logSummary);
  }

  /**
   * @param ticker measures window age (monotonic); {@code clock} only dates {@code windowEndsAt}
   * @param scheduler evicts ended windows promptly so their summaries are not delayed until the
   *     next cache access
   */
  LocalSuppressionStore(
      Duration window,
      long maxKeys,
      Clock clock,
      Ticker ticker,
      Scheduler scheduler,
      Executor executor,
      Consumer<Summary> onSummary) {
    this.window = window;
    this.clock = clock;
    this.windows =
        Caffeine.newBuilder()
            .expireAfterWrite(window)
            .maximumSize(maxKeys)
            .ticker(ticker)
            .scheduler(scheduler)
            .executor(executor)
            .removalListener(
                (String key, Window w, RemovalCause cause) -> {
                  if (w != null && cause.wasEvicted() && w.denied.get() > 0) {
                    onSummary.accept(new Summary(key, w.message, w.denied.get(), window));
                  }
                })
            .build();
  }

  @Override
  public SuppressionDecision acquire(String key, String message) {
    Window fresh = new Window(abbreviate(message), clock.instant().plus(window));
    // Atomic: concurrent first calls for a key see exactly one null. An ended window that has not
    // been evicted yet counts as absent and is replaced.
    Window current = windows.asMap().putIfAbsent(key, fresh);
    if (current == null) {
      return SuppressionDecision.allow(fresh.endsAt);
    }
    return SuppressionDecision.deny(current.endsAt, current.denied.incrementAndGet());
  }

  /** Runs pending evictions now (and so pending summaries); for tests with a fake ticker. */
  void cleanUp() {
    windows.cleanUp();
  }

  long size() {
    windows.cleanUp();
    return windows.estimatedSize();
  }

  private static String abbreviate(String message) {
    return message.length() <= SUMMARY_MESSAGE_CHARS
        ? message
        : message.substring(0, SUMMARY_MESSAGE_CHARS) + "…";
  }

  private static void logSummary(Summary s) {
    log.info(
        "Suppressed key={} count={} window={}s message=\"{}\"",
        s.key(),
        s.suppressedCount(),
        s.window().toSeconds(),
        // Messages come from API callers: escape line breaks so one can't forge extra log lines.
        s.message().replace("\r", "\\r").replace("\n", "\\n"));
  }
}
