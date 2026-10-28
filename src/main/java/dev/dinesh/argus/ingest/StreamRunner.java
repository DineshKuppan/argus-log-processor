package dev.dinesh.argus.ingest;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.ingest.IngestPipeline.Outcome;
import dev.dinesh.argus.ingest.IngestPipeline.Request;
import dev.dinesh.argus.source.StreamSpool;
import dev.dinesh.argus.source.StreamSpool.Pending;
import dev.dinesh.argus.source.StreamSpool.ReadPosition;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Ingests push streams' spools with the ordinary pipeline ({@code P3_PLAN.md} D1). Every {@link
 * #SCAN_INTERVAL} it starts a run for each stream that is due (a segment's worth waiting, a sealed
 * spool file waiting, or a line that has waited {@code flush-interval}), on at most {@code
 * max-concurrent-runs} threads; a due stream beyond those waits, its spool absorbing the backlog. A
 * run reads one spool file up to its acknowledged length, as source {@code streamId}, with the
 * spool's path as part of its identity, so each spool file is its own generation.
 */
@Slf4j
@Component
public class StreamRunner implements SmartLifecycle {

  static final Duration SCAN_INTERVAL = Duration.ofSeconds(1);

  /** After a failed run, or while a file job holds the source. */
  static final Duration RETRY_DELAY = Duration.ofSeconds(10);

  static final String OWNER = "stream-run";
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

  private final StreamRegistry streams;
  private final IngestPipeline pipeline;
  private final CheckpointStore checkpoints;
  private final SourceLeases leases;
  private final long segmentBytes;
  private final long flushIntervalNanos;
  private final int maxConcurrentRuns;
  private final LongSupplier nanoTime;
  private final AtomicBoolean stopping = new AtomicBoolean();
  private ThreadPoolExecutor runs;
  private ScheduledExecutorService scanner;
  private volatile boolean running;

  @Autowired
  public StreamRunner(
      StreamRegistry streams,
      IngestPipeline pipeline,
      CheckpointStore checkpoints,
      SourceLeases leases,
      PushProperties push,
      IngestProperties ingest) {
    this(streams, pipeline, checkpoints, leases, push, ingest, System::nanoTime);
  }

  StreamRunner(
      StreamRegistry streams,
      IngestPipeline pipeline,
      CheckpointStore checkpoints,
      SourceLeases leases,
      PushProperties push,
      IngestProperties ingest,
      LongSupplier nanoTime) {
    this.streams = streams;
    this.pipeline = pipeline;
    this.checkpoints = checkpoints;
    this.leases = leases;
    this.segmentBytes = ingest.segmentBytes().toBytes();
    this.flushIntervalNanos = push.flushInterval().toNanos();
    this.maxConcurrentRuns = push.maxConcurrentRuns();
    this.nanoTime = nanoTime;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    stopping.set(false);
    AtomicInteger threads = new AtomicInteger();
    // Unbounded queue, but a stream is queued at most once, so it never holds more than the
    // number of streams.
    runs =
        new ThreadPoolExecutor(
            maxConcurrentRuns,
            maxConcurrentRuns,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> daemon(r, "argus-stream-run-" + threads.incrementAndGet()));
    scanner = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "argus-stream-scan"));
    scanner.scheduleWithFixedDelay(
        this::scanQuietly,
        SCAN_INTERVAL.toMillis(),
        SCAN_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
    running = true;
    log.info(
        "Stream runner started maxConcurrentRuns={} flushIntervalMs={}",
        maxConcurrentRuns,
        TimeUnit.NANOSECONDS.toMillis(flushIntervalNanos));
  }

  /** Cancels running runs at their next batch; their checkpoints stay at the last commit. */
  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    stopping.set(true);
    scanner.shutdownNow();
    runs.shutdown();
    try {
      if (!runs.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        log.warn("Stream runs did not stop within {}s", STOP_TIMEOUT.toSeconds());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Starts a run for every due stream that has none queued or running. */
  void scan() {
    for (PushStream stream : streams.all()) {
      if (stopping.get()) {
        return;
      }
      if (!due(stream) || !stream.scheduled.compareAndSet(false, true)) {
        continue;
      }
      try {
        runs.execute(
            () -> {
              try {
                runNow(stream);
              } finally {
                stream.scheduled.set(false);
              }
            });
      } catch (RejectedExecutionException e) {
        stream.scheduled.set(false);
        return; // stopping
      }
    }
  }

  boolean due(PushStream stream) {
    return nanoTime.getAsLong() - stream.retryNotBeforeNanos >= 0
        && stream.spool().due(segmentBytes, flushIntervalNanos);
  }

  /**
   * Runs the stream's oldest waiting spool file to its acknowledged length, on the calling thread.
   *
   * @return the run's outcome, or empty if there was nothing to run or the source was busy
   */
  Optional<Outcome> runNow(PushStream stream) {
    String tenantId = stream.tenantId();
    String streamId = stream.streamId();
    Optional<String> owner = leases.tryAcquire(tenantId, streamId, OWNER);
    if (owner.isPresent()) {
      log.info("Stream {} run deferred: source busy with {}", streamId, owner.get());
      retryLater(stream);
      return Optional.empty();
    }
    StreamSpool spool = stream.spool();
    Pending claim = null;
    Outcome outcome = null;
    try {
      Optional<Pending> pending = spool.claim();
      if (pending.isEmpty()) {
        return Optional.empty();
      }
      claim = pending.get();
      outcome = run(stream, claim);
      return Optional.ofNullable(outcome);
    } finally {
      if (claim != null && outcome != Outcome.COMPLETED) {
        spool.release(claim);
      }
      try {
        // Whatever the outcome, the checkpoint says how far the stream got.
        Optional<ReadPosition> read =
            checkpoints.load(tenantId, streamId).flatMap(ReadPosition::of);
        if (read.isPresent()) {
          spool.markRead(read.get());
        }
      } catch (IOException | RuntimeException e) {
        log.warn("Stream {}: could not update its spool after a run: {}", streamId, e.toString());
      } finally {
        leases.release(tenantId, streamId, OWNER);
      }
    }
  }

  /** Null if the run failed (recorded on the stream, retried after {@link #RETRY_DELAY}). */
  private Outcome run(PushStream stream, Pending claim) {
    IngestProgress progress = new IngestProgress();
    Instant startedAt = Instant.now();
    stream.runs.incrementAndGet();
    Outcome outcome = null;
    String error = null;
    try {
      outcome =
          pipeline.run(
              new Request(
                  stream.tenantId(),
                  claim.path(),
                  stream.streamId(),
                  false,
                  stream.parser(),
                  claim.endOffset(),
                  true),
              progress,
              stopping);
      return outcome;
    } catch (Exception e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
      retryLater(stream);
      return null;
    } finally {
      stream.lastRun =
          new PushStream.Run(
              outcome == null ? "FAILED" : outcome.name(),
              error,
              claim.spool(),
              startedAt,
              Instant.now(),
              progress);
    }
  }

  private void retryLater(PushStream stream) {
    stream.retryNotBeforeNanos = nanoTime.getAsLong() + RETRY_DELAY.toNanos();
  }

  private void scanQuietly() {
    try {
      scan();
    } catch (RuntimeException e) {
      log.error("Stream scan failed", e);
    }
  }

  private static Thread daemon(Runnable r, String name) {
    Thread t = new Thread(r, name);
    t.setDaemon(true);
    return t;
  }
}
