package dev.dinesh.argus.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.pipeline.EnvelopeFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvelopeParquetWriterTest {

  private static final long ROW_GROUP = 1024 * 1024;

  @TempDir Path dir;

  private final ObjectMapper mapper = new ObjectMapper();

  private static LogEnvelope envelope(
      Instant timestamp, Map<String, Object> attributes, Map<String, String> resource) {
    return new LogEnvelope(
        "id-1",
        "acme",
        timestamp,
        Instant.parse("2026-10-28T09:00:00.000001999Z"),
        "WARN",
        13,
        "GET /x HTTP/1.1",
        "4bf92f3577b34da6a3ce929d0e0e4736",
        "00f067aa0ba902b7",
        resource,
        attributes,
        new LogEnvelope.Source("app", 2, 4096, "nginx_access"));
  }

  private List<Group> writeAndRead(LogEnvelope... envelopes) throws IOException {
    Path file = dir.resolve("out.parquet");
    try (EnvelopeParquetWriter writer = EnvelopeParquetWriter.open(file, ROW_GROUP, mapper)) {
      for (LogEnvelope e : envelopes) {
        writer.write(e);
      }
      assertEquals(envelopes.length, writer.rows());
    }
    return ParquetTestFiles.rows(file);
  }

  @Test
  void writesEveryFieldOfTheEnvelope() throws IOException {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("ip", "10.0.0.12");
    attributes.put("status", 403);
    attributes.put("argus.unparsed", true);
    attributes.put("nested", Map.of("a", List.of(1, 2)));
    attributes.put("missing", null);

    Group row =
        writeAndRead(
                envelope(
                    Instant.parse("2025-10-09T01:11:40.123456789Z"),
                    attributes,
                    Map.of("service.name", "checkout")))
            .get(0);

    assertEquals("acme", row.getString(EnvelopeSchema.TENANT_ID, 0));
    // Truncated to microseconds.
    assertEquals(1_759_972_300_123_456L, row.getLong(EnvelopeSchema.TIMESTAMP, 0));
    assertEquals(1_793_178_000_000_001L, row.getLong(EnvelopeSchema.OBSERVED_TIMESTAMP, 0));
    assertEquals("WARN", row.getString(EnvelopeSchema.SEVERITY_TEXT, 0));
    assertEquals(13, row.getInteger(EnvelopeSchema.SEVERITY_NUMBER, 0));
    assertEquals("GET /x HTTP/1.1", row.getString(EnvelopeSchema.BODY, 0));
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", row.getString(EnvelopeSchema.TRACE_ID, 0));
    assertEquals("00f067aa0ba902b7", row.getString(EnvelopeSchema.SPAN_ID, 0));
    assertEquals(Map.of("service.name", "checkout"), map(row, EnvelopeSchema.RESOURCE));
    Map<String, String> expected = new HashMap<>();
    expected.put("ip", "10.0.0.12");
    expected.put("status", "403");
    expected.put("argus.unparsed", "true");
    expected.put("nested", "{\"a\":[1,2]}");
    expected.put("missing", null);
    assertEquals(expected, map(row, EnvelopeSchema.ATTRIBUTES));
    assertEquals("app", row.getString(EnvelopeSchema.SOURCE_ID, 0));
    assertEquals(2, row.getInteger(EnvelopeSchema.SOURCE_GENERATION, 0));
    assertEquals(4096, row.getLong(EnvelopeSchema.SOURCE_OFFSET, 0));
    assertEquals("nginx_access", row.getString(EnvelopeSchema.SOURCE_FORMAT, 0));
  }

  @Test
  void theEventIdIsNotStoredButCanBeDerivedFromTheRow() throws IOException {
    LogEnvelope e = envelope(Instant.EPOCH, null, null);
    String eventId = EnvelopeFactory.eventId("acme", "app", 2, 4096);
    LogEnvelope stored =
        new LogEnvelope(
            eventId,
            e.tenantId(),
            e.timestamp(),
            e.observedTimestamp(),
            e.severityText(),
            e.severityNumber(),
            e.body(),
            e.traceId(),
            e.spanId(),
            e.resource(),
            e.attributes(),
            e.source());

    Group row = writeAndRead(stored).get(0);

    assertFalse(EnvelopeSchema.SCHEMA.containsField("event_id"));
    assertEquals(
        eventId,
        EnvelopeFactory.eventId(
            row.getString(EnvelopeSchema.TENANT_ID, 0),
            row.getString(EnvelopeSchema.SOURCE_ID, 0),
            row.getInteger(EnvelopeSchema.SOURCE_GENERATION, 0),
            row.getLong(EnvelopeSchema.SOURCE_OFFSET, 0)));
  }

  @Test
  void leavesNullFieldsAndEmptyMapsOut() throws IOException {
    LogEnvelope bare =
        new LogEnvelope(
            "id-2",
            "acme",
            null,
            Instant.EPOCH,
            null,
            0,
            null,
            null,
            null,
            null,
            Map.of(),
            new LogEnvelope.Source("app", 0, 0, null));

    Group row = writeAndRead(bare).get(0);

    for (String field :
        List.of(
            EnvelopeSchema.TIMESTAMP,
            EnvelopeSchema.SEVERITY_TEXT,
            EnvelopeSchema.BODY,
            EnvelopeSchema.TRACE_ID,
            EnvelopeSchema.SPAN_ID,
            EnvelopeSchema.RESOURCE,
            EnvelopeSchema.ATTRIBUTES,
            EnvelopeSchema.SOURCE_FORMAT)) {
      assertEquals(0, row.getFieldRepetitionCount(field), field);
    }
    assertEquals(0, row.getInteger(EnvelopeSchema.SEVERITY_NUMBER, 0));
  }

  @Test
  void anEventTimeMicrosecondsCannotHoldIsArchivedAsNullAndKeptAsAnAttribute() throws IOException {
    // A JSON line's epoch -10^13 parses, but is ~317,000 years before 1970.
    Instant extreme = Instant.ofEpochSecond(-10_000_000_000_000L);
    LogEnvelope envelope = envelope(extreme, Map.of("k", "v"), null);

    assertNull(EnvelopeParquetWriter.archivedTimestamp(envelope));
    Group row = writeAndRead(envelope).get(0);

    assertEquals(0, row.getFieldRepetitionCount(EnvelopeSchema.TIMESTAMP));
    assertEquals(
        Map.of(
            "k", "v", EnvelopeParquetWriter.UNARCHIVABLE_TIMESTAMP_ATTRIBUTE, extreme.toString()),
        map(row, EnvelopeSchema.ATTRIBUTES));
    Instant normal = Instant.parse("2025-10-09T01:11:40Z");
    assertEquals(normal, EnvelopeParquetWriter.archivedTimestamp(envelope(normal, null, null)));
  }

  @Test
  void everyColumnIsZstdCompressedAcrossSeveralRowGroups() throws IOException {
    Path file = dir.resolve("big.parquet");
    try (EnvelopeParquetWriter writer = EnvelopeParquetWriter.open(file, 64 * 1024, mapper)) {
      for (int i = 0; i < 20_000; i++) {
        writer.write(
            envelope(
                Instant.ofEpochSecond(1_760_000_000L + i), Map.of("i", Integer.toString(i)), null));
      }
    }

    try (ParquetFileReader reader =
        ParquetFileReader.open(new LocalInputFile(file), ParquetTestFiles.OPTIONS)) {
      List<BlockMetaData> groups = reader.getRowGroups();
      assertTrue(groups.size() > 1, "expected several row groups, got " + groups.size());
      assertEquals(20_000, reader.getRecordCount());
      for (BlockMetaData group : groups) {
        for (ColumnChunkMetaData column : group.getColumns()) {
          assertEquals(CompressionCodecName.ZSTD, column.getCodec(), column.getPath().toString());
        }
      }
    }
  }

  private static Map<String, String> map(Group row, String field) {
    Group keyValues = row.getGroup(field, 0);
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keyValues.getFieldRepetitionCount(EnvelopeSchema.KEY_VALUE); i++) {
      Group entry = keyValues.getGroup(EnvelopeSchema.KEY_VALUE, i);
      map.put(
          entry.getString(EnvelopeSchema.KEY, 0),
          entry.getFieldRepetitionCount(EnvelopeSchema.VALUE) == 0
              ? null
              : entry.getString(EnvelopeSchema.VALUE, 0));
    }
    return map;
  }
}
