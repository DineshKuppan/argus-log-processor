package dev.dinesh.argus.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.config.ReplayProperties;
import dev.dinesh.argus.export.ExportJobService.ExportBusyException;
import dev.dinesh.argus.export.ExportJobService.ExportRequest;
import dev.dinesh.argus.export.ExportJobService.ExportSourceNotFoundException;
import dev.dinesh.argus.ingest.IngestJob.State;
import dev.dinesh.argus.ingest.IngestJobService.JobSlotsBusyException;
import dev.dinesh.argus.ingest.IngestJobService.WalFullException;
import dev.dinesh.argus.ingest.IngestPipeline;
import dev.dinesh.argus.ingest.ReplayLane;
import dev.dinesh.argus.ingest.SourceLeases;
import dev.dinesh.argus.ingest.StreamRegistry;
import dev.dinesh.argus.parser.ParserRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** Export jobs against an in-memory source, with the real spool, registry and cursor store. */
class ExportJobServiceTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");

  @TempDir Path dir;

  private final IngestPipeline pipeline = mock(IngestPipeline.class);
  private final List<String> failingSaves = new ArrayList<>();
  private StreamRegistry registry;
  private ReplayLane lane;
  private ExportJobService service;
  private FakeSource source;
  private PushProperties push;
  private IngestProperties ingest;
  private ExportCursorStore cursors;

  @BeforeEach
  void setUp() throws IOException {
    push = new PushProperties(dir.resolve("spool"), null, 0, 0, null, null, null, 0, 0, 0, null);
    ingest = new IngestProperties(0, null, null, 0, 0, null, 0, List.of(), dir.resolve("cp"), null);
    registry =
        new StreamRegistry(push, new FileCheckpointStore(ingest, MAPPER), new SourceLeases());
    source = new FakeSource();
    start(new ReplayProperties(1, DataSize.ofGigabytes(1), null));
  }

  /** A crash between a page's append and its cursor: the save fails, the job stops there. */
  private void start(ReplayProperties replay) {
    lane = new ReplayLane(replay);
    cursors =
        new ExportCursorStore(dir.resolve("cp"), MAPPER) {
          @Override
          public void save(String tenantId, String streamId, Saved saved) throws IOException {
            if (!failingSaves.isEmpty() && failingSaves.remove(0).equals("fail")) {
              throw new IOException("simulated crash before the cursor was saved");
            }
            super.save(tenantId, streamId, saved);
          }
        };
    service =
        new ExportJobService(
            new ExportSources(
                List.of(
                    new ExportSources.Named("fake", "acme", source),
                    new ExportSources.Named("theirs", "globex", source))),
            registry,
            lane,
            pipeline,
            cursors,
            new ParserRegistry(),
            MAPPER,
            push,
            Duration.ofMillis(10));
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    source.release.countDown();
    service.shutdown();
    registry.close();
  }

  private ExportJob submit(String streamId, boolean fromBeginning) throws IOException {
    return submit(streamId, TextNode.valueOf("q"), fromBeginning);
  }

  private ExportJob submit(String streamId, JsonNode query, boolean fromBeginning)
      throws IOException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (true) {
      try {
        return service.submit(
            "acme", new ExportRequest("fake", streamId, query, FROM, TO, fromBeginning));
      } catch (JobSlotsBusyException | ExportBusyException e) {
        // The slot and the stream free a moment after the previous job finishes.
        if (Instant.now().isAfter(deadline)) {
          throw e;
        }
        sleep(10);
      }
    }
  }

  private static ExportJob awaitFinished(ExportJob job) {
    Instant deadline = Instant.now().plusSeconds(10);
    while (!job.state().isFinished() && Instant.now().isBefore(deadline)) {
      sleep(10);
    }
    assertTrue(job.state().isFinished(), "job still " + job.state());
    return job;
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Every line spooled for the stream, in order. */
  private List<String> spooled(String streamId) throws IOException {
    Path streamDir = dir.resolve("spool/tenant=acme/stream=" + streamId);
    List<String> lines = new ArrayList<>();
    try (Stream<Path> files = Files.list(streamDir)) {
      for (Path file :
          files.filter(p -> p.getFileName().toString().matches("\\d+\\.log")).sorted().toList()) {
        lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
      }
    }
    return lines;
  }

  @Test
  void pagesLandInTheSpoolInOrderWithTheCursorSavedAfterEach() throws Exception {
    source.pages(List.of("a1", "a2"), List.of("b1"), List.of("c1", "c2"));

    ExportJob job = awaitFinished(submit("elk", false));

    assertEquals(State.COMPLETED, job.state(), job.error());
    assertEquals(List.of("a1", "a2", "b1", "c1", "c2"), spooled("elk"));
    assertEquals(3, job.pages());
    assertEquals(5, job.lines());
    var saved = cursors.load("acme", "elk").orElseThrow();
    assertTrue(saved.done());
    assertEquals(3, saved.pages());
    assertEquals(3, source.released.get(), "the last cursor's remote state is released");
    assertEquals(
        "json",
        registry.find("acme", "elk").orElseThrow().parser().name(),
        "export lines are JSON");
  }

  @Test
  void aCrashBeforeTheCursorIsSavedReFetchesOnePageAndTheSpoolKeepsItOnce() throws Exception {
    source.pages(List.of("a1"), List.of("b1", "b2"), List.of("c1"));
    failingSaves.addAll(List.of("ok", "fail")); // page b is spooled, its cursor isn't saved

    ExportJob crashed = awaitFinished(submit("elk", false));
    assertEquals(State.FAILED, crashed.state());
    assertEquals(List.of("a1", "b1", "b2"), spooled("elk"));

    ExportJob resumed = awaitFinished(submit("elk", false));

    assertEquals(State.COMPLETED, resumed.state(), resumed.error());
    assertTrue(resumed.resumed());
    assertEquals(1, resumed.duplicatePages(), "page b was fetched again and dropped by its key");
    assertEquals(List.of("a1", "b1", "b2", "c1"), spooled("elk"));
    assertEquals(4, resumed.lines());
  }

  @Test
  void aFinishedExportIsNotRepeatedUnlessFromBeginningAndAnotherQueryStartsOver() throws Exception {
    source.pages(List.of("a1"), List.of("b1"));
    awaitFinished(submit("elk", false));
    int fetches = source.fetches.get();

    ExportJob again = awaitFinished(submit("elk", false));
    assertEquals(State.COMPLETED, again.state());
    assertEquals(fetches, source.fetches.get(), "nothing fetched for a finished export");
    assertEquals(List.of("a1", "b1"), spooled("elk"));

    ExportJob rerun = awaitFinished(submit("elk", true));
    assertEquals(State.COMPLETED, rerun.state());
    assertFalse(rerun.resumed());
    assertEquals(List.of("a1", "b1", "a1", "b1"), spooled("elk"), "a new run has new keys");

    ExportJob other = awaitFinished(submit("elk", TextNode.valueOf("other query"), false));
    assertEquals(State.COMPLETED, other.state());
    assertFalse(other.resumed());
    assertNotEquals(again.identity(), other.identity());
    assertEquals(6, spooled("elk").size());
  }

  @Test
  void transientFailuresAreRetriedAndPermanentOnesAreNot() throws Exception {
    source.pages(List.of("a1"), List.of("b1"));
    source.failures.set(2); // two IOExceptions, then success
    ExportJob retried = awaitFinished(submit("elk", false));
    assertEquals(State.COMPLETED, retried.state(), retried.error());
    assertEquals(List.of("a1", "b1"), spooled("elk"));

    source.failures.set(ExportJobService.RETRIES + 1);
    ExportJob exhausted = awaitFinished(submit("other", false));
    assertEquals(State.FAILED, exhausted.state());
    assertTrue(exhausted.error().contains("IOException"), exhausted.error());

    source.permanent = true;
    int before = source.fetches.get();
    ExportJob refused = awaitFinished(submit("third", false));
    assertEquals(State.FAILED, refused.state());
    assertTrue(refused.error().contains("bad query"), refused.error());
    assertEquals(before + 1, source.fetches.get(), "a permanent failure is not retried");
  }

  @Test
  void anExportPausesWhileItsStreamHoldsTooMuchNotYetIngested() throws Exception {
    lane.shutdown();
    start(new ReplayProperties(1, DataSize.ofGigabytes(1), DataSize.ofBytes(4)));
    source.pages(List.of("a1", "a2"), List.of("b1"));

    ExportJob job = submit("elk", false);
    Instant deadline = Instant.now().plusSeconds(5);
    while (!"SPOOL_FULL".equals(job.paused()) && Instant.now().isBefore(deadline)) {
      sleep(10);
    }
    assertEquals("SPOOL_FULL", job.paused());
    assertEquals(List.of("a1", "a2"), spooled("elk"), "no page while paused");

    service.cancel("acme", job.id());
    awaitFinished(job);
    assertEquals(State.CANCELLED, job.state());
    assertTrue(job.pausedMs() > 0);
  }

  @Test
  void anExportWaitsForTheReplayReadBudget() throws Exception {
    lane.shutdown();
    start(new ReplayProperties(1, DataSize.ofBytes(20), null));
    source.pages(List.of("0123456789"), List.of("0123456789"), List.of("0123456789"));
    long started = System.nanoTime();

    ExportJob job = awaitFinished(submit("elk", false));

    assertEquals(State.COMPLETED, job.state());
    // 33 bytes at 20B/s: the second page waits ~0.55s, the third ~0.55s more.
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 1_000);
  }

  @Test
  void oneExportPerStreamAndOneReplaySlot() throws Exception {
    source.pages(List.of("a1"));
    source.blockFetches();
    ExportJob first = submit("elk", false);

    assertThrows(
        ExportBusyException.class,
        () -> service.submit("acme", new ExportRequest("fake", "elk", null, FROM, TO, false)));
    assertThrows(
        JobSlotsBusyException.class,
        () -> service.submit("acme", new ExportRequest("fake", "other", null, FROM, TO, false)));
    assertTrue(registry.find("acme", "other").isEmpty(), "a refused export creates no stream");

    source.release.countDown();
    assertEquals(State.COMPLETED, awaitFinished(first).state());
  }

  @Test
  void requestsAreValidatedBeforeAnythingStarts() {
    assertThrows(
        ExportSourceNotFoundException.class,
        () -> service.submit("acme", new ExportRequest("theirs", "elk", null, FROM, TO, false)),
        "another tenant's source looks like no source");
    assertThrows(
        ExportSourceNotFoundException.class,
        () -> service.submit("acme", new ExportRequest("nope", "elk", null, FROM, TO, false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.submit("acme", new ExportRequest("fake", "elk", null, TO, FROM, false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.submit("acme", new ExportRequest("fake", "../x", null, FROM, TO, false)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.submit(
                "acme",
                new ExportRequest("fake", "elk", TextNode.valueOf("invalid"), FROM, TO, false)));
    when(pipeline.walFull()).thenReturn(true);
    assertThrows(
        WalFullException.class,
        () -> service.submit("acme", new ExportRequest("fake", "elk", null, FROM, TO, false)));
    assertTrue(registry.find("acme", "elk").isEmpty(), "a refused export creates no stream");
  }

  @Test
  void aLineWithANewlineStaysOneLine() throws Exception {
    source.pages(List.of("{\"message\":\"two\nlines\"}", "  "));

    awaitFinished(submit("elk", false));

    assertEquals(List.of("{\"message\":\"two lines\"}"), spooled("elk"));
  }

  @Test
  void anotherTenantDoesNotSeeTheJob() throws Exception {
    source.pages(List.of("a1"));
    ExportJob job = awaitFinished(submit("elk", false));

    assertTrue(service.find("globex", job.id()).isEmpty());
    assertTrue(service.cancel("globex", job.id()).isEmpty());
    assertTrue(service.find("acme", job.id()).isPresent());
  }

  /** Pages from a list; the position is the next page's index. */
  private static final class FakeSource implements ExportSource {
    private List<List<String>> pages = List.of();
    final AtomicInteger fetches = new AtomicInteger();
    final AtomicInteger failures = new AtomicInteger();
    final AtomicInteger released = new AtomicInteger();
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean blocking;
    volatile boolean permanent;

    @SafeVarargs
    final void pages(List<String>... pages) {
      this.pages = List.of(pages);
    }

    void blockFetches() {
      blocking = true;
    }

    @Override
    public void validate(JsonNode query) {
      if (query != null && query.asText().equals("invalid")) {
        throw new IllegalArgumentException("invalid query");
      }
    }

    @Override
    public String describe() {
      return "fake";
    }

    @Override
    public Page fetch(Query query, Cursor cursor) throws IOException, InterruptedException {
      fetches.incrementAndGet();
      if (blocking) {
        release.await(10, TimeUnit.SECONDS);
      }
      if (permanent) {
        throw new PermanentExportException("bad query");
      }
      if (failures.get() > 0) {
        failures.decrementAndGet();
        throw new IOException("connection reset");
      }
      int page = cursor == null ? 0 : cursor.position().get("page").asInt();
      Cursor next = new Cursor(MAPPER.createObjectNode().put("page", page + 1), "session-" + page);
      return new Page(pages.get(page), "page-" + page, next, page + 1 >= pages.size());
    }

    @Override
    public void release(Cursor cursor) {
      released.set(cursor.position().get("page").asInt());
    }
  }
}
