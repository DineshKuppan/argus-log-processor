package dev.dinesh.argus.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.TenancyConfig;
import dev.dinesh.argus.tenant.ApiKeyTenantResolver;
import dev.dinesh.argus.tenant.TenantRoots;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Without {@code argus.hot.store}, search says so. */
@WebMvcTest(SearchController.class)
@Import({TenancyConfig.class, TenantRoots.class})
@EnableConfigurationProperties(IngestProperties.class)
class SearchControllerWithoutHotTierTest {

  @DynamicPropertySource
  static void apiKeys(DynamicPropertyRegistry registry) {
    registry.add("argus.tenancy.api-keys[0].tenant", () -> "acme");
    registry.add(
        "argus.tenancy.api-keys[0].key-sha256",
        () -> "afacab3575137afa4e00d9cbcafcb14c9ae25f779d964eb0ea5b2c4eb5dfd163");
  }

  @Autowired MockMvc mvc;

  @Test
  void answers404() throws Exception {
    mvc.perform(
            get("/api/v2/search?from=2026-11-01T09:00:00Z&to=2026-11-01T10:00:00Z")
                .header(ApiKeyTenantResolver.API_KEY_HEADER, "acme-key"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString("argus.hot.store")));
  }
}
