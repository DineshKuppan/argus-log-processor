package dev.dinesh.argus.ingest;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.ingest.IngestJob.State;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.ingest.IngestPipeline.Request;
import dev.dinesh.argus.parser.LogParser;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.source.SourceOffset;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs ingest jobs on a fixed number of slots. Jobs beyond the slot count are rejected rather than
 * queued, so callers get immediate backpressure (HTTP 429) instead of unbounded memory growth.
 */
@Slf4j
@Service
public class IngestJobService {

  /** Finished jobs kept for status queries; older ones are evicted. */
  static final int MAX_RETAINED_JOBS = 200;

  private static final int SOURCE_ID_HASH_BYTES = 6;

  private final IngestPipeline pipeline;
  private final ParserRegistry parsers;
  private final List<Path> allowedRoots;
  private final ThreadPoolExecutor executor;
  private final Map<String, IngestJob> jobs = new ConcurrentHashMap<>();
  private final Map<String, String> activeJobBySource = new ConcurrentHashMap<>();

  public IngestJobService(IngestPipeline pipeline, ParserRegistry parsers, IngestProperties props) {
    this.pipeline = pipeline;
    this.parsers = parsers;
    this.allowedRoots = props.allowedRoots();
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

  /** Thrown when every job slot is busy; maps to HTTP 429. */
  public static class JobSlotsBusyException extends RuntimeException {
    JobSlotsBusyException(int slots) {
      super("All " + slots + " ingest job slots are busy; retry later");
    }
  }

  /** Thrown when the source already has a running job; maps to HTTP 409. */
  public static class SourceBusyException extends RuntimeException {
    SourceBusyException(String sourceId, String jobId) {
      super("Source " + sourceId + " already has an active job " + jobId);
    }
  }

  /**
   * Validates the request and starts a job.
   *
   * @throws NoSuchFileException if the file does not exist
   * @param format primary format, or null to detect it
   * @param pattern Logback layout for format {@code logback}
   * @throws IllegalArgumentException if the path is outside the allowed roots, not a regular file,
   *     the source id is invalid, or the format or pattern is not supported
   */
  public IngestJob submit(
      String rawPath,
      String requestedSourceId,
      boolean fromBeginning,
      String format,
      String pattern)
      throws IOException {
    if (rawPath == null || rawPath.isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    // Check the roots before existence, so a 404 never reveals whether a path outside them exists.
    Path path = Path.of(rawPath).toAbsolutePath().normalize();
    requireAllowed(path);
    if (!Files.exists(path)) {
      throw new NoSuchFileException(rawPath);
    }
    // Again after resolving symlinks, which may point outside the roots.
    Path realPath = path.toRealPath();
    requireAllowed(realPath);
    if (!Files.isRegularFile(realPath)) {
      throw new IllegalArgumentException("Not a regular file: " + rawPath);
    }
    String sourceId =
        requestedSourceId == null || requestedSourceId.isBlank()
            ? defaultSourceId(realPath)
            : SourceOffset.requireValidSourceId(requestedSourceId);
    LogParser parser = parsers.resolve(format, pattern).orElse(null);

    IngestJob job =
        new IngestJob(UUID.randomUUID().toString(), sourceId, realPath, fromBeginning, parser);
    String existing = activeJobBySource.putIfAbsent(sourceId, job.id());
    if (existing != null) {
      throw new SourceBusyException(sourceId, existing);
    }
    try {
      evictOldJobs();
      jobs.put(job.id(), job);
      executor.execute(() -> runJob(job));
    } catch (RejectedExecutionException e) {
      jobs.remove(job.id());
      activeJobBySource.remove(sourceId, job.id());
      throw new JobSlotsBusyException(executor.getMaximumPoolSize());
    }
    log.info("Ingest job {} submitted source={} path={}", job.id(), sourceId, realPath);
    return job;
  }

  public Optional<IngestJob> find(String jobId) {
    return Optional.ofNullable(jobs.get(jobId));
  }

  /** Number of jobs currently occupying a slot. */
  public int activeJobs() {
    return executor.getActiveCount();
  }

  /** Requests cancellation; the job stops at its next batch boundary. */
  public Optional<IngestJob> cancel(String jobId) {
    Optional<IngestJob> job = find(jobId);
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
      Outcome outcome =
          pipeline.run(
              new Request(job.path(), job.sourceId(), job.fromBeginning(), job.parser()),
              job.progress(),
              job.cancelFlag());
      job.finished(outcome == Outcome.CANCELLED ? State.CANCELLED : State.COMPLETED, null);
    } catch (Exception e) {
      job.finished(State.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      activeJobBySource.remove(job.sourceId(), job.id());
    }
  }

  /** {@code path} must be absolute and normalized; compared with each root as written and real. */
  private void requireAllowed(Path path) throws IOException {
    for (Path root : allowedRoots) {
      if (Files.isDirectory(root)
          && (path.startsWith(root.toRealPath())
              || path.startsWith(root.toAbsolutePath().normalize()))) {
        return;
      }
    }
    throw new IllegalArgumentException(
        "Path is outside the allowed ingest roots (argus.ingest.allowed-roots)");
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

  private void evictOldJobs() {
    int excess = jobs.size() - MAX_RETAINED_JOBS + 1;
    if (excess <= 0) {
      return;
    }
    jobs.values().stream()
        .filter(j -> j.state().isFinished())
        .sorted(Comparator.comparing(IngestJob::finishedAt))
        .limit(excess)
        .toList()
        .forEach(j -> jobs.remove(j.id()));
  }
}
