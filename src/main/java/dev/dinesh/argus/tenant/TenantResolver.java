package dev.dinesh.argus.tenant;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Port: which tenant a request acts for. Resolved from credentials, never from the request body
 * (guardrail 4). Controllers receive it as a {@link CurrentTenant} argument.
 */
public interface TenantResolver {

  /**
   * @throws UnauthenticatedException if the request carries no valid credential
   */
  String resolve(HttpServletRequest request);
}
