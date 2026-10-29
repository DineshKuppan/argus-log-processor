package dev.dinesh.argus.ingest;

import dev.dinesh.argus.config.ReplayProperties;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The replay lane's slots and read budget ({@code P3_PLAN.md} D8), shared by replay file jobs and
 * export jobs. Its threads run at minimum priority; that is advisory (Linux ignores Java thread
 * priorities by default), so the read budget is what keeps a replay from starving live ingest.
 */
@Slf4j
@Component
public class ReplayLane {

  private final ThreadPoolExecutor executor;
  private final ByteRateLimiter readLimit;
  private final ReplayProperties props;

  public ReplayLane(ReplayProperties props) {
    this.props = props;
    this.readLimit = new ByteRateLimiter(props.bytesPerSecond().toBytes());
    AtomicInteger threads = new AtomicInteger();
    // No queue: a replay beyond the slots is rejected (429), never buffered.
    this.executor =
        new ThreadPoolExecutor(
            props.maxConcurrentJobs(),
            props.maxConcurrentJobs(),
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
              Thread t = new Thread(r, "argus-replay-" + threads.incrementAndGet());
              t.setDaemon(true);
              t.setPriority(Thread.MIN_PRIORITY);
              return t;
            });
  }

  public ReplayProperties props() {
    return props;
  }

  /** The budget every replay read draws from. */
  public ByteRateLimiter readLimit() {
    return readLimit;
  }

  public int maxConcurrentJobs() {
    return executor.getMaximumPoolSize();
  }

  /**
   * Starts {@code job} on a free slot.
   *
   * @throws RejectedExecutionException if every slot is busy
   */
  public void execute(Runnable job) {
    executor.execute(job);
  }

  @PreDestroy
  public void shutdown() throws InterruptedException {
    executor.shutdown();
    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
      log.warn("Replay jobs did not stop within 30s; checkpoints remain at the last commit");
    }
  }
}
