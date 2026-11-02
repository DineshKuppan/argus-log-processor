package dev.dinesh.argus.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.checkpoint.FileCheckpointStore;
import dev.dinesh.argus.config.FramingProperties;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.sink.WalCapacity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.util.unit.DataSize;

/**
 * Ingests one file with a given framing and prints one {@code RESULT} line. Run in a separate JVM
 * by {@link FramingIT}, so its heap limit is enforced and it can be killed.
 *
 * <p>Args: {@code <input> <workDir> <framing> <segmentSize>}. Re-running with the same work
 * directory resumes from its checkpoint.
 */
public final class FramingLoadRunner {

  public static void main(String[] args) throws Exception {
    Path input = Path.of(args[0]);
    Path work = Path.of(args[1]);
    String framing = args[2];
    DataSize segment = DataSize.parse(args[3]);
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    IngestProperties props =
        new IngestProperties(0, null, segment, 0, 0, null, 1, List.of(), work.resolve("cp"), null);
    IngestPipeline pipeline =
        new IngestPipeline(
            props,
            new ParserRegistry(),
            new LocalVolumeSink(
                new LocalSinkProperties(work.resolve("out"), DataSize.ofBytes(0)), mapper),
            new FileCheckpointStore(props, mapper),
            WalCapacity.UNBOUNDED,
            ArgusMetrics.noop(),
            null,
            null,
            // The file is complete: a trace at its end is ingested, not held back.
            new FramingProperties(framing, null, null, Duration.ZERO));

    HeapSampler heap = HeapSampler.start();
    IngestProgress progress = new IngestProgress();
    long started = System.nanoTime();
    IngestPipeline.Outcome outcome =
        pipeline.run(
            new IngestPipeline.Request("default", input, "app", false, null),
            progress,
            new AtomicBoolean());
    double seconds = (System.nanoTime() - started) / 1e9;
    heap.stop();
    long bytes = progress.committedOffset() - progress.startOffset();
    System.out.printf(
        "RESULT outcome=%s framing=%s records=%d multilineRecords=%d unparsed=%d rejected=%d"
            + " truncated=%d segments=%d startOffset=%d committedOffset=%d resumeReason=%s"
            + " seconds=%.2f mbPerSec=%.1f recordsPerSec=%.0f peakHeapMB=%d maxHeapMB=%d%n",
        outcome,
        framing,
        progress.lines(),
        progress.multilineRecords(),
        progress.unparsed(),
        progress.rejected(),
        progress.truncated(),
        progress.segmentsCommitted(),
        progress.startOffset(),
        progress.committedOffset(),
        progress.resumeReason(),
        seconds,
        bytes / seconds / (1024 * 1024),
        progress.lines() / seconds,
        heap.peakUsedBytes() / (1024 * 1024),
        Runtime.getRuntime().maxMemory() / (1024 * 1024));
  }
}
