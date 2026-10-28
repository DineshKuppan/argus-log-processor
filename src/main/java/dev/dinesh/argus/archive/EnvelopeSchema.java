package dev.dinesh.argus.archive;

import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

/**
 * Parquet schema of the archive: one row per {@link dev.dinesh.argus.model.LogEnvelope}. Column
 * names are snake_case, which Athena and BigQuery accept without quoting. Changing a column is a
 * schema migration for every query engine reading the archive, so add columns, never rename them.
 *
 * <p>There is no {@code event_id} column: the id is a hash of {@code tenant_id}, {@code source_id},
 * {@code source_generation} and {@code source_offset} ({@link
 * dev.dinesh.argus.pipeline.EnvelopeFactory#eventId(String, String, int, long)}), which the row
 * already holds, and a random-looking 36-character string would be three quarters of the archive
 * (measured: 20.5 of 27.2 bytes per row). Those four columns are the row's unique key.
 */
public final class EnvelopeSchema {

  public static final String TENANT_ID = "tenant_id";
  public static final String TIMESTAMP = "timestamp";
  public static final String OBSERVED_TIMESTAMP = "observed_timestamp";
  public static final String SEVERITY_TEXT = "severity_text";
  public static final String SEVERITY_NUMBER = "severity_number";
  public static final String BODY = "body";
  public static final String TRACE_ID = "trace_id";
  public static final String SPAN_ID = "span_id";
  public static final String RESOURCE = "resource";
  public static final String ATTRIBUTES = "attributes";
  public static final String SOURCE_ID = "source_id";
  public static final String SOURCE_GENERATION = "source_generation";
  public static final String SOURCE_OFFSET = "source_offset";
  public static final String SOURCE_FORMAT = "source_format";

  /** Name of a map's repeated group, as the Parquet spec's MAP layout requires. */
  static final String KEY_VALUE = "key_value";

  static final String KEY = "key";
  static final String VALUE = "value";

  private static final String STRING_MAP =
      " (MAP) { repeated group key_value { required binary key (STRING);"
          + " optional binary value (STRING); } }";

  public static final MessageType SCHEMA =
      MessageTypeParser.parseMessageType(
          "message log_envelope {"
              + " required binary tenant_id (STRING);"
              + " optional int64 timestamp (TIMESTAMP(MICROS,true));"
              + " required int64 observed_timestamp (TIMESTAMP(MICROS,true));"
              + " optional binary severity_text (STRING);"
              + " required int32 severity_number;"
              + " optional binary body (STRING);"
              + " optional binary trace_id (STRING);"
              + " optional binary span_id (STRING);"
              + " optional group resource"
              + STRING_MAP
              + " optional group attributes"
              + STRING_MAP
              + " required binary source_id (STRING);"
              + " required int32 source_generation;"
              + " required int64 source_offset;"
              + " optional binary source_format (STRING);"
              + " }");

  private EnvelopeSchema() {}
}
