package dev.dinesh.argus.ingest;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.ReplayProperties;
import dev.dinesh.argus.ingest.IngestJob.State;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.ingest.IngestPipeline.Request;
import dev.dinesh.argus.parser.LogParser;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.tenant.TenantRoots;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Runs ingest jobs on a fixed number of slots per lane ({@code P3_PLAN.md} D8): live jobs on {@code
 * max-concurrent-jobs}, replays on the {@link ReplayLane}'s, paced by its read budget. Jobs beyond
 * a lane's slots are rejected rather than queued, so callers get immediate backpressure (HTTP 429)
 * instead of unbounded memory growth. Jobs belong to a tenant: a tenant sees only its own jobs, and
 * sources are per tenant.
 */
@Slf4j
@Service
public class IngestJobService {

  /**
   * Finished jobs kept per tenant for status queries; older ones are evicted. Per tenant, so one
   * tenant's submissions never evict another tenant's job records.
   */
  static final int MAX_RETAINED_JOBS_PER_TENANT = 200;

  private static final int SOURCE_ID_HASH_BYTES = 6;

  private final IngestPipeline pipeline;
  private final ParserRegistry parsers;
  private final TenantRoots tenantRoots;
  private final ThreadPoolExecutor executor;
  private final ReplayLane replay;
  private final Map<String, IngestJob> jobs = new ConcurrentHashMap<>();

  /** Shared with stream runs: one run per source at a time. */
  private final SourceLeases leases;

  public IngestJobService(
      IngestPipeline pipeline,
      ParserRegistry parsers,
      IngestProperties props,
      TenantRoots tenantRoots) {
    this(
        pipeline,
        parsers,
        props,
        tenantRoots,
        new SourceLeases(),
        new ReplayLane(ReplayProperties.defaults()));
  }

