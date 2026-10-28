package dev.dinesh.argus.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.archive.FileSystemObjectStore;
import dev.dinesh.argus.archive.ObjectStore;
import dev.dinesh.argus.archive.WalSegments;
import dev.dinesh.argus.archive.WatermarkWalCapacity;
import dev.dinesh.argus.archive.ZstdCodecFactory;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.ArchiveProperties;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.config.WalProperties;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.seeder.DataSeeder;
import dev.dinesh.argus.sink.LocalVolumeSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.springframework.util.unit.DataSize;

/**
 * T3, scaled down: ingests a large file while the archive store is down, then brings the store
 * back. Prints one summary line. Run in a separate JVM by {@link WalBackpressureIT} so its heap
 * limit is enforced.
 *
 * <p>Args: {@code <workDir> <inputSize> <outageSeconds>}. Segments are 25MB of source and the WAL
 * is capped at 4 compressed segments' worth, so the outage fills it within seconds.
 */
public final class WalBackpressureRunner {

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    long inputBytes = DataSize.parse(args[1]).toBytes();
    Duration outage = Duration.ofSeconds(Long.parseLong(args[2]));
    Path input = Files.createDirectories(work.resolve("in")).resolve("big.log");
    long generatedLines = DataSeeder.generateNginxLogs(input, inputBytes, 42L);

    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    IngestProperties props =
        new IngestProperties(
            0, null, DataSize.ofMegabytes(25), 0, 0, null, 1, List.of(), work.resolve("cp"), null);
    Path walRoot = work.resolve("wal");
    WalSegments wal = new WalSegments(walRoot);
    WalProperties limits = new WalProperties(DataSize.ofMegabytes(40), 0.8, 0.6);
    WatermarkWalCapacity capacity = new WatermarkWalCapacity(wal, limits);

    // The store is down until the outage ends.
    long storeBackAt = System.nanoTime() + outage.toNanos();
    Path archiveRoot = work.resolve("archive");
    ObjectStore real = new FileSystemObjectStore(archiveRoot);
    AtomicLong failedPuts = new AtomicLong();
    ObjectStore flaky =
        new ObjectStore() {
          @Override
          public String describe() {
            return real.describe();
          }

          @Override
          public void put(String key, Path file) throws IOException {
            if (System.nanoTime() < storeBackAt) {
              failedPuts.incrementAndGet();
              throw new IOException("503 Service Unavailable (simulated outage)");
            }
            real.put(key, file);
          }

          @Override
          public List<String> list(String prefix) throws IOException {
            return real.list(prefix);
          }

          @Override
          public void delete(String key) throws IOException {
            real.delete(key);
          }
        };
    Archiver archiver =
        new Archiver(
            flaky,
            wal,
            new ArchiveProperties(
                ArchiveProperties.Store.FILESYSTEM,
                null,
                true,
                Duration.ofMillis(200),
                Files.createDirectories(work.resolve("staging")),
                null,
                Duration.ofMillis(100),
                Duration.ofSeconds(2),
                new ArchiveProperties.FileSystem(archiveRoot),
                null,
                null),
            mapper);
    IngestPipeline pipeline =
        new IngestPipeline(
            props,
            new ParserRegistry(),
            new LocalVolumeSink(
                new LocalSinkProperties(
                    walRoot, DataSize.ofBytes(0), LocalSinkProperties.Compression.ZSTD),
                mapper),
            new FileCheckpointStore(props, mapper),
            capacity);

    HeapSampler heap = HeapSampler.start();
    AtomicLong maxPendingBytes = new AtomicLong();
    AtomicLong maxSegmentBytes = new AtomicLong();
    AtomicBoolean sampling = new AtomicBoolean(true);
    Thread walSampler =
        new Thread(
            () -> {
              while (sampling.get()) {
                maxPendingBytes.accumulateAndGet(pendingBytes(wal), Math::max);
                maxSegmentBytes.accumulateAndGet(largestSegmentBytes(wal), Math::max);
                sleep(100);
              }
            },
            "wal-sampler");
    walSampler.setDaemon(true);
    walSampler.start();
    archiver.start();

    IngestProgress progress = new IngestProgress();
    long started = System.nanoTime();
    IngestPipeline.Outcome outcome =
        pipeline.run(
            new IngestPipeline.Request("default", input, "t3", false, null),
            progress,
            new AtomicBoolean());
    double ingestSeconds = (System.nanoTime() - started) / 1e9;
    // Then wait for the archive to drain the WAL.
    long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
    while (pendingBytes(wal) > 0 && System.nanoTime() < deadline) {
      sleep(200);
    }
    double totalSeconds = (System.nanoTime() - started) / 1e9;
    archiver.stop();
    sampling.set(false);
    walSampler.join();
    heap.stop();

    System.out.printf(
        "RESULT outcome=%s generatedLines=%d lines=%d archivedRows=%d segments=%d"
            + " outageSeconds=%d failedPuts=%d pausedMs=%d ingestSeconds=%.1f totalSeconds=%.1f"
            + " highWatermarkBytes=%d maxPendingBytes=%d segmentBytesMax=%d walLeftBytes=%d"
            + " peakHeapMB=%d maxHeapMB=%d%n",
        outcome,
        generatedLines,
        progress.lines(),
        parquetRows(archiveRoot),
        progress.segmentsCommitted(),
        outage.toSeconds(),
        failedPuts.get(),
        progress.pausedMs(),
        ingestSeconds,
        totalSeconds,
        limits.highBytes(),
        maxPendingBytes.get(),
        maxSegmentBytes.get(),
        pendingBytes(wal),
        heap.peakUsedBytes() / (1024 * 1024),
        Runtime.getRuntime().maxMemory() / (1024 * 1024));
  }

  private static long pendingBytes(WalSegments wal) {
    long total = 0;
    try {
      for (WalSegments.Segment s : wal.pending()) {
        total += s.file().toFile().length();
      }
    } catch (IOException e) {
      // the next sample sees a consistent WAL
    }
    return total;
  }

  private static long largestSegmentBytes(WalSegments wal) {
    long max = 0;
    try {
      for (WalSegments.Segment s : wal.pending()) {
        max = Math.max(max, s.file().toFile().length());
      }
    } catch (IOException e) {
      // the next sample sees a consistent WAL
    }
    return max;
  }

  private static long parquetRows(Path dir) throws IOException {
    ParquetReadOptions options =
        ParquetReadOptions.builder(new PlainParquetConfiguration())
            .withCodecFactory(new ZstdCodecFactory(ZstdCodecFactory.DEFAULT_LEVEL))
            .build();
    long rows = 0;
    if (!Files.exists(dir)) {
      return 0;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".parquet")).toList()) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), options)) {
          rows += reader.getRecordCount();
        }
      }
    }
    return rows;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
