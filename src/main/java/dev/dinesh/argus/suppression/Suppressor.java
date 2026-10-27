package dev.dinesh.argus.suppression;

import java.util.regex.Pattern;

/**
 * Decides whether a message may be printed: only if the same tenant has not had the same message
 * allowed within the window. Uses ingest time, never the message's own timestamp (guardrail 8).
 */
public class Suppressor {

  /** Tenant ids start every store key (guardrail 4), so they may not contain the ':' separator. */
  private static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final SuppressionStore store;
  private final MessageFingerprint fingerprint;

  public Suppressor(SuppressionStore store, MessageFingerprint fingerprint) {
    this.store = store;
    this.fingerprint = fingerprint;
  }

  /**
   * @throws IllegalArgumentException for an invalid tenant id or a null message
   */
  public SuppressionDecision check(String tenantId, String message) {
    if (tenantId == null || !TENANT_ID.matcher(tenantId).matches()) {
      throw new IllegalArgumentException(
          "Invalid tenant id (allowed: 1-64 of A-Z a-z 0-9 . _ -): " + tenantId);
    }
    if (message == null) {
      throw new IllegalArgumentException("message is required");
    }
    return store.acquire(key(tenantId, message), message);
  }

  String key(String tenantId, String message) {
    return tenantId + ":supp:" + fingerprint.of(message);
  }
}
