package dev.dinesh.argus.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.export.ExportCursorStore.Saved;
import dev.dinesh.argus.export.ExportSource.Cursor;
import dev.dinesh.argus.export.ExportSource.Page;
import dev.dinesh.argus.export.ExportSource.Query;
import dev.dinesh.argus.ingest.IngestJob.State;
import dev.dinesh.argus.ingest.IngestJobService.JobSlotsBusyException;
import dev.dinesh.argus.ingest.IngestJobService.WalFullException;
import dev.dinesh.argus.ingest.IngestPipeline;
import dev.dinesh.argus.ingest.Lane;
import dev.dinesh.argus.ingest.PushStream;
import dev.dinesh.argus.ingest.ReplayLane;
import dev.dinesh.argus.ingest.StreamRegistry;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.source.StreamSpool;
import dev.dinesh.argus.tenant.TenantIds;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Exports a time range from a configured source into a stream spool ({@code P3_PLAN.md} D7), on the
 * replay lane (D8). Each page is appended under an {@code Idempotency-Key} derived from the export,
 * the run and the page, and the cursor after it is saved only once the page is durable: a crash
 * re-fetches at most one page, which the spool then answers as a duplicate. Pages go to the spool
 * directly, so suppression and the push quota don't apply (guardrail 8); the lane's read budget
 * does. The stream runner then ingests the spool like any push stream.
 */
@Slf4j
@Service
public class ExportJobService {

  static final int MAX_RETAINED_JOBS_PER_TENANT = 200;

  /** Retries of a transient failure before the job fails: 1s, 2s, 4s by default. */
  static final int RETRIES = 3;

  private static final Duration PAUSE_POLL = Duration.ofMillis(500);
  private static final long SLICE_MS = 100;

  /** No such source for the caller's tenant; maps to HTTP 404. */
  public static class ExportSourceNotFoundException extends RuntimeException {
    public ExportSourceNotFoundException(String name) {
      super("Export source not found: " + name);
    }
  }

  /** The stream already has an export running; maps to HTTP 409. */
  public static class ExportBusyException extends RuntimeException {
    public ExportBusyException(String streamId, String jobId) {
      super("Stream " + streamId + " already has export job " + jobId + " running");
    }
  }

  /**
   * One export request.
   *
   * @param source a configured source name of the caller's tenant
   * @param streamId the stream whose spool the pages go to
   * @param fromBeginning ignore a saved cursor of the same export and export it again
   */
  public record ExportRequest(
      String source,
      String streamId,
      JsonNode query,
      Instant from,
      Instant to,
      boolean fromBeginning) {}

  private final ExportSources sources;
  private final StreamRegistry streams;
  private final ReplayLane lane;
  private final IngestPipeline pipeline;
  private final ExportCursorStore cursors;
  private final ParserRegistry parsers;
  private final ObjectMapper mapper;
  private final long maxStreamPendingBytes;
  private final long maxSpoolPendingBytes;
  private final Duration retryInitial;
  private final ArgusMetrics metrics;
  private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();

  /** {@code tenant/stream} → the export job running on it. */
  private final Map<String, String> running = new ConcurrentHashMap<>();

  @Autowired
  public ExportJobService(
      ExportSources sources,
      StreamRegistry streams,
      ReplayLane lane,
      IngestPipeline pipeline,
      ExportCursorStore cursors,
      ParserRegistry parsers,
      ObjectMapper mapper,
      PushProperties push,
      ObjectProvider<ArgusMetrics> metrics) {
    this(
        sources,
        streams,
        lane,
        pipeline,
        cursors,
        parsers,
        mapper,
        push,
        Duration.ofSeconds(1),
        metrics.getIfAvailable(ArgusMetrics::noop));
  }

