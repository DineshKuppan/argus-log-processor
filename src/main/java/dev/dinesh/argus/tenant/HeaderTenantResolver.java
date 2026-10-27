package dev.dinesh.argus.tenant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Local development only: trusts a caller-chosen {@code X-Argus-Tenant} header (default {@code
 * default}). Any caller can act as any tenant, so this breaks guardrail 4 by design; production
 * uses {@link ApiKeyTenantResolver}.
 */
public final class HeaderTenantResolver implements TenantResolver {

  public static final String TENANT_HEADER = "X-Argus-Tenant";
  public static final String DEFAULT_TENANT = "default";

  @Override
  public String resolve(HttpServletRequest request) {
    String tenant = request.getHeader(TENANT_HEADER);
    return TenantIds.requireValid(tenant == null ? DEFAULT_TENANT : tenant);
  }
}
