package dev.dinesh.argus.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.config.TenancyConfig;
import dev.dinesh.argus.service.LogService;
import dev.dinesh.argus.tenant.HeaderTenantResolver;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Error mapping of the v1 API. */
@WebMvcTest(controllers = LogController.class, properties = "argus.tenancy.mode=header")
@Import(TenancyConfig.class)
class LogControllerTest {

  @Autowired MockMvc mvc;
  @MockBean LogService service;

  private ResultActions aggregate(String file) throws Exception {
    return mvc.perform(
        post("/api/v1/logs/aggregateV2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"file\":\"" + file + "\"}"));
  }

  @Test
  void busySlotsAnswer429WithRetryAfter() throws Exception {
    when(service.aggregate(eq("default"), eq("busy.log"), any(), anyInt()))
        .thenThrow(new LogService.AggregationSlotsBusyException(2));

    aggregate("busy.log")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", LogController.RETRY_AFTER_SECONDS))
        .andExpect(jsonPath("$.error").isString());
  }

  @Test
  void badInputIs400AndAMissingFileIs404() throws Exception {
    when(service.aggregate(eq("default"), eq("bad.log"), any(), anyInt()))
        .thenThrow(new IllegalArgumentException("topN must not be negative: -1"));
    when(service.aggregate(eq("default"), eq("missing.log"), any(), anyInt()))
        .thenThrow(new NoSuchFileException("missing.log"));

    aggregate("bad.log").andExpect(status().isBadRequest());
    aggregate("missing.log").andExpect(status().isNotFound());
  }

  @Test
  void theTenantReachesTheService() throws Exception {
    when(service.aggregate(eq("acme"), eq("acme.log"), any(), anyInt()))
        .thenThrow(new IllegalArgumentException("reached"));

    mvc.perform(
            post("/api/v1/logs/aggregateV2")
                .header(HeaderTenantResolver.TENANT_HEADER, "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\":\"acme.log\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("reached"));
  }
}