  ExportJobService(
      ExportSources sources,
      StreamRegistry streams,
      ReplayLane lane,
      IngestPipeline pipeline,
      ExportCursorStore cursors,
      ParserRegistry parsers,
      ObjectMapper mapper,
      PushProperties push,
      Duration retryInitial,
      ArgusMetrics metrics) {
    this.metrics = metrics;
    this.sources = sources;
    this.streams = streams;
    this.lane = lane;
    this.pipeline = pipeline;
    this.cursors = cursors;
    this.parsers = parsers;
    this.mapper = mapper;
    this.maxStreamPendingBytes = lane.props().maxPendingBytes().toBytes();
    // Exports leave at least half of the spool to live pushes.
    this.maxSpoolPendingBytes = push.maxSpoolBytes().toBytes() / 2;
    this.retryInitial = retryInitial;
  }

  /**
   * Validates the request and starts the export, resuming a saved cursor of the same export.
   *
   * @throws ExportSourceNotFoundException if the tenant has no such source
   * @throws ExportBusyException if the stream has an export running
   * @throws IllegalArgumentException for an invalid stream id, range or query
   * @throws dev.dinesh.argus.ingest.PushRejectedException if the stream can't be created (409)
   */
  public ExportJob submit(String tenantId, ExportRequest request) throws IOException {
    TenantIds.requireValid(tenantId);
    String streamId = SourceOffset.requireValidSourceId(request.streamId());
    if (request.from() == null || request.to() == null || !request.from().isBefore(request.to())) {
      throw new IllegalArgumentException("from and to are required, and from must be before to");
    }
    ExportSources.Named named =
        sources
            .forTenant(tenantId, request.source())
            .orElseThrow(() -> new ExportSourceNotFoundException(request.source()));
    ExportSource source = named.source();
    source.validate(request.query());
    if (pipeline.walFull()) {
      throw new WalFullException();
    }
    Query query = new Query(request.query(), request.from(), request.to());
    String identity = identity(named, query);
    String streamKey = tenantId + "/" + streamId;
    String other = running.putIfAbsent(streamKey, "starting");
    if (other != null) {
      throw new ExportBusyException(streamId, other);
    }
    ExportJob job = null;
    try {
      // Before the stream is created: a refused export must not take a stream slot.
      if (!lane.hasFreeSlot()) {
        throw new RejectedExecutionException("replay lane busy");
      }
      PushStream stream = streams.getOrCreate(tenantId, streamId);
      stream.useParser(parsers.resolve("json", null).orElseThrow());
      Optional<Saved> saved =
          cursors
              .load(tenantId, streamId)
              .filter(s -> s.identity().equals(identity) && !request.fromBeginning());
      job =
          new ExportJob(
              UUID.randomUUID().toString(),
              tenantId,
              named.name(),
              streamId,
              query,
              identity,
              saved.map(Saved::runId).orElseGet(() -> UUID.randomUUID().toString()),
              saved.isPresent());
      if (saved.isPresent()) {
        // Totals continue across a resume.
        job.pages.set(saved.get().pages());
        job.lines.set(saved.get().lines());
        job.bytes.set(saved.get().bytes());
      }
      Cursor start = saved.map(Saved::cursor).orElse(null);
      boolean done = saved.map(Saved::done).orElse(false);
      if (saved.isPresent()) {
        job.position = start == null ? null : start.position();
      }
      evictOldJobs(tenantId);
      jobs.put(job.id(), job);
      running.put(streamKey, job.id());
      ExportJob submitted = job;
      if (done) {
        // Everything is already in the spool: nothing to fetch, no slot needed.
        finish(submitted, State.COMPLETED, null);
      } else {
        lane.execute(() -> run(submitted, source, stream, start));
      }
    } catch (RejectedExecutionException e) {
      if (job != null) {
        jobs.remove(job.id());
      }
      running.remove(streamKey);
      throw new JobSlotsBusyException(Lane.REPLAY, lane.maxConcurrentJobs());
    } catch (IOException | RuntimeException e) {
      // Only this thread holds the stream's entry until the job starts.
      if (job != null) {
        jobs.remove(job.id());
      }
      running.remove(streamKey);
      throw e;
    }
    log.info(
        "Export job {} submitted tenant={} source={} stream={} from={} to={} resumed={}",
        job.id(),
        tenantId,
        named.name(),
        streamId,
        query.from(),
        query.to(),
        job.resumed());
    return job;
  }

