package dev.dinesh.argus.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.SearchProperties;
import dev.dinesh.argus.config.TenancyConfig;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.search.SearchException;
import dev.dinesh.argus.search.SearchHit;
import dev.dinesh.argus.search.SearchQuery;
import dev.dinesh.argus.search.SearchResult;
import dev.dinesh.argus.search.SearchService;
import dev.dinesh.argus.tenant.ApiKeyTenantResolver;
import dev.dinesh.argus.tenant.TenantRoots;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The search API's statuses, with the service mocked. */
@WebMvcTest(SearchController.class)
@Import({TenancyConfig.class, TenantRoots.class})
@EnableConfigurationProperties(IngestProperties.class)
class SearchControllerTest {

  @DynamicPropertySource
  static void apiKeys(DynamicPropertyRegistry registry) {
    registry.add("argus.tenancy.api-keys[0].tenant", () -> "acme");
    // printf %s acme-key | shasum -a 256
    registry.add(
        "argus.tenancy.api-keys[0].key-sha256",
        () -> "afacab3575137afa4e00d9cbcafcb14c9ae25f779d964eb0ea5b2c4eb5dfd163");
  }

  private static final String RANGE = "from=2026-11-01T09:00:00Z&to=2026-11-01T10:00:00Z";

  @Autowired MockMvc mvc;
  @MockBean SearchService search;

  @BeforeEach
  void hotWindow() {
    when(search.oldest()).thenReturn(Instant.parse("2026-10-18T00:00:00Z"));
    when(search.props()).thenReturn(SearchProperties.defaults());
  }

  private ResultActions search(String query) throws Exception {
    return mvc.perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v2/search?" + query)
            .header(ApiKeyTenantResolver.API_KEY_HEADER, "acme-key"));
  }

  @Test
  void searchesForTheCallersTenantOnly() throws Exception {
    SearchHit hit =
        new SearchHit(
            "ev-1",
            Instant.parse("2026-11-01T09:30:00Z"),
            Instant.parse("2026-11-01T09:30:01Z"),
            "ERROR",
            17,
            "disk full",
            null,
            null,
            null,
            Map.of("http_status", 507),
            new LogEnvelope.Source("app", 0, 0, "json"));
    when(search.search(any(), isNull()))
        .thenReturn(new SearchResult(List.of(hit), "c2", false, 12));

    search(RANGE + "&q=disk&level=error&attr=http_status:507&tenant=globex")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hits[0].eventId").value("ev-1"))
        .andExpect(jsonPath("$.hits[0].attributes.http_status").value(507))
        .andExpect(jsonPath("$.hits[0].tenantId").doesNotExist())
        .andExpect(jsonPath("$.next").value("c2"));

    ArgumentCaptor<SearchQuery> query = ArgumentCaptor.forClass(SearchQuery.class);
    verify(search).search(query.capture(), isNull());
    assertEquals("acme", query.getValue().tenantId(), "from the key, not ?tenant=");
    assertEquals(17, query.getValue().minSeverity());
    assertEquals(
        List.of(new SearchQuery.Attribute("http_status", "507")), query.getValue().attributes());
  }

  @Test
  void needsAnApiKey() throws Exception {
    mvc.perform(get("/api/v2/search?" + RANGE)).andExpect(status().isUnauthorized());
    verify(search, never()).search(any(), any());
  }

  @Test
  void rejectsRangesAndParametersOutOfBounds() throws Exception {
    for (String bad :
        List.of(
            "to=2026-11-01T10:00:00Z",
            "from=2026-11-01T10:00:00Z&to=2026-11-01T09:00:00Z",
            "from=yesterday&to=2026-11-01T10:00:00Z",
            "from=2026-10-18T00:00:00Z&to=2026-11-02T00:00:00Z",
            "from=2026-10-17T23:00:00Z&to=2026-10-18T01:00:00Z",
            RANGE + "&level=loud",
            RANGE + "&size=501",
            RANGE + "&size=0",
            RANGE + "&attr=no-colon",
            RANGE + "&attr=bad key:1",
            RANGE + "&source=../etc",
            RANGE + "&q=" + "x".repeat(1025))) {
      search(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    }
    verify(search, never()).search(any(), any());
  }

  @Test
  void tooOldSaysWhereTheLinesAre() throws Exception {
    search("from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("archive")));
  }

  @Test
  void busyAndUnavailableCarryRetryAfter() throws Exception {
    when(search.search(any(), any()))
        .thenThrow(new SearchException(429, 1, "busy"))
        .thenThrow(new SearchException(503, 5, "down"))
        .thenThrow(new SearchException(502, 0, "too large"))
        .thenThrow(new IllegalArgumentException("cursor belongs to another search"));

    search(RANGE)
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "1"));
    search(RANGE)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"));
    search(RANGE)
        .andExpect(status().isBadGateway())
        .andExpect(header().doesNotExist("Retry-After"));
    search(RANGE + "&cursor=abc").andExpect(status().isBadRequest());
  }
}
