package dev.dinesh.argus.archive;

import static dev.dinesh.argus.archive.EnvelopeSchema.SCHEMA;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.model.LogEnvelope;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.parquet.conf.ParquetConfiguration;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.RecordConsumer;

/**
 * Writes {@link LogEnvelope}s to one local Parquet file ({@link EnvelopeSchema}, ZSTD via {@link
 * ZstdCodecFactory}). Uses no Hadoop {@code FileSystem} or {@code Configuration}. Memory: one row
 * group is buffered on the heap before it is written, so {@code rowGroupBytes} bounds what an open
 * writer holds.
 *
 * <p>Not thread-safe.
 */
public final class EnvelopeParquetWriter implements Closeable {

  private static final ZstdCodecFactory CODECS =
      new ZstdCodecFactory(ZstdCodecFactory.DEFAULT_LEVEL);

  /**
   * Attribute that keeps an event time the {@code timestamp} column can't hold (beyond ±292,000
   * years in microseconds), so such a line is archived with a null timestamp instead of failing.
   */
  public static final String UNARCHIVABLE_TIMESTAMP_ATTRIBUTE = "argus.timestamp";

  private static final Instant MIN_MICROS =
      Instant.EPOCH.plus(Long.MIN_VALUE / 1_000_000 + 1, ChronoUnit.SECONDS);
  private static final Instant MAX_MICROS =
      Instant.EPOCH.plus(Long.MAX_VALUE / 1_000_000 - 1, ChronoUnit.SECONDS);

  private final ParquetWriter<LogEnvelope> writer;
  private long rows;

  private EnvelopeParquetWriter(ParquetWriter<LogEnvelope> writer) {
    this.writer = writer;
  }

  /** Creates (or replaces) {@code file}. */
  public static EnvelopeParquetWriter open(Path file, long rowGroupBytes, ObjectMapper mapper)
      throws IOException {
    return new EnvelopeParquetWriter(
        new Builder(new LocalOutputFile(file), mapper)
            .withConf(new PlainParquetConfiguration())
            .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
            .withCompressionCodec(CompressionCodecName.ZSTD)
            .withCodecFactory(CODECS)
            .withRowGroupSize(rowGroupBytes)
            .withDictionaryEncoding(true)
            .build());
  }

  public void write(LogEnvelope envelope) throws IOException {
    writer.write(envelope);
    rows++;
  }

  /**
   * The event time as the archive stores it: null when there is none or when the {@code timestamp}
   * column can't hold it. Partitions use this too, so a row's {@code dt}/{@code hour} always
   * matches its column.
   */
  public static Instant archivedTimestamp(LogEnvelope envelope) {
    Instant t = envelope.timestamp();
    return t == null || t.isBefore(MIN_MICROS) || t.isAfter(MAX_MICROS) ? null : t;
  }

  public long rows() {
    return rows;
  }

  /** Writes the last row group and the footer; the file is complete only after this. */
  @Override
  public void close() throws IOException {
    writer.close();
  }

  /** Microseconds since the epoch: the precision Athena and BigQuery timestamps have. */
  static long micros(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
  }

  private static final class Builder extends ParquetWriter.Builder<LogEnvelope, Builder> {

    private final ObjectMapper mapper;

    Builder(OutputFile file, ObjectMapper mapper) {
      super(file);
      this.mapper = mapper;
    }

    @Override
    protected Builder self() {
      return this;
    }

    @Override
    protected WriteSupport<LogEnvelope> getWriteSupport(ParquetConfiguration conf) {
      return new EnvelopeWriteSupport(mapper);
    }

    @Override
    protected WriteSupport<LogEnvelope> getWriteSupport(org.apache.hadoop.conf.Configuration conf) {
      return new EnvelopeWriteSupport(mapper);
    }
  }

  /** Maps one envelope to one row; null fields and empty maps are left out (Parquet nulls). */
  private static final class EnvelopeWriteSupport extends WriteSupport<LogEnvelope> {

    private final ObjectMapper mapper;
    private RecordConsumer out;

