package dev.dinesh.argus.wal;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.util.unit.DataSize;

/** Writes real WAL segments with {@link LocalVolumeSink}, as ingest does. */
public final class WalFixture {

  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final LocalVolumeSink sink;

  public WalFixture(Path root) {
    this(root, LocalSinkProperties.Compression.NONE);
  }

  public WalFixture(Path root, LocalSinkProperties.Compression compression) {
    this.sink =
        new LocalVolumeSink(
            new LocalSinkProperties(root, DataSize.ofBytes(0), compression), MAPPER);
  }

  /** Commits {@code records} lines at offsets {@code start, start+10, …}; returns the file. */
  public Path segment(
      String tenant, String source, int gen, long start, int records, Instant firstTime)
      throws IOException {
    List<Instant> times = new ArrayList<>();
    for (int i = 0; i < records; i++) {
      times.add(firstTime == null ? null : firstTime.plusSeconds(i));
    }
    return segment(tenant, source, gen, start, times);
  }

  /** One line per event time (null: none), at offsets {@code start, start+10, …}. */
  public Path segment(String tenant, String source, int gen, long start, List<Instant> times)
      throws IOException {
    List<LogEnvelope> envelopes = new ArrayList<>();
    for (int i = 0; i < times.size(); i++) {
      long offset = start + 10L * i;
      envelopes.add(
          new LogEnvelope(
              tenant + "-" + source + "-" + gen + "-" + offset,
              tenant,
              times.get(i),
              Instant.parse("2026-10-28T09:00:00Z"),
              "INFO",
              9,
              "line " + i,
              null,
              null,
              null,
              Map.of("i", i),
              new LogEnvelope.Source(source, gen, offset, "json")));
    }
    long end = start + 10L * times.size();
    SegmentWriter writer = sink.open(tenant, source, gen, start);
    writer.write(new LogBatch(source, 0, 0, envelopes, start, end, true, 0, 0, 0));
    return Path.of(writer.commit(end));
  }
}
