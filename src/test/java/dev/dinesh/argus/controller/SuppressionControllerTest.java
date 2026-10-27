package dev.dinesh.argus.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.config.SuppressionConfig;
import dev.dinesh.argus.config.SuppressionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The suppression API against the real local store. */
@WebMvcTest(SuppressionController.class)
@Import(SuppressionConfig.class)
@EnableConfigurationProperties(SuppressionProperties.class)
class SuppressionControllerTest {

  @Autowired MockMvc mvc;

  private ResultActions check(String tenant, String body) throws Exception {
    var request =
        post("/api/v2/suppression/check").contentType(MediaType.APPLICATION_JSON).content(body);
    if (tenant != null) {
      request.header(SuppressionController.TENANT_HEADER, tenant);
    }
    return mvc.perform(request);
  }

  @Test
  void allowsTheFirstCopyAndDeniesTheNextWithACount() throws Exception {
    check("acme", "{\"message\":\"disk full\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("ALLOW"))
        .andExpect(jsonPath("$.suppressedCount").value(0))
        .andExpect(jsonPath("$.windowEndsAt").isString());
    check("acme", "{\"message\":\"disk full\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("DENY"))
        .andExpect(jsonPath("$.suppressedCount").value(1));
  }

  @Test
  void tenantsComeFromTheHeaderAndAreIsolated() throws Exception {
    check("globex", "{\"message\":\"shared text\"}")
        .andExpect(jsonPath("$.decision").value("ALLOW"));
    check(null, "{\"message\":\"shared text\"}").andExpect(jsonPath("$.decision").value("ALLOW"));
    check(null, "{\"message\":\"shared text\",\"tenantId\":\"globex\"}")
        .andExpect(jsonPath("$.decision").value("DENY"));
  }

  @Test
  void rejectsBadTenantsAndMissingMessages() throws Exception {
    check("a:b", "{\"message\":\"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").isString());
    check("acme", "{}").andExpect(status().isBadRequest());
    check("acme", "").andExpect(status().isBadRequest());
  }
}
