package dev.dinesh.argus.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Fills {@link CurrentTenant} parameters from the configured {@link TenantResolver}. */
public final class CurrentTenantArgumentResolver implements HandlerMethodArgumentResolver {

  private final TenantResolver tenants;

  public CurrentTenantArgumentResolver(TenantResolver tenants) {
    this.tenants = tenants;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(CurrentTenant.class)
        && parameter.getParameterType() == String.class;
  }

  @Override
  public String resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    return tenants.resolve(webRequest.getNativeRequest(HttpServletRequest.class));
  }
}