  @Autowired
  public IngestJobService(
      IngestPipeline pipeline,
      ParserRegistry parsers,
      IngestProperties props,
      TenantRoots tenantRoots,
      SourceLeases leases,
      ReplayLane replay) {
    this.pipeline = pipeline;
    this.parsers = parsers;
    this.tenantRoots = tenantRoots;
    this.leases = leases;
    this.replay = replay;
    AtomicInteger threadCount = new AtomicInteger();
    this.executor =
        new ThreadPoolExecutor(
            props.maxConcurrentJobs(),
            props.maxConcurrentJobs(),
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
              Thread t = new Thread(r, "argus-job-" + threadCount.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
  }

  /** Ingest can't take a new job right now; maps to HTTP 429 + {@code Retry-After}. */
  public abstract static class IngestBusyException extends RuntimeException {
    private final int retryAfterSeconds;

    IngestBusyException(String message, int retryAfterSeconds) {
      super(message);
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  /** Thrown when every job slot of the lane is busy. */
  public static class JobSlotsBusyException extends IngestBusyException {
    /** About how long a job slot takes to free up. */
    static final int RETRY_AFTER_SECONDS = 30;

    public JobSlotsBusyException(Lane lane, int slots) {
      super(
          "All "
              + slots
              + " "
              + lane.name().toLowerCase(Locale.ROOT)
              + " job slots are busy; retry later",
          RETRY_AFTER_SECONDS);
    }
  }

  /** Thrown while the WAL is above its high watermark: the archive is behind (P2 D7). */
  public static class WalFullException extends IngestBusyException {
    /** Longer than for busy slots: the archive must drain a fifth of the WAL first. */
    static final int RETRY_AFTER_SECONDS = 60;

    public WalFullException() {
      super("The WAL is full while the archive catches up; retry later", RETRY_AFTER_SECONDS);
    }
  }

  /**
   * Thrown when the source already has a running job or stream run, or is a push stream's; maps to
   * HTTP 409.
   */
  public static class SourceBusyException extends RuntimeException {
    SourceBusyException(String sourceId, String owner) {
      super("Source " + sourceId + " is busy with " + owner);
    }
  }

  /** Starts a job on its default lane: replay when {@code fromBeginning}, live otherwise. */
  public IngestJob submit(
      String tenantId,
      String rawPath,
      String requestedSourceId,
      boolean fromBeginning,
      String format,
      String pattern)
      throws IOException {
    return submit(tenantId, rawPath, requestedSourceId, fromBeginning, format, pattern, null);
  }

  /**
   * Validates the request and starts a job.
   *
   * @throws NoSuchFileException if the file does not exist
   * @param format primary format, or null to detect it
   * @param pattern Logback layout for format {@code logback}
   * @param requestedLane null for the default: replay when {@code fromBeginning}, live otherwise
   * @throws IllegalArgumentException if the path is outside the allowed roots, not a regular file,
   *     the source id is invalid, or the format or pattern is not supported
   */
  public IngestJob submit(
      String tenantId,
      String rawPath,
      String requestedSourceId,
      boolean fromBeginning,
      String format,
      String pattern,
      Lane requestedLane)
      throws IOException {
    TenantIds.requireValid(tenantId);
    Path realPath = tenantRoots.forTenant(tenantId).requireFile(rawPath);
    String sourceId =
        requestedSourceId == null || requestedSourceId.isBlank()
            ? defaultSourceId(realPath)
            : SourceOffset.requireValidSourceId(requestedSourceId);
    LogParser parser = parsers.resolve(format, pattern).orElse(null);
    Lane lane = requestedLane != null ? requestedLane : fromBeginning ? Lane.REPLAY : Lane.LIVE;
    if (pipeline.walFull()) {
      throw new WalFullException();
    }

    IngestJob job =
        new IngestJob(
            JobIds.next(pipeline.instanceId()),
            tenantId,
            sourceId,
            realPath,
            fromBeginning,
            parser,
            lane);
    Optional<String> owner = leases.tryAcquireForFile(tenantId, sourceId, job.id());
    if (owner.isPresent()) {
      throw new SourceBusyException(sourceId, owner.get());
    }
    try {
      evictOldJobs(tenantId);
      jobs.put(job.id(), job);
      if (lane == Lane.REPLAY) {
        replay.execute(() -> runJob(job));
      } else {
        executor.execute(() -> runJob(job));
      }
    } catch (RejectedExecutionException e) {
      jobs.remove(job.id());
      leases.release(tenantId, sourceId, job.id());
      throw new JobSlotsBusyException(
          lane, lane == Lane.REPLAY ? replay.maxConcurrentJobs() : executor.getMaximumPoolSize());
    }
    log.info(
        "Ingest job {} submitted tenant={} source={} path={} lane={}",
        job.id(),
        tenantId,
        sourceId,
        realPath,
        lane);
    return job;
  }

  /** Why {@code jobId} isn't here, if another instance started it (D12); else null. */
  public String elsewhere(String jobId) {
    return JobIds.elsewhere(jobId, pipeline.instanceId());
  }

  /** The tenant's job; another tenant's job id is reported as absent, not as forbidden. */
  public Optional<IngestJob> find(String tenantId, String jobId) {
    return Optional.ofNullable(jobs.get(jobId)).filter(j -> j.tenantId().equals(tenantId));
  }

  /** Number of jobs currently occupying a slot. */
  public int activeJobs() {
    return executor.getActiveCount();
  }

  /** Requests cancellation; the job stops at its next batch boundary. */
  public Optional<IngestJob> cancel(String tenantId, String jobId) {
    Optional<IngestJob> job = find(tenantId, jobId);
    job.ifPresent(j -> j.cancelFlag().set(true));
    return job;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    jobs.values().forEach(j -> j.cancelFlag().set(true));
    executor.shutdown();
    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
      log.warn("Ingest jobs did not stop within 30s; checkpoints remain at the last commit");
    }
  }

  private void runJob(IngestJob job) {
    job.running();
    try {
      Request request =
          new Request(
              job.tenantId(), job.path(), job.sourceId(), job.fromBeginning(), job.parser());
      if (job.lane() == Lane.REPLAY) {
        request = request.onReplayLane(replay.readLimit());
      }
      Outcome outcome = pipeline.run(request, job.progress(), job.cancelFlag());
      job.finished(outcome == Outcome.CANCELLED ? State.CANCELLED : State.COMPLETED, null);
    } catch (Exception e) {
      job.finished(State.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      leases.release(job.tenantId(), job.sourceId(), job.id());
    }
  }

  private static String defaultSourceId(Path realPath) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(realPath.toString().getBytes(StandardCharsets.UTF_8));
      return "file-" + HexFormat.of().formatHex(digest, 0, SOURCE_ID_HASH_BYTES);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void evictOldJobs(String tenantId) {
    List<IngestJob> own =
        jobs.values().stream().filter(j -> j.tenantId().equals(tenantId)).toList();
    int excess = own.size() - MAX_RETAINED_JOBS_PER_TENANT + 1;
    if (excess <= 0) {
      return;
    }
    own.stream()
        .filter(j -> j.state().isFinished())
        .sorted(Comparator.comparing(IngestJob::finishedAt))
        .limit(excess)
        .toList()
        .forEach(j -> jobs.remove(j.id()));
  }
}
