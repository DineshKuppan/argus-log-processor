package dev.dinesh.argus.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.config.QuotaConfig;
import dev.dinesh.argus.config.QuotaProperties;
import dev.dinesh.argus.config.SuppressionConfig;
import dev.dinesh.argus.config.SuppressionProperties;
import dev.dinesh.argus.config.TenancyConfig;
import dev.dinesh.argus.ingest.IngestPipeline;
import dev.dinesh.argus.ingest.PushIngestService;
import dev.dinesh.argus.ingest.StreamRegistry;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.tenant.ApiKeyTenantResolver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.unit.DataSize;

/** The push API against the real push service and spool, with a mocked pipeline. */
@WebMvcTest(StreamController.class)
@Import({
  PushIngestService.class,
  StreamRegistry.class,
  ParserRegistry.class,
  TenancyConfig.class,
  QuotaConfig.class,
  SuppressionConfig.class,
  StreamControllerTest.Config.class
})
@EnableConfigurationProperties({SuppressionProperties.class, IngestProperties.class})
class StreamControllerTest {

  private static final Path SPOOL;

  static {
    try {
      SPOOL = Files.createTempDirectory("argus-push-api");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final String ACME_KEY = "acme-key";
  private static final String GLOBEX_KEY = "globex-key";

  @DynamicPropertySource
  static void apiKeys(DynamicPropertyRegistry registry) {
    registry.add("argus.tenancy.api-keys[0].tenant", () -> "acme");
    registry.add(
        "argus.tenancy.api-keys[0].key-sha256",
        () -> "afacab3575137afa4e00d9cbcafcb14c9ae25f779d964eb0ea5b2c4eb5dfd163");
    registry.add("argus.tenancy.api-keys[1].tenant", () -> "globex");
    registry.add(
        "argus.tenancy.api-keys[1].key-sha256",
        () -> "f2a455b59b858a04c51108416af2042203cb6ac35fa4141fba4148bc856e78d4");
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    PushProperties testPushProperties() {
      return new PushProperties(
          SPOOL, DataSize.ofKilobytes(64), 0, 0, null, null, null, 0, 0, 0, null);
    }

    @Bean
    @Primary
    QuotaProperties testQuotaProperties() {
      // globex may push 10 bytes per second, acme 1MB.
      return new QuotaProperties(
          null,
          DataSize.ofMegabytes(1),
          1.0,
          Map.of("globex", new QuotaProperties.Tenant(DataSize.ofBytes(10))));
    }
  }

  @Autowired MockMvc mvc;
  @MockBean IngestPipeline pipeline;
  @MockBean CheckpointStore checkpoints;

  @BeforeEach
  void noCheckpoints() throws IOException {
    when(checkpoints.load(any(), any())).thenReturn(Optional.empty());
  }

  private ResultActions as(String apiKey, MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request.header(ApiKeyTenantResolver.API_KEY_HEADER, apiKey));
  }

  private static MockHttpServletRequestBuilder pushTo(String stream, String body) {
    return post("/api/v2/ingest/streams/" + stream)
        .contentType("application/x-ndjson")
        .content(body);
  }

  @Test
  void aPushNeedsAnApiKey() throws Exception {
    mvc.perform(pushTo("web", "a\n")).andExpect(status().isUnauthorized());
  }

  @Test
  void acceptsLinesAndReportsWhereTheyLanded() throws Exception {
    as(ACME_KEY, pushTo("web", "{\"msg\":\"a\"}\n{\"msg\":\"b\"}\n"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.streamId").value("web"))
        .andExpect(jsonPath("$.accepted").value(2))
        .andExpect(jsonPath("$.spool").value(1))
        .andExpect(jsonPath("$.startOffset").value(0))
        .andExpect(jsonPath("$.endOffset").value(24))
        .andExpect(jsonPath("$.duplicate").value(false));

    as(ACME_KEY, get("/api/v2/ingest/streams/web"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptedLines").value(2))
        .andExpect(jsonPath("$.pendingBytes").value(24));
    as(ACME_KEY, get("/api/v2/ingest/streams"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.streamId == 'web')].acceptedLines").value(2));
  }

  @Test
  void anotherTenantsStreamIsNotFound() throws Exception {
    as(ACME_KEY, pushTo("private", "a\n")).andExpect(status().isAccepted());

    as(GLOBEX_KEY, get("/api/v2/ingest/streams/private")).andExpect(status().isNotFound());
    // The context is shared, so globex may have streams from other tests, but never this one.
    as(GLOBEX_KEY, get("/api/v2/ingest/streams"))
        .andExpect(jsonPath("$[?(@.streamId == 'private')]").isEmpty());
  }

  @Test
  void aRepeatedIdempotencyKeyIs200WithTheOriginalOffsets() throws Exception {
    as(ACME_KEY, pushTo("retry", "one\n").header("Idempotency-Key", "b-1"))
        .andExpect(status().isAccepted());
    as(ACME_KEY, pushTo("retry", "two\n")).andExpect(status().isAccepted());

    as(ACME_KEY, pushTo("retry", "one\n").header("Idempotency-Key", "b-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true))
        .andExpect(jsonPath("$.startOffset").value(0))
        .andExpect(jsonPath("$.endOffset").value(4));
  }

  @Test
  void gzipBodiesAreAccepted() throws Exception {
    ByteArrayOutputStream gz = new ByteArrayOutputStream();
    try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
      out.write("x\ny\n".getBytes(StandardCharsets.UTF_8));
    }

    as(
            ACME_KEY,
            post("/api/v2/ingest/streams/zipped")
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .content(gz.toByteArray()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(2));
  }

  @Test
  void eachBoundHasItsStatus() throws Exception {
    as(GLOBEX_KEY, pushTo("q", "123456789\n")).andExpect(status().isAccepted());
    as(GLOBEX_KEY, pushTo("q", "a\n"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
        .andExpect(jsonPath("$.reason").value("QUOTA"));

    as(ACME_KEY, pushTo("big", "x".repeat(70 * 1024)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
    as(ACME_KEY, pushTo("bad", "a\n").header(HttpHeaders.CONTENT_ENCODING, "br"))
        .andExpect(status().isUnsupportedMediaType());
    as(ACME_KEY, pushTo("bad..id/x", "a\n")).andExpect(status().isNotFound());
    as(ACME_KEY, pushTo("bad%20id", "a\n")).andExpect(status().isBadRequest());

    when(pipeline.walFull()).thenReturn(true);
    as(ACME_KEY, pushTo("web", "a\n"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"));
  }
}
