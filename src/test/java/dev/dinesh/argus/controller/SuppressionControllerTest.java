package dev.dinesh.argus.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.config.SuppressionConfig;
import dev.dinesh.argus.config.SuppressionProperties;
import dev.dinesh.argus.config.TenancyConfig;
import dev.dinesh.argus.tenant.ApiKeyTenantResolver;
import dev.dinesh.argus.tenant.HeaderTenantResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The suppression API against the real local store, with API-key tenancy. */
@WebMvcTest(
    controllers = SuppressionController.class,
    properties = {
      "argus.tenancy.api-keys[0].tenant=acme",
      // printf %s acme-key | shasum -a 256
      "argus.tenancy.api-keys[0].key-sha256=afacab3575137afa4e00d9cbcafcb14c9ae25f779d964eb0ea5b2c4eb5dfd163",
      "argus.tenancy.api-keys[1].tenant=globex",
      "argus.tenancy.api-keys[1].key-sha256=f2a455b59b858a04c51108416af2042203cb6ac35fa4141fba4148bc856e78d4"
    })
@Import({SuppressionConfig.class, TenancyConfig.class})
@EnableConfigurationProperties(SuppressionProperties.class)
class SuppressionControllerTest {

  @Autowired MockMvc mvc;

  private ResultActions check(String apiKey, String body) throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/v2/suppression/check").contentType(MediaType.APPLICATION_JSON).content(body);
    if (apiKey != null) {
      request.header(ApiKeyTenantResolver.API_KEY_HEADER, apiKey);
    }
    return mvc.perform(request);
  }

  @Test
  void allowsTheFirstCopyAndDeniesTheNextWithACount() throws Exception {
    check("acme-key", "{\"message\":\"disk full\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("ALLOW"))
        .andExpect(jsonPath("$.suppressedCount").value(0))
        .andExpect(jsonPath("$.windowEndsAt").isString());
    check("acme-key", "{\"message\":\"disk full\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("DENY"))
        .andExpect(jsonPath("$.suppressedCount").value(1));
  }

  @Test
  void theTenantComesFromTheKeyNotTheHeaderOrBody() throws Exception {
    check("globex-key", "{\"message\":\"shared text\"}")
        .andExpect(jsonPath("$.decision").value("ALLOW"));
    // acme's own window: the globex header and body field change nothing.
    mvc.perform(
            post("/api/v2/suppression/check")
                .contentType(MediaType.APPLICATION_JSON)
                .header(ApiKeyTenantResolver.API_KEY_HEADER, "acme-key")
                .header(HeaderTenantResolver.TENANT_HEADER, "globex")
                .content("{\"message\":\"shared text\",\"tenantId\":\"globex\"}"))
        .andExpect(jsonPath("$.decision").value("ALLOW"));
  }

  @Test
  void requestsWithoutAValidKeyAreUnauthorized() throws Exception {
    check(null, "{\"message\":\"x\"}")
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "ApiKey realm=\"argus\""))
        .andExpect(jsonPath("$.error").isString());
    check("not-a-key", "{\"message\":\"x\"}").andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsMissingMessages() throws Exception {
    check("acme-key", "{}").andExpect(status().isBadRequest());
    check("acme-key", "").andExpect(status().isBadRequest());
  }
}
