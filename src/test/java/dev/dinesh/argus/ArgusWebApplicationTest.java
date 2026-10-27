package dev.dinesh.argus;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.controller.IngestController;
import dev.dinesh.argus.ingest.IngestJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Starts the full application context so wiring and property binding errors fail the build. */
@SpringBootTest
@AutoConfigureMockMvc
class ArgusWebApplicationTest {

  @Autowired IngestController ingestController;
  @Autowired IngestJobService ingestJobService;
  @Autowired IngestProperties ingestProperties;
  @Autowired MockMvc mvc;

  @Test
  void contextLoadsAndBindsIngestProperties() {
    assertEquals(10_000, ingestProperties.batchMaxLines());
    assertEquals(500L * 1024 * 1024, ingestProperties.segmentBytes().toBytes());
    assertEquals(2, ingestProperties.maxConcurrentJobs());
  }

  /** The shipped config has no keys: every API (v1 included) answers 401, nothing is open. */
  @Test
  void everyApiNeedsAKeyByDefault() throws Exception {
    mvc.perform(get("/api/v1/logs/parse").param("file", "src/main/resources/logs/app.json"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v2/ingest/jobs/nope")).andExpect(status().isUnauthorized());
  }

  @Test
  void apiDocsDeclareTheKeyAndHideTheTenantParameter() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-Argus-Api-Key"))
        .andExpect(content().string(not(containsString("\"tenantId\",\"in\""))));
  }
}
