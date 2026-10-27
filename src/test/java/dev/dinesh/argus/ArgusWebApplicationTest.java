package dev.dinesh.argus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.controller.IngestController;
import dev.dinesh.argus.ingest.IngestJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Starts the full application context so wiring and property binding errors fail the build. */
@SpringBootTest
class ArgusWebApplicationTest {

  @Autowired IngestController ingestController;
  @Autowired IngestJobService ingestJobService;
  @Autowired IngestProperties ingestProperties;

  @Test
  void contextLoadsAndBindsIngestProperties() {
    assertEquals(10_000, ingestProperties.batchMaxLines());
    assertEquals(500L * 1024 * 1024, ingestProperties.segmentBytes().toBytes());
    assertEquals(2, ingestProperties.maxConcurrentJobs());
  }
}
