package dev.dinesh.argus.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.TenancyConfig;
import dev.dinesh.argus.export.ExportJob;
import dev.dinesh.argus.export.ExportJobService;
import dev.dinesh.argus.export.ExportJobService.ExportBusyException;
import dev.dinesh.argus.export.ExportJobService.ExportRequest;
import dev.dinesh.argus.export.ExportJobService.ExportSourceNotFoundException;
import dev.dinesh.argus.export.ExportJobs;
import dev.dinesh.argus.ingest.IngestJobService.JobSlotsBusyException;
import dev.dinesh.argus.ingest.Lane;
import dev.dinesh.argus.tenant.ApiKeyTenantResolver;
import dev.dinesh.argus.tenant.TenantRoots;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The export API's statuses, with the service mocked. */
@WebMvcTest(ExportController.class)
@Import({TenancyConfig.class, TenantRoots.class})
@EnableConfigurationProperties(IngestProperties.class)
class ExportControllerTest {

  @DynamicPropertySource
  static void apiKeys(DynamicPropertyRegistry registry) {
    registry.add("argus.tenancy.api-keys[0].tenant", () -> "acme");
    // printf %s acme-key | shasum -a 256
    registry.add(
        "argus.tenancy.api-keys[0].key-sha256",
        () -> "afacab3575137afa4e00d9cbcafcb14c9ae25f779d964eb0ea5b2c4eb5dfd163");
  }

  private static final String BODY =
      "{\"source\":\"elk\",\"streamId\":\"elk-2026\",\"query\":{\"match\":{\"service\":\"api\"}},"
          + "\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}";

  @Autowired MockMvc mvc;
  @MockBean ExportJobService exports;

  private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header(ApiKeyTenantResolver.API_KEY_HEADER, "acme-key"));
  }

  private ResultActions start(String body) throws Exception {
    return perform(
        post("/api/v2/ingest/exports").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void startsAnExportForTheCallersTenant() throws Exception {
    ExportJob job = ExportJobs.queued("acme", "elk", "elk-2026");
    when(exports.submit(eq("acme"), any())).thenReturn(job);

    start(BODY)
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/v2/ingest/exports/job-1"))
        .andExpect(jsonPath("$.lane").value("REPLAY"))
        .andExpect(jsonPath("$.state").value("QUEUED"))
        .andExpect(jsonPath("$.from").value("2026-01-01T00:00:00Z"));

    ArgumentCaptor<ExportRequest> request = ArgumentCaptor.forClass(ExportRequest.class);
    verify(exports).submit(eq("acme"), request.capture());
    assertEquals(Instant.parse("2026-01-02T00:00:00Z"), request.getValue().to());
    assertEquals("api", request.getValue().query().path("match").path("service").asText());
  }

  @Test
  void statusAndCancel() throws Exception {
    ExportJob job = ExportJobs.queued("acme", "elk", "elk-2026");
    when(exports.find("acme", "job-1")).thenReturn(Optional.of(job));
    when(exports.cancel("acme", "job-1")).thenReturn(Optional.of(job));

    perform(get("/api/v2/ingest/exports/job-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.streamId").value("elk-2026"));
    perform(delete("/api/v2/ingest/exports/job-1")).andExpect(status().isAccepted());
    perform(get("/api/v2/ingest/exports/other")).andExpect(status().isNotFound());
  }

  @Test
  void errorsMapToStatuses() throws Exception {
    when(exports.submit(eq("acme"), any()))
        .thenThrow(new IllegalArgumentException("from must be before to"));
    start(BODY).andExpect(status().isBadRequest());

    when(exports.submit(eq("acme"), any())).thenThrow(new JobSlotsBusyException(Lane.REPLAY, 1));
    start(BODY)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.error").value(containsString("replay job slots")));

    start("{\"from\":\"yesterday\"}").andExpect(status().isBadRequest());

    when(exports.submit(eq("acme"), any())).thenThrow(new ExportSourceNotFoundException("elk"));
    start(BODY).andExpect(status().isNotFound());

    when(exports.submit(eq("acme"), any())).thenThrow(new ExportBusyException("elk-2026", "j"));
    start(BODY).andExpect(status().isConflict());
  }

  @Test
  void requestsWithoutAValidKeyAre401() throws Exception {
    mvc.perform(
            post("/api/v2/ingest/exports").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }
}
