package dev.dinesh.argus.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.archive.FileSystemObjectStore;
import dev.dinesh.argus.archive.WalSegments;
import dev.dinesh.argus.archive.ZstdCodecFactory;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.ArchiveProperties;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.seeder.DataSeeder;
import dev.dinesh.argus.sink.LocalVolumeSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.springframework.util.unit.DataSize;

/**
 * Generates a large log, ingests it with production batch/segment settings, archives the output to
 * Parquet (P2), then prints one summary line. Run in a separate JVM by {@link LargeFileIngestIT} so
 * its heap limit is enforced.
 *
 * <p>Args: {@code <workDir> <inputSize> [none|zstd]} (WAL compression).
 */
public final class LargeFileIngestRunner {

  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    long inputBytes = DataSize.parse(args[1]).toBytes();
    LocalSinkProperties.Compression compression =
        args.length > 2
            ? LocalSinkProperties.Compression.valueOf(args[2].toUpperCase(java.util.Locale.ROOT))
            : LocalSinkProperties.Compression.NONE;
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
                new LocalSinkProperties(work.resolve("out"), DataSize.ofBytes(0), compression),
                mapper),
            new FileCheckpointStore(props, mapper));

    HeapSampler heap = HeapSampler.start();
    IngestProgress progress = new IngestProgress();
    long started = System.nanoTime();
    IngestPipeline.Outcome outcome =
        pipeline.run(
            new IngestPipeline.Request("default", input, "load", false, null),
            progress,
            new AtomicBoolean());
    double seconds = (System.nanoTime() - started) / 1e9;
    long ingestPeakHeap = heap.peakUsedBytes();

    // Then archive everything ingested, as P2's Archiver does in the background, with the
    // production row group size.
    Path wal = work.resolve("out");
    long walBytes = sizeOf(wal, ".ndjson") + sizeOf(wal, ".ndjson.zst");
    Path archiveRoot = work.resolve("archive");
    ArchiveProperties archiveProps =
        new ArchiveProperties(
            ArchiveProperties.Store.FILESYSTEM,
            null,
            true,
            null,
            Files.createDirectories(work.resolve("staging")),
            null,
            null,
            null,
            new ArchiveProperties.FileSystem(archiveRoot),
            null,
            null);
    long archiveStarted = System.nanoTime();
    Archiver.Pass pass =
        new Archiver(
                new FileSystemObjectStore(archiveRoot), new WalSegments(wal), archiveProps, mapper)
            .runOnce();
    double archiveSeconds = (System.nanoTime() - archiveStarted) / 1e9;
    heap.stop();
    long parquetBytes = sizeOf(archiveRoot, ".parquet");
    long inputSize = Files.size(input);

    System.out.printf(
        "RESULT outcome=%s generatedLines=%d lines=%d rejected=%d segments=%d committedOffset=%d"
            + " inputBytes=%d seconds=%.1f linesPerSec=%.0f peakHeapMB=%d maxHeapMB=%d"
            + " archived=%d archiveFailed=%d archivedRows=%d walBytes=%d parquetBytes=%d"
            + " walLeftBytes=%d archiveSeconds=%.1f archiveLinesPerSec=%.0f"
            + " compressionVsInput=%.1f compressionVsNdjson=%.1f archivePeakHeapMB=%d"
            + " walCompression=%s walVsInput=%.2f%n",
        outcome,
        generatedLines,
        progress.lines(),
        progress.rejected(),
        progress.segmentsCommitted(),
        progress.committedOffset(),
        inputSize,
        seconds,
        progress.lines() / seconds,
        ingestPeakHeap / (1024 * 1024),
        Runtime.getRuntime().maxMemory() / (1024 * 1024),
        pass.archived(),
        pass.failed(),
        parquetRows(archiveRoot),
        walBytes,
        parquetBytes,
        sizeOf(wal, ".ndjson") + sizeOf(wal, ".ndjson.zst"),
        archiveSeconds,
        progress.lines() / archiveSeconds,
        (double) inputSize / parquetBytes,
        (double) walBytes / parquetBytes,
        heap.peakUsedBytes() / (1024 * 1024),
        compression,
        (double) walBytes / inputSize);
  }

  private static long sizeOf(Path dir, String suffix) throws IOException {
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(p -> p.toString().endsWith(suffix))
          .mapToLong(p -> p.toFile().length())
          .sum();
    }
  }

  /** Row counts from the footers only; Hadoop-free, as the archive is written. */
  private static long parquetRows(Path dir) throws IOException {
    ParquetReadOptions options =
        ParquetReadOptions.builder(new PlainParquetConfiguration())
            .withCodecFactory(new ZstdCodecFactory(ZstdCodecFactory.DEFAULT_LEVEL))
            .build();
    long rows = 0;
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".parquet")).toList()) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), options)) {
          rows += reader.getRecordCount();
        }
      }
    }
    return rows;
  }
}
