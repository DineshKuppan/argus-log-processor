package dev.dinesh.argus.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.sink.LocalVolumeSink;
import dev.dinesh.argus.sink.LogSink.SegmentWriter;
import dev.dinesh.argus.source.LogBatch;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.util.unit.DataSize;

/** Writes real WAL segments with {@link LocalVolumeSink}, as ingest does. */
final class WalFixture {

  static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final LocalVolumeSink sink;

  WalFixture(Path root) {
    this.sink = new LocalVolumeSink(new LocalSinkProperties(root, DataSize.ofBytes(0)), MAPPER);
  }

  /** Commits {@code records} lines at offsets {@code start, start+10, …}; returns the file. */
  Path segment(String tenant, String source, int gen, long start, int records, Instant firstTime)
      throws IOException {
    List<LogEnvelope> envelopes =
        IntStream.range(0, records)
            .mapToObj(
                i ->
                    new LogEnvelope(
                        tenant + "-" + source + "-" + gen + "-" + (start + 10L * i),
                        tenant,
                        firstTime == null ? null : firstTime.plusSeconds(i),
                        Instant.parse("2026-10-28T09:00:00Z"),
                        "INFO",
                        9,
                        "line " + i,
                        null,
                        null,
                        null,
                        Map.of("i", i),
                        new LogEnvelope.Source(source, gen, start + 10L * i, "json")))
            .toList();
    long end = start + 10L * records;
    SegmentWriter writer = sink.open(tenant, source, gen, start);
    writer.write(new LogBatch(source, 0, 0, envelopes, start, end, true, 0, 0, 0));
    return Path.of(writer.commit(end));
  }
}
