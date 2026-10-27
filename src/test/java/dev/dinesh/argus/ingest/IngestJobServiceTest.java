package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.ingest.IngestJob.State;
import dev.dinesh.argus.ingest.IngestJobService.JobSlotsBusyException;
import dev.dinesh.argus.ingest.IngestJobService.SourceBusyException;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.parser.ParserRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestJobServiceTest {

  @TempDir Path dir;

  private Path root;
  private Path file;
  private final IngestPipeline pipeline = mock(IngestPipeline.class);
  private final CountDownLatch release = new CountDownLatch(1);
  private IngestJobService service;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(dir.resolve("allowed"));
    file = Files.writeString(root.resolve("app.log"), "line\n");
    IngestProperties props =
        new IngestProperties(0, null, null, 0, 0, null, 1, List.of(root), dir.resolve("cp"), null);
    service = new IngestJobService(pipeline, new ParserRegistry(), props);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    release.countDown();
    service.shutdown();
  }

  private void pipelineBlocksUntilReleased() throws Exception {
    when(pipeline.run(any(), any(), any()))
        .thenAnswer(
            inv -> {
              release.await(10, TimeUnit.SECONDS);
              return Outcome.COMPLETED;
            });
  }

  /** The slot frees a moment after the job reports completion, as a 429-retrying client sees. */
  private IngestJob submitRetryingWhileSlotsBusy(Path path, String sourceId) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (true) {
      try {
        return service.submit("default", path.toString(), sourceId, false, null, null);
      } catch (JobSlotsBusyException e) {
        if (Instant.now().isAfter(deadline)) {
          throw e;
        }
        Thread.sleep(10);
      }
    }
  }

  private static void awaitState(IngestJob job, State state) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (job.state() != state && Instant.now().isBefore(deadline)) {
      Thread.sleep(10);
    }
    assertEquals(state, job.state());
  }

  @Test
  void runsJobToCompletion() throws Exception {
    when(pipeline.run(any(), any(), any())).thenReturn(Outcome.COMPLETED);

    IngestJob job = service.submit("default", file.toString(), "app", false, null, null);

    awaitState(job, State.COMPLETED);
    assertEquals(file.toRealPath(), job.path());
  }

  @Test
  void recordsPipelineFailureOnTheJob() throws Exception {
    when(pipeline.run(any(), any(), any())).thenThrow(new IOException("disk full"));

    IngestJob job = service.submit("default", file.toString(), "app", false, null, null);

    awaitState(job, State.FAILED);
    assertEquals("IOException: disk full", job.error());
  }

  @Test
  void rejectsPathsOutsideTheAllowedRoots() throws IOException {
    Path outside = Files.writeString(dir.resolve("secret.txt"), "x");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.submit("default", outside.toString(), null, false, null, null));
  }

  @Test
  void rejectsSymlinkInsideARootThatPointsOutsideIt() throws IOException {
    Path outside = Files.writeString(dir.resolve("secret.txt"), "x");
    Path link = Files.createSymbolicLink(root.resolve("link.log"), outside);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.submit("default", link.toString(), null, false, null, null));
  }

  @Test
  void rejectsMissingFilesDirectoriesAndInvalidSourceIds() throws IOException {
    Path subdir = Files.createDirectories(root.resolve("sub"));
    assertThrows(
        NoSuchFileException.class,
        () ->
            service.submit(
                "default", root.resolve("missing.log").toString(), null, false, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.submit("default", subdir.toString(), null, false, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.submit("default", file.toString(), "../x", false, null, null));
  }

  @Test
  void defaultSourceIdIsStableForTheSameFile() throws Exception {
    when(pipeline.run(any(), any(), any())).thenReturn(Outcome.COMPLETED);

    IngestJob first = service.submit("default", file.toString(), null, false, null, null);
    awaitState(first, State.COMPLETED);
    IngestJob second = submitRetryingWhileSlotsBusy(root.resolve("./app.log"), null);

    assertEquals(first.sourceId(), second.sourceId());
    assertTrue(first.sourceId().matches("file-[0-9a-f]{12}"));
  }

  @Test
  void rejectsASecondJobForABusySource() throws Exception {
    pipelineBlocksUntilReleased();
    service.submit("default", file.toString(), "app", false, null, null);

    assertThrows(
        SourceBusyException.class,
        () -> service.submit("default", file.toString(), "app", false, null, null));
  }

  @Test
  void rejectsJobsWhenAllSlotsAreBusy() throws Exception {
    pipelineBlocksUntilReleased();
    Path other = Files.writeString(root.resolve("other.log"), "line\n");
    IngestJob running = service.submit("default", file.toString(), "app", false, null, null);

    assertThrows(
        JobSlotsBusyException.class,
        () -> service.submit("default", other.toString(), "other", false, null, null));

    // A rejected submission must not leave its source marked busy.
    release.countDown();
    awaitState(running, State.COMPLETED);
    IngestJob retried = submitRetryingWhileSlotsBusy(other, "other");
    awaitState(retried, State.COMPLETED);
  }

  @Test
  void cancelSetsTheFlagSeenByThePipeline() throws Exception {
    pipelineBlocksUntilReleased();
    IngestJob job = service.submit("default", file.toString(), "app", false, null, null);

    service.cancel("default", job.id());

    assertTrue(job.cancelFlag().get());
  }
}