    EnvelopeWriteSupport(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    @Override
    public WriteContext init(ParquetConfiguration conf) {
      return new WriteContext(SCHEMA, Map.of());
    }

    @Override
    public WriteContext init(org.apache.hadoop.conf.Configuration conf) {
      return new WriteContext(SCHEMA, Map.of());
    }

    @Override
    public void prepareForWrite(RecordConsumer recordConsumer) {
      this.out = recordConsumer;
    }

    @Override
    public void write(LogEnvelope e) {
      LogEnvelope.Source source = e.source();
      Instant timestamp = archivedTimestamp(e);
      Map<String, Object> attributes = e.attributes();
      if (timestamp == null && e.timestamp() != null) {
        attributes = new LinkedHashMap<>(attributes != null ? attributes : Map.of());
        attributes.put(UNARCHIVABLE_TIMESTAMP_ATTRIBUTE, e.timestamp().toString());
      }
      out.startMessage();
      string(EnvelopeSchema.TENANT_ID, e.tenantId());
      if (timestamp != null) {
        int64(EnvelopeSchema.TIMESTAMP, micros(timestamp));
      }
      int64(EnvelopeSchema.OBSERVED_TIMESTAMP, micros(e.observedTimestamp()));
      string(EnvelopeSchema.SEVERITY_TEXT, e.severityText());
      int32(EnvelopeSchema.SEVERITY_NUMBER, e.severityNumber());
      string(EnvelopeSchema.BODY, e.body());
      string(EnvelopeSchema.TRACE_ID, e.traceId());
      string(EnvelopeSchema.SPAN_ID, e.spanId());
      map(EnvelopeSchema.RESOURCE, e.resource());
      map(EnvelopeSchema.ATTRIBUTES, attributes);
      string(EnvelopeSchema.SOURCE_ID, source.id());
      int32(EnvelopeSchema.SOURCE_GENERATION, source.generation());
      int64(EnvelopeSchema.SOURCE_OFFSET, source.offset());
      string(EnvelopeSchema.SOURCE_FORMAT, source.format());
      out.endMessage();
    }

    private void string(String field, String value) {
      if (value == null) {
        return;
      }
      int index = SCHEMA.getFieldIndex(field);
      out.startField(field, index);
      out.addBinary(Binary.fromString(value));
      out.endField(field, index);
    }

    private void int32(String field, int value) {
      int index = SCHEMA.getFieldIndex(field);
      out.startField(field, index);
      out.addInteger(value);
      out.endField(field, index);
    }

    private void int64(String field, long value) {
      int index = SCHEMA.getFieldIndex(field);
      out.startField(field, index);
      out.addLong(value);
      out.endField(field, index);
    }

    private void map(String field, Map<String, ?> map) {
      if (map == null || map.isEmpty()) {
        return;
      }
      int index = SCHEMA.getFieldIndex(field);
      out.startField(field, index);
      out.startGroup();
      out.startField(EnvelopeSchema.KEY_VALUE, 0);
      for (Map.Entry<String, ?> entry : map.entrySet()) {
        out.startGroup();
        out.startField(EnvelopeSchema.KEY, 0);
        out.addBinary(Binary.fromString(entry.getKey()));
        out.endField(EnvelopeSchema.KEY, 0);
        String value = text(entry.getValue());
        if (value != null) {
          out.startField(EnvelopeSchema.VALUE, 1);
          out.addBinary(Binary.fromString(value));
          out.endField(EnvelopeSchema.VALUE, 1);
        }
        out.endGroup();
      }
      out.endField(EnvelopeSchema.KEY_VALUE, 0);
      out.endGroup();
      out.endField(field, index);
    }

    /** Strings as they are; anything else (numbers, booleans, nested JSON) as its JSON text. */
    private String text(Object value) {
      if (value == null || value instanceof String) {
        return (String) value;
      }
      try {
        return mapper.writeValueAsString(value);
      } catch (JsonProcessingException ex) {
        return String.valueOf(value);
      }
    }
  }
}
