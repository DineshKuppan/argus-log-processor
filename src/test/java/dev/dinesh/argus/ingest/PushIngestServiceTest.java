package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.config.QuotaProperties;
import dev.dinesh.argus.ingest.PushIngestService.PushRequest;
import dev.dinesh.argus.ingest.PushIngestService.PushResult;
import dev.dinesh.argus.ingest.PushRejectedException.Reason;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.quota.LocalQuotaStore;
import dev.dinesh.argus.quota.QuotaService;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.suppression.LocalSuppressionStore;
import dev.dinesh.argus.suppression.MessageFingerprint;
import dev.dinesh.argus.suppression.Suppressor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class PushIngestServiceTest {

  @TempDir Path dir;

  private final IngestPipeline pipeline = mock(IngestPipeline.class);
  private final CheckpointStore checkpoints = mock(CheckpointStore.class);
  private final SourceLeases leases = new SourceLeases();
  private StreamRegistry registry;
  private Suppressor suppressor;
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  @AfterEach
  void closeSpools() {
    if (registry != null) {
      registry.close();
    }
  }

  private PushProperties push(
      long maxRequestBytes, int maxLines, int maxRequests, long maxSpool, int maxStreams) {
    return new PushProperties(
        dir.resolve("spool"),
        DataSize.ofBytes(maxRequestBytes),
        maxLines,
        maxRequests,
        null,
        DataSize.ofBytes(maxSpool),
        null,
        maxStreams,
        0,
        0,
        null);
  }

  private PushIngestService service() throws IOException {
    return service(push(1 << 20, 100, 4, 1L << 30, 3), 1L << 20);
  }

  private PushIngestService service(PushProperties props, long quotaBytesPerSecond)
      throws IOException {
    when(checkpoints.load(any(), any())).thenReturn(Optional.empty());
    registry = new StreamRegistry(props, checkpoints, leases);
    IngestProperties ingest =
        new IngestProperties(0, null, null, 0, 0, DataSize.ofBytes(64), 0, List.of(), null, null);
    QuotaService quotas =
        new QuotaService(
            new LocalQuotaStore(),
            new QuotaProperties(null, DataSize.ofBytes(quotaBytesPerSecond), 1.0, Map.of()));
    suppressor =
        new Suppressor(
            new LocalSuppressionStore(Duration.ofSeconds(10), 1000),
            new MessageFingerprint(false),
            new ArgusMetrics(meters));
    return new PushIngestService(
        registry,
        pipeline,
        quotas,
        suppressor,
        new ParserRegistry(),
        props,
        ingest,
        new ArgusMetrics(meters));
  }

  private static PushRequest request(String tenant, String body) {
    return request(tenant, body.getBytes(StandardCharsets.UTF_8), null, null);
  }

  /** A body that blocks its reader until {@code release}, after counting down {@code reading}. */
  private static InputStream blocking(CountDownLatch reading, CountDownLatch release) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        reading.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
        return -1;
      }
    };
  }

  private static CompletableFuture<PushResult> pushAsync(
      PushIngestService service, PushRequest request) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return service.push(request);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static PushRequest request(String tenant, byte[] body, String encoding, String key) {
    return new PushRequest(
        tenant, "app", new ByteArrayInputStream(body), encoding, body.length, key, null, null);
  }

  private String spooled(String tenant) throws IOException {
    return Files.readString(
        dir.resolve("spool/tenant=" + tenant + "/stream=app/000001.log"), StandardCharsets.UTF_8);
  }

  private static byte[] gzip(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    }
    return out.toByteArray();
  }

  private static Reason rejection(ThrowingCall call) {
    return assertThrows(PushRejectedException.class, call::run).reason();
  }

  interface ThrowingCall {
    void run() throws Exception;
  }

  @Test
  void spoolsLinesAndAddsAMissingFinalNewline() throws IOException {
    PushResult result = service().push(request("acme", "{\"msg\":\"a\"}\n\n  \n{\"msg\":\"b\"}"));

    assertEquals(2, result.accepted());
    assertEquals(0, result.startOffset());
    assertEquals(24, result.endOffset());
    assertFalse(result.duplicate());
    assertEquals("{\"msg\":\"a\"}\n{\"msg\":\"b\"}\n", spooled("acme"));
    assertEquals(24, registry.pendingBytes());
  }

  @Test
  void gzipBodiesAreDecompressedAndAGzipBombIsRefused() throws IOException {
    PushIngestService service = service(push(64, 100, 4, 1L << 30, 3), 1L << 20);

    PushResult ok =
        service.push(
            request("acme", gzip("one\ntwo\n".getBytes(StandardCharsets.UTF_8)), "gzip", null));
    assertEquals(2, ok.accepted());
    assertEquals("one\ntwo\n", spooled("acme"));

    // 1MB of zeros compresses to about 1KB, under any wire limit: the decompressed size is checked.
    byte[] bomb = gzip(new byte[1 << 20]);
    assertEquals(
        Reason.TOO_LARGE, rejection(() -> service.push(request("acme", bomb, "gzip", null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.push(request("acme", "not gzip".getBytes(), "gzip", null)));
    assertEquals(
        Reason.UNSUPPORTED_ENCODING,
        rejection(() -> service.push(request("acme", "x\n".getBytes(), "br", null))));
  }

  @Test
  void aBodyOverTheLimitIsRefusedBeforeItIsRead() throws IOException {
    PushIngestService service = service(push(8, 100, 4, 1L << 30, 3), 1L << 20);
    InputStream unread = new ByteArrayInputStream(new byte[0]);

    assertEquals(
        Reason.TOO_LARGE,
        rejection(
            () -> service.push(new PushRequest("acme", "app", unread, null, 9, null, null, null))));
    assertEquals(Reason.TOO_LARGE, rejection(() -> service.push(request("acme", "123456789"))));
  }

  @Test
  void tooManyLinesIsRefusedAndLongLinesAreTruncated() throws IOException {
    PushIngestService service = service(push(1 << 20, 3, 4, 1L << 30, 3), 1L << 20);

    assertEquals(Reason.TOO_LARGE, rejection(() -> service.push(request("acme", "a\nb\nc\nd\n"))));

    // max-line-bytes is 64 in this test.
    String max = "x".repeat(64);
    PushResult result = service.push(request("acme", max + "OVERFLOW\nshort\n"));
    assertEquals(1, result.truncated());
    assertEquals(max + "\nshort\n", spooled("acme"));
  }

  @Test
  void repeatedMessagesAreSuppressedAtReceiveTimeAndNeverSpooled() throws IOException {
    PushIngestService service = service();

    PushResult first =
        service.push(
            request(
                "acme",
                "{\"ts\":1,\"msg\":\"disk full\"}\n"
                    + "{\"ts\":2,\"msg\":\"disk full\"}\n"
                    + "{\"ts\":3,\"msg\":\"other\"}\n"));
    PushResult second = service.push(request("acme", "{\"ts\":4,\"msg\":\"disk full\"}\n"));
    PushResult otherTenant = service.push(request("globex", "{\"ts\":5,\"msg\":\"disk full\"}\n"));

    assertEquals(2, first.accepted());
    assertEquals(1, first.suppressed(), "the message, not the whole line, is compared");
    assertEquals(0, second.accepted());
    assertEquals(1, second.suppressed());
    assertEquals(1, otherTenant.accepted(), "suppression is per tenant");
    assertEquals(
        "{\"ts\":1,\"msg\":\"disk full\"}\n{\"ts\":3,\"msg\":\"other\"}\n", spooled("acme"));
  }

  @Test
  void metricsCountRequestsLinesAndDecisionsPerTenant() throws IOException {
    PushIngestService service = service(push(1 << 20, 100, 4, 1L << 30, 3), 64);
    service.push(request("acme", "{\"msg\":\"a\"}\n{\"msg\":\"a\"}\n{\"msg\":\"b\"}\n"));
    rejection(() -> service.push(request("acme", "{\"msg\":\"c\"}\n".repeat(20))));

    assertEquals(1, requests("accepted"));
    assertEquals(1, requests("rejected_quota"));
    assertEquals(2, meters.get("argus.push.lines").tags("result", "accepted").counter().count());
    assertEquals(1, meters.get("argus.push.lines").tags("result", "suppressed").counter().count());
    assertEquals(
        1, meters.get("argus.suppression.decisions").tags("decision", "deny").counter().count());
    assertEquals(
        1, meters.get("argus.push.request").tag("outcome", "rejected_quota").timer().count());
  }

  private double requests(String outcome) {
    return meters
        .get("argus.push.requests")
        .tags("tenant", "acme", "outcome", outcome)
        .counter()
        .count();
  }

  @Test
  void aRepeatedIdempotencyKeyIsAnsweredFromTheFirstRequestWithoutChargingTheQuota()
      throws IOException {
    // Quota for exactly one request of 4 bytes.
    PushIngestService service = service(push(1 << 20, 100, 4, 1L << 30, 3), 4);
    byte[] body = "abc\n".getBytes(StandardCharsets.UTF_8);

    PushResult first = service.push(request("acme", body, null, "batch-1"));
    PushResult retry = service.push(request("acme", body, null, "batch-1"));

    assertTrue(retry.duplicate());
    assertEquals(first.startOffset(), retry.startOffset());
    assertEquals(first.endOffset(), retry.endOffset());
    assertEquals("abc\n", spooled("acme"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.push(request("acme", body, null, "has space")));
  }

  @Test
  void aTenantOverItsQuotaGets429WithRetryAfterWhileAnotherTenantIsUnaffected() throws IOException {
    PushIngestService service = service(push(1 << 20, 100, 4, 1L << 30, 3), 10);

    service.push(request("acme", "123456789\n"));
    PushRejectedException over =
        assertThrows(PushRejectedException.class, () -> service.push(request("acme", "x\n")));

    assertEquals(Reason.QUOTA, over.reason());
    assertEquals(1, over.retryAfterSeconds());
    assertEquals(1, service.push(request("globex", "123456789\n")).accepted());
  }

  @Test
  void aFullWalOrAFullSpoolGets429() throws IOException {
    PushIngestService service = service(push(1 << 20, 100, 4, 8, 3), 1L << 20);

    when(pipeline.walFull()).thenReturn(true);
    PushRejectedException wal =
        assertThrows(PushRejectedException.class, () -> service.push(request("acme", "a\n")));
    assertEquals(Reason.WAL_FULL, wal.reason());
    assertEquals(60, wal.retryAfterSeconds());

    when(pipeline.walFull()).thenReturn(false);
    service.push(request("acme", "12345678\n"));
    PushRejectedException spool =
        assertThrows(PushRejectedException.class, () -> service.push(request("acme", "a\n")));
    assertEquals(Reason.SPOOL_FULL, spool.reason());
    assertEquals(30, spool.retryAfterSeconds());
  }

  @Test
  void aTenantCannotOpenMoreThanItsStreams() throws IOException {
    PushIngestService service = service(push(1 << 20, 100, 4, 1L << 30, 1), 1L << 20);
    service.push(request("acme", "a\n"));

    assertEquals(
        Reason.TOO_MANY_STREAMS,
        rejection(
            () ->
                service.push(
                    new PushRequest(
                        "acme",
                        "second",
                        new ByteArrayInputStream("a\n".getBytes()),
                        null,
                        2,
                        null,
                        null,
                        null))));
    assertEquals(1, service.push(request("globex", "a\n")).accepted());
  }

  @Test
  void requestsBeyondTheConcurrentSlotsGet429() throws Exception {
    PushIngestService service = service(push(1 << 20, 100, 1, 1L << 30, 3), 1L << 20);
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    InputStream slow =
        new InputStream() {
          @Override
          public int read() throws IOException {
            reading.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            return -1;
          }
        };
    CompletableFuture<PushResult> first =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return service.push(
                    new PushRequest("acme", "app", slow, null, -1, null, null, null));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    assertTrue(reading.await(10, TimeUnit.SECONDS));

    PushRejectedException busy =
        assertThrows(PushRejectedException.class, () -> service.push(request("globex", "a\n")));

    assertEquals(Reason.BUSY, busy.reason());
    assertEquals(1, busy.retryAfterSeconds());
    release.countDown();
    assertEquals(0, first.get(10, TimeUnit.SECONDS).accepted());
    assertEquals(1, service.push(request("globex", "a\n")).accepted(), "the slot is free again");
  }

  @Test
  void aSpoolWriteThatFailsGivesBackItsSuppressionWindowsSoTheRetryIsKept() throws IOException {
    PushIngestService service = service();
    service.push(request("acme", "{\"msg\":\"warm up\"}\n"));
    registry.find("acme", "app").orElseThrow().spool().close();

    assertThrows(
        IllegalStateException.class,
        () -> service.push(request("acme", "{\"msg\":\"disk full\"}\n")));

    assertTrue(
        suppressor.check("acme", "disk full").allowed(),
        "the lost line's window was released, so its retry is not suppressed");
  }

  @Test
  void aRetryWhileItsOriginalIsInProgressGets429() throws Exception {
    PushIngestService service = service();
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<PushResult> original =
        pushAsync(
            service,
            new PushRequest(
                "acme", "app", blocking(reading, release), null, -1, "batch-1", null, null));
    assertTrue(reading.await(10, TimeUnit.SECONDS));

    PushRejectedException inFlight =
        assertThrows(
            PushRejectedException.class,
            () -> service.push(request("acme", "a\n".getBytes(), null, "batch-1")));

    assertEquals(Reason.KEY_IN_FLIGHT, inFlight.reason());
    assertEquals(1, inFlight.retryAfterSeconds());
    release.countDown();
    assertFalse(original.get(10, TimeUnit.SECONDS).duplicate());
    assertTrue(
        service.push(request("acme", "a\n".getBytes(), null, "batch-1")).duplicate(),
        "once the original is done, the retry is answered from it");
  }

  @Test
  void aRejectedRequestDoesNotCreateItsStream() throws IOException {
    PushIngestService service = service(push(8, 2, 4, 1L << 30, 3), 1L << 20);

    assertEquals(Reason.TOO_LARGE, rejection(() -> service.push(request("acme", "123456789"))));
    assertEquals(Reason.TOO_LARGE, rejection(() -> service.push(request("acme", "a\nb\nc\n"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.push(request("acme", "x".getBytes(), "gzip", null)));

    assertEquals(Optional.empty(), registry.find("acme", "app"));
  }

  @Test
  void suppressedLinesAreNotCountedAsTruncated() throws IOException {
    PushIngestService service = service();
    String longLine = "x".repeat(100);

    PushResult result = service.push(request("acme", longLine + "\n" + longLine + "\n"));

    assertEquals(1, result.accepted());
    assertEquals(1, result.suppressed());
    assertEquals(1, result.truncated());
  }

  @Test
  void aStreamIdUsedByFileIngestIsRefused() throws IOException {
    PushIngestService service = service();
    when(checkpoints.load("acme", "app"))
        .thenReturn(Optional.of(new SourceOffset("app", "/var/log/app.log", null, 0, 10, 0, null)));
    leases.tryAcquireForFile("globex", "app", "job-1");

    assertEquals(Reason.SOURCE_IN_USE, rejection(() -> service.push(request("acme", "a\n"))));
    assertEquals(
        Reason.SOURCE_IN_USE,
        rejection(() -> service.push(request("globex", "a\n"))),
        "a running file job holds it");
    leases.release("globex", "app", "job-1");
    service.push(request("globex", "a\n"));
    assertEquals(
        Optional.of("push stream app"),
        leases.tryAcquireForFile("globex", "app", "job-2"),
        "a file job can't take a stream's id");
  }
}
