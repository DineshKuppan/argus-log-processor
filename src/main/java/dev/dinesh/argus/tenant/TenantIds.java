package dev.dinesh.argus.tenant;

import java.util.regex.Pattern;

/**
 * Tenant ids start every key, path and object prefix (guardrail 4), so they are restricted to
 * characters that are safe in all of them: no ':' (key separator), '/' or '..' (paths).
 */
public final class TenantIds {

  private static final Pattern TENANT_ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");

  private TenantIds() {}

  /**
   * @throws IllegalArgumentException if {@code tenantId} is not a valid tenant id
   */
  public static String requireValid(String tenantId) {
    if (tenantId == null || !TENANT_ID.matcher(tenantId).matches()) {
      throw new IllegalArgumentException(
          "Invalid tenant id (allowed: 1-64 of A-Z a-z 0-9 . _ -, not starting with '.'): "
              + tenantId);
    }
    return tenantId;
  }

  /**
   * The directory (or object-key) segment that starts every tenant's checkpoints and output: one
   * definition, so sinks and checkpoint stores always agree on it.
   */
  public static String pathSegment(String tenantId) {
    return "tenant=" + requireValid(tenantId);
  }
}
