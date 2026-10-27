package dev.dinesh.argus.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.ingest.IngestJobService;
import dev.dinesh.argus.ingest.IngestPipeline;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.parser.ParserRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Exercises the v2 ingest API against the real job service with a mocked pipeline. */
@WebMvcTest(IngestController.class)
@Import({IngestJobService.class, ParserRegistry.class, IngestControllerTest.Config.class})
class IngestControllerTest {

  private static final Path ROOT;

  static {
    try {
      ROOT = Files.createTempDirectory("argus-ingest-api").toRealPath();
      Files.writeString(ROOT.resolve("app.log"), "line\n");
      Files.writeString(ROOT.resolve("other.log"), "line\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    IngestProperties testIngestProperties() {
      // One job slot so the 429 path can be exercised.
      return new IngestProperties(
          0, null, null, 0, 0, null, 1, List.of(ROOT), ROOT.resolve("cp"), null);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired IngestJobService service;
  @MockBean IngestPipeline pipeline;

  private CountDownLatch release;

  @BeforeEach
  void pipelineBlocksUntilReleased() throws Exception {
    release = new CountDownLatch(1);
    CountDownLatch latch = release;
    when(pipeline.run(any(), any(), any()))
        .thenAnswer(
            inv -> {
              latch.await(10, TimeUnit.SECONDS);
              return Outcome.COMPLETED;
            });
  }

  @AfterEach
  void releaseAndWaitForTheSlot() throws InterruptedException {
    release.countDown();
    // The context (and its single job slot) is shared across tests; wait until it is free.
    for (int i = 0; i < 250 && service.activeJobs() > 0; i++) {
      Thread.sleep(20);
    }
  }

  private ResultActions submit(Map<String, String> body) throws Exception {
    return mvc.perform(
        post("/api/v2/ingest/file")
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body)));
  }

  @Test
  void acceptsAFileUnderAnAllowedRoot() throws Exception {
    submit(Map.of("path", ROOT.resolve("app.log").toString(), "sourceId", "api-app"))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", startsWith("/api/v2/ingest/jobs/")))
        .andExpect(jsonPath("$.sourceId").value("api-app"))
        .andExpect(jsonPath("$.jobId").isNotEmpty());
  }

  @Test
  void returnsJobStatusAndCancels() throws Exception {
    String body =
        submit(Map.of("path", ROOT.resolve("app.log").toString(), "sourceId", "api-status"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jobId = mapper.readTree(body).get("jobId").asText();

    mvc.perform(get("/api/v2/ingest/jobs/" + jobId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(jobId))
        .andExpect(jsonPath("$.state").exists())
        .andExpect(jsonPath("$.percent").exists());
    mvc.perform(delete("/api/v2/ingest/jobs/" + jobId)).andExpect(status().isAccepted());
  }

  @Test
  void unknownJobIs404() throws Exception {
    mvc.perform(get("/api/v2/ingest/jobs/nope")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v2/ingest/jobs/nope")).andExpect(status().isNotFound());
  }

  @Test
  void pathOutsideAllowedRootsIs400AndMissingFileIs404() throws Exception {
    submit(Map.of("path", "/etc/hosts"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
    submit(Map.of("path", ROOT.resolve("missing.log").toString())).andExpect(status().isNotFound());
  }

  @Test
  void outsideTheRootsAMissingFileLooksTheSameAsAnExistingOne() throws Exception {
    String existing =
        submit(Map.of("path", "/etc/hosts")).andReturn().getResponse().getContentAsString();
    String missing =
        submit(Map.of("path", "/etc/argus-no-such-file"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String traversal =
        submit(Map.of("path", ROOT.resolve("../argus-no-such-file").toString()))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertEquals(existing, missing);
    assertEquals(existing, traversal);
  }

  @Test
  void unknownFormatsAndUnsupportedLayoutsAre400() throws Exception {
    String app = ROOT.resolve("app.log").toString();
    submit(Map.of("path", app, "format", "xml"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("nginx_access")));
    submit(Map.of("path", app, "pattern", "%replace(%m){'a','b'}"))
        .andExpect(status().isBadRequest());
    submit(Map.of("path", app, "format", "logback")).andExpect(status().isBadRequest());
  }

  @Test
  void requestedFormatIsAccepted() throws Exception {
    submit(
            Map.of(
                "path",
                ROOT.resolve("app.log").toString(),
                "sourceId",
                "api-format",
                "pattern",
                "%d [%t] %-5p %c - %m%n"))
        .andExpect(status().isAccepted());
  }

  @Test
  void busySlotsReturn429WithRetryAfter() throws Exception {
    submit(Map.of("path", ROOT.resolve("app.log").toString(), "sourceId", "api-busy-1"))
        .andExpect(status().isAccepted());

    submit(Map.of("path", ROOT.resolve("other.log").toString(), "sourceId", "api-busy-2"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", IngestController.RETRY_AFTER_SECONDS));
  }
}
