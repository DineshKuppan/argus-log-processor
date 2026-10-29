package dev.dinesh.argus.suppression;

import dev.dinesh.argus.tenant.TenantIds;
import java.util.Collection;

/**
 * Decides whether a message may be printed: only if the same tenant has not had the same message
 * allowed within the window. Uses ingest time, never the message's own timestamp (guardrail 8).
 */
public class Suppressor {

  /**
   * Each check hashes the whole message (and template mode scans it three times), so its size is
   * capped to keep a decision well under a millisecond.
   */
  public static final int MAX_MESSAGE_CHARS = 64 * 1024;

  private final SuppressionStore store;
  private final MessageFingerprint fingerprint;

  public Suppressor(SuppressionStore store, MessageFingerprint fingerprint) {
    this.store = store;
    this.fingerprint = fingerprint;
  }

  /**
   * @throws IllegalArgumentException for an invalid tenant id, or a null or too long message
   */
  public SuppressionDecision check(String tenantId, String message) {
    return check(tenantId, message, null);
  }

  /**
   * Like {@link #check(String, String)}, and when the message is allowed adds its window's key to
   * {@code allowedKeys}, so the window can be given back with {@link #release} if the message isn't
   * kept after all.
   */
  public SuppressionDecision check(
      String tenantId, String message, Collection<String> allowedKeys) {
    TenantIds.requireValid(tenantId);
    if (message == null) {
      throw new IllegalArgumentException("message is required");
    }
    if (message.length() > MAX_MESSAGE_CHARS) {
      throw new IllegalArgumentException(
          "message is longer than " + MAX_MESSAGE_CHARS + " characters: " + message.length());
    }
    String key = key(tenantId, message);
    SuppressionDecision decision = store.acquire(key, message);
    if (allowedKeys != null && decision.allowed()) {
      allowedKeys.add(key);
    }
    return decision;
  }

  /**
   * Ends the windows of messages that were allowed but not kept (their write failed), so a retry of
   * them is allowed rather than suppressed as a copy of what was lost.
   *
   * @param keys collected by {@link #check(String, String, Collection)}
   */
  public void release(Collection<String> keys) {
    keys.forEach(store::release);
  }

  String key(String tenantId, String message) {
    return tenantId + ":supp:" + fingerprint.of(message);
  }
}
