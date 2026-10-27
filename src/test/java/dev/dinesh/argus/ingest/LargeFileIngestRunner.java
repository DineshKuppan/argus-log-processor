package dev.dinesh.argus.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.seeder.DataSeeder;
import dev.dinesh.argus.sink.LocalVolumeSink;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.util.unit.DataSize;

/**
 * Generates a large log and ingests it with production batch/segment settings, then prints one
 * summary line. Run in a separate JVM by {@link LargeFileIngestIT} so its heap limit is enforced.
 *
 * <p>Args: {@code <workDir> <inputSize>}.
 */
public final class LargeFileIngestRunner {

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    long inputBytes = DataSize.parse(args[1]).toBytes();
    Path input = Files.createDirectories(work.resolve("in")).resolve("big.log");
    long generatedLines = DataSeeder.generateNginxLogs(input, inputBytes, 42L);

    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    IngestProperties props =
        new IngestProperties(0, null, null, 0, 0, null, 1, List.of(), work.resolve("cp"), null);
    IngestPipeline pipeline =
        new IngestPipeline(
            props,
            new ParserRegistry(),
            new LocalVolumeSink(
                new LocalSinkProperties(work.resolve("out"), DataSize.ofBytes(0)), mapper),
            new FileCheckpointStore(props, mapper));

    HeapSampler heap = HeapSampler.start();
    IngestProgress progress = new IngestProgress();
    long started = System.nanoTime();
    IngestPipeline.Outcome outcome =
        pipeline.run(
            new IngestPipeline.Request(input, "load", false, null), progress, new AtomicBoolean());
    double seconds = (System.nanoTime() - started) / 1e9;
    heap.stop();

    System.out.printf(
        "RESULT outcome=%s generatedLines=%d lines=%d rejected=%d segments=%d committedOffset=%d"
            + " inputBytes=%d seconds=%.1f linesPerSec=%.0f peakHeapMB=%d maxHeapMB=%d%n",
        outcome,
        generatedLines,
        progress.lines(),
        progress.rejected(),
        progress.segmentsCommitted(),
        progress.committedOffset(),
        Files.size(input),
        seconds,
        progress.lines() / seconds,
        heap.peakUsedBytes() / (1024 * 1024),
        Runtime.getRuntime().maxMemory() / (1024 * 1024));
  }

  /**
   * Samples total heap in use. Summing each pool's own peak would overstate it, because pools peak
   * at different moments.
   */
  private static final class HeapSampler {
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
}
