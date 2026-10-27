package dev.dinesh.argus.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dinesh.argus.tenant.ApiKeyTenantResolver.ApiKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiKeyTenantResolverTest {

  private final ApiKeyTenantResolver resolver =
      new ApiKeyTenantResolver(
          List.of(
              new ApiKey("acme", ApiKeyTenantResolver.sha256Hex("acme-key")),
              new ApiKey("globex", ApiKeyTenantResolver.sha256Hex("globex-key").toUpperCase())));

  private static MockHttpServletRequest request(String header, String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (header != null) {
      request.addHeader(header, value);
    }
    return request;
  }

  @Test
  void resolvesTheTenantFromEitherHeader() {
    assertEquals(
        "acme", resolver.resolve(request(ApiKeyTenantResolver.API_KEY_HEADER, "acme-key")));
    assertEquals("globex", resolver.resolve(request("Authorization", "Bearer globex-key")));
    assertEquals("globex", resolver.resolve(request("Authorization", "bearer  globex-key ")));
  }

  @Test
  void missingOrUnknownKeysAreUnauthenticated() {
    assertThrows(UnauthenticatedException.class, () -> resolver.resolve(request(null, null)));
    assertThrows(
        UnauthenticatedException.class,
        () -> resolver.resolve(request(ApiKeyTenantResolver.API_KEY_HEADER, "wrong")));
    assertThrows(
        UnauthenticatedException.class,
        () -> resolver.resolve(request("Authorization", "Basic YWNtZTprZXk=")));
    // The tenant header carries no authority in API-key mode.
    assertThrows(
        UnauthenticatedException.class,
        () -> resolver.resolve(request(HeaderTenantResolver.TENANT_HEADER, "acme")));
  }

  @Test
  void rejectsBadConfigurationAtStartup() {
    String hash = ApiKeyTenantResolver.sha256Hex("k");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApiKeyTenantResolver(List.of(new ApiKey("a:b", hash))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApiKeyTenantResolver(List.of(new ApiKey("acme", "plaintext-key"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApiKeyTenantResolver(List.of(new ApiKey("a", hash), new ApiKey("b", hash))));
  }

  @Test
  void devModeUsesTheHeaderOrDefault() {
    HeaderTenantResolver dev = new HeaderTenantResolver();

    assertEquals("default", dev.resolve(request(null, null)));
    assertEquals("acme", dev.resolve(request(HeaderTenantResolver.TENANT_HEADER, "acme")));
    assertThrows(
        IllegalArgumentException.class,
        () -> dev.resolve(request(HeaderTenantResolver.TENANT_HEADER, "../etc")));
  }
}
