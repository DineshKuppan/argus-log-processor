package dev.dinesh.argus.tenant;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Fills {@link CurrentTenant} parameters with the tenant {@link TenantInterceptor} resolved. */
public final class CurrentTenantArgumentResolver implements HandlerMethodArgumentResolver {

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
    Object tenant =
        webRequest.getAttribute(
            TenantInterceptor.TENANT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    if (tenant == null) {
      // Fail closed: the handler is outside the interceptor's paths.
      throw new IllegalStateException(
          "@CurrentTenant used on a path TenantInterceptor does not cover: "
              + parameter.getExecutable());
    }
    return (String) tenant;
  }
}