  /** The tenant's job; another tenant's job id is reported as absent. */
  public Optional<ExportJob> find(String tenantId, String jobId) {
    return Optional.ofNullable(jobs.get(jobId)).filter(j -> j.tenantId().equals(tenantId));
  }

  /** Requests cancellation; the job stops before its next page, its cursor saved. */
  public Optional<ExportJob> cancel(String tenantId, String jobId) {
    Optional<ExportJob> job = find(tenantId, jobId);
    job.ifPresent(j -> j.cancelFlag().set(true));
    return job;
  }

  @PreDestroy
  void shutdown() {
    jobs.values().forEach(j -> j.cancelFlag().set(true));
  }

  private void run(ExportJob job, ExportSource source, PushStream stream, Cursor start) {
    job.running();
    long startNanos = System.nanoTime();
    State outcome = State.FAILED;
    String error = null;
    Cursor cursor = start;
    try {
      while (true) {
        if (job.cancelFlag().get() || !awaitRoom(job, stream)) {
          outcome = State.CANCELLED;
          return;
        }
        Page page = fetchWithRetries(job, source, cursor);
        if (page == null) {
          outcome = State.CANCELLED;
          return;
        }
        if (!spool(job, stream, page)) {
          outcome = State.CANCELLED;
          return;
        }
        cursor = page.next();
        if (page.last()) {
          source.release(cursor);
          outcome = State.COMPLETED;
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = "Interrupted";
    } catch (Exception e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
      log.warn("Export job {} failed: {}", job.id(), error);
    } finally {
      if (outcome == State.CANCELLED && cursor != null) {
        source.release(cursor);
      }
      finish(job, outcome, error);
      // Canonical line: one per export job, whatever its outcome.
      log.info(
          "Export finished tenant={} source={} stream={} job={} outcome={} error={} resumed={}"
              + " pages={} lines={} bytes={} duplicatePages={} pausedMs={} durationMs={}",
          job.tenantId(),
          job.source(),
          job.streamId(),
          job.id(),
          outcome,
          error,
          job.resumed(),
          job.pages(),
          job.lines(),
          job.bytes(),
          job.duplicatePages(),
          job.pausedMs(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
  }

  /**
   * Appends the page under its key, then saves the cursor after it.
   *
   * @return false if the job was cancelled while waiting for the read budget (nothing written)
   */
  private boolean spool(ExportJob job, PushStream stream, Page page)
      throws IOException, InterruptedException {
    long startNanos = System.nanoTime();
    byte[] body = toLines(page.lines());
    long waitStart = System.nanoTime();
    boolean passed = lane.readLimit().acquire(body.length, job.cancelFlag()::get);
    metrics.replayWait(System.nanoTime() - waitStart);
    if (!passed) {
      return false;
    }
    String key = pageKey(job, page.pageId());
    boolean duplicate = false;
    StreamSpool.Append append = null;
    if (body.length > 0) {
      append = stream.spool().append(body, body.length, key, Instant.now());
      duplicate = append.duplicate();
    }
    long pages = job.pages.incrementAndGet();
    // A duplicate page was spooled by the interrupted run, after its last saved totals: it counts
    // once, here.
    job.lines.addAndGet(page.lines().size());
    job.bytes.addAndGet(body.length);
    if (duplicate) {
      job.duplicatePages.incrementAndGet();
    }
    cursors.save(
        job.tenantId(),
        job.streamId(),
        new Saved(
            job.identity(),
            job.runId(),
            job.source(),
            page.next(),
            page.last(),
            pages,
            job.lines(),
            job.bytes(),
            Instant.now()));
    job.position = page.next() == null ? null : page.next().position();
    job.lastPageAt = Instant.now();
    // Canonical line: one per page.
    log.info(
        "Export page finished tenant={} source={} stream={} job={} page={} lines={} bytes={}"
            + " duplicate={} spool={} startOffset={} endOffset={} last={} durationMs={}",
        job.tenantId(),
        job.source(),
        job.streamId(),
        job.id(),
        page.pageId(),
        page.lines().size(),
        body.length,
        duplicate,
        append == null ? null : append.spool(),
        append == null ? null : append.startOffset(),
        append == null ? null : append.endOffset(),
        page.last(),
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    metrics.exportPage(job.tenantId(), job.source(), page.lines().size());
    return true;
  }

  /** Null if the job was cancelled while waiting to retry. */
  private Page fetchWithRetries(ExportJob job, ExportSource source, Cursor cursor)
      throws IOException, InterruptedException {
    long delayMs = retryInitial.toMillis();
    for (int attempt = 0; ; attempt++) {
      try {
        return source.fetch(job.query(), cursor);
      } catch (PermanentExportException e) {
        throw e;
      } catch (IOException e) {
        if (attempt >= RETRIES) {
          throw e;
        }
        metrics.exportRetry(job.tenantId(), job.source());
        log.warn(
            "Export job {}: fetch failed, retry {} of {} in {}ms: {}",
            job.id(),
            attempt + 1,
            RETRIES,
            delayMs,
            e.toString());
        if (!sleep(job, delayMs)) {
          return null;
        }
        delayMs *= 2;
      }
    }
  }

  /**
   * Waits while the stream or the spool holds too much not yet ingested, or the WAL is full.
   *
   * @return false if the job was cancelled while waiting
   */
  private boolean awaitRoom(ExportJob job, PushStream stream) throws InterruptedException {
    long pausedSince = 0;
    try {
      while (true) {
        String reason =
            pipeline.walFull()
                ? "WAL_FULL"
                : stream.spool().pendingBytes() >= maxStreamPendingBytes
                        || streams.pendingBytes() >= maxSpoolPendingBytes
                    ? "SPOOL_FULL"
                    : null;
        if (reason == null) {
          return true;
        }
        if (pausedSince == 0) {
          pausedSince = System.nanoTime();
          log.info("Export job {} paused: {}", job.id(), reason);
        }
        job.paused = reason;
        if (!sleep(job, PAUSE_POLL.toMillis())) {
          return false;
        }
      }
    } finally {
      job.paused = null;
      if (pausedSince != 0) {
        job.pausedMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pausedSince));
      }
    }
  }

  /** Sleeps in slices; false if the job was cancelled meanwhile. */
  private static boolean sleep(ExportJob job, long millis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    for (long left = millis; left > 0; ) {
      if (job.cancelFlag().get()) {
        return false;
      }
      Thread.sleep(Math.min(left, SLICE_MS));
      left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    }
    return !job.cancelFlag().get();
  }

  private void finish(ExportJob job, State state, String error) {
    job.finished(state, error);
    running.remove(job.tenantId() + "/" + job.streamId(), job.id());
  }

  private static byte[] toLines(List<String> lines) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String line : lines) {
      // A raw newline would split one record into two lines (and two event ids).
      String flat =
          line.indexOf('\n') < 0 && line.indexOf('\r') < 0 ? line : line.replaceAll("[\r\n]", " ");
      if (flat.isBlank()) {
        continue;
      }
      out.writeBytes(flat.getBytes(StandardCharsets.UTF_8));
      out.write('\n');
    }
    return out.toByteArray();
  }

  private String identity(ExportSources.Named named, Query query) throws IOException {
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("source", named.name());
    parts.put("reads", named.source().describe());
    parts.put("query", query.query());
    parts.put("from", query.from().toString());
    parts.put("to", query.to().toString());
    return sha256(mapper.writeValueAsString(parts));
  }

  private static String pageKey(ExportJob job, String pageId) {
    return "export-" + sha256(job.identity() + "\n" + job.runId() + "\n" + pageId);
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void evictOldJobs(String tenantId) {
    List<ExportJob> own =
        jobs.values().stream().filter(j -> j.tenantId().equals(tenantId)).toList();
    int excess = own.size() - MAX_RETAINED_JOBS_PER_TENANT + 1;
    if (excess <= 0) {
      return;
    }
    own.stream()
        .filter(j -> j.state().isFinished())
        .sorted(Comparator.comparing(ExportJob::finishedAt))
        .limit(excess)
        .toList()
        .forEach(j -> jobs.remove(j.id()));
  }
}
