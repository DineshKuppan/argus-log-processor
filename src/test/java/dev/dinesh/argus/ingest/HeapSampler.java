package dev.dinesh.argus.ingest;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples total heap in use. Summing each pool's own peak would overstate it, because pools peak at
 * different moments.
 */
final class HeapSampler {
  private static final long INTERVAL_MS = 20;
  private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
  private final AtomicLong peak = new AtomicLong();
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread thread = new Thread(this::sample, "heap-sampler");

  static HeapSampler start() {
    HeapSampler sampler = new HeapSampler();
    sampler.thread.setDaemon(true);
    sampler.thread.start();
    return sampler;
  }

  private void sample() {
    while (running.get()) {
      peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
      try {
        Thread.sleep(INTERVAL_MS);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  void stop() throws InterruptedException {
    running.set(false);
    thread.join();
  }

  long peakUsedBytes() {
    return peak.get();
  }
}
