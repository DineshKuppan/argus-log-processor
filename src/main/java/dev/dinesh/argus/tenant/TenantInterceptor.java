package dev.dinesh.argus.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the tenant of every API request before it reaches a controller, so an endpoint that
 * forgets {@link CurrentTenant} is still authenticated (fails closed, not open).
 */
public final class TenantInterceptor implements HandlerInterceptor {

  static final String TENANT_ATTRIBUTE = TenantInterceptor.class.getName() + ".tenant";

  private final TenantResolver tenants;

  public TenantInterceptor(TenantResolver tenants) {
    this.tenants = tenants;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    request.setAttribute(TENANT_ATTRIBUTE, tenants.resolve(request));
    return true;
  }
}
