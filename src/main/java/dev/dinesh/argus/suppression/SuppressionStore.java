package dev.dinesh.argus.suppression;

/**
 * Holds one window per key: the first call for a key opens a window and is allowed; calls for the
 * same key until the window ends are denied and counted. Implementations must be thread-safe and
 * atomic (exactly one ALLOW among concurrent first calls), and must fail open (allow) when their
 * backing store is unavailable (guardrail 8).
 */
public interface SuppressionStore {

  /**
   * @param key tenant-prefixed message key (see {@link Suppressor})
   * @param message the original message, for summaries of suppressed windows
   */
  SuppressionDecision acquire(String key, String message);

  /**
   * Ends the key's window, so the next call for it is allowed: for a message that was allowed but
   * then not kept (its write failed), so a retry isn't suppressed as its copy. Best effort.
   */
  void release(String key);
}
