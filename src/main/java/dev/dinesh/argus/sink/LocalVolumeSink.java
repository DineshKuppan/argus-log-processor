package dev.dinesh.argus.sink;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.utils.DurableFiles;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Writes segments as NDJSON files on a local or Kubernetes-mounted volume:
 *
 * <pre>
 * root/tenant=default/_staging/source=ID/START.ndjson.part                   while writing
 * root/tenant=default/dt=YYYY-MM-DD/source=ID/gen=G/START-END.ndjson        after commit
 * </pre>
 *
 * <p>Names derive from source offsets, and {@code dt} from the segment's first record, so replaying
 * a segment after a crash lands on the same path and replaces the earlier copy. {@code gen}
 * separates successive files at the same path (rotation, truncation), whose offsets restart at 0.
 */
@Slf4j
@Component
public class LocalVolumeSink implements LogSink {

  private static final String TENANT_DIR = "tenant=default";
  private static final String PART_SUFFIX = ".ndjson.part";
  private static final int WRITE_BUFFER_BYTES = 256 * 1024;

  private final Path tenantRoot;
  private final long minFreeBytes;
  private final ObjectWriter writer;

  public LocalVolumeSink(LocalSinkProperties props, ObjectMapper mapper) {
    this.tenantRoot = props.root().resolve(TENANT_DIR);
    this.minFreeBytes = props.minFreeBytes().toBytes();
    // Flushing per record would turn every record into a write syscall; commit flushes instead.
    this.writer = mapper.writer().without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
  }

  @Override
  public void discardUncommitted(String sourceId) throws IOException {
    Path staging = stagingDir(sourceId);
    if (!Files.isDirectory(staging)) {
      return;
    }
    try (DirectoryStream<Path> parts = Files.newDirectoryStream(staging, "*" + PART_SUFFIX)) {
      for (Path part : parts) {
        log.info("Discarding uncommitted segment {}", part);
        Files.deleteIfExists(part);
      }
    }
  }

  @Override
  public SegmentWriter open(String sourceId, int generation, long startOffset) throws IOException {
    Path staging = Files.createDirectories(stagingDir(sourceId));
    long usable = Files.getFileStore(staging).getUsableSpace();
    if (usable < minFreeBytes) {
      throw new IOException(
          "Insufficient disk space for a new segment: "
              + usable
              + " bytes free, "
              + minFreeBytes
              + " required");
    }
    return new LocalSegmentWriter(
        sourceId, generation, startOffset, staging.resolve(startOffset + PART_SUFFIX));
  }

  private Path stagingDir(String sourceId) {
    return tenantRoot
        .resolve("_staging")
        .resolve("source=" + SourceOffset.requireValidSourceId(sourceId));
  }

  private final class LocalSegmentWriter implements SegmentWriter {

    private final String sourceId;
    private final int generation;
    private final long startOffset;
    private final Path part;
    private final FileChannel channel;
    private final JsonGenerator json;
    private Instant firstTimestamp;
    private long records;

    LocalSegmentWriter(String sourceId, int generation, long startOffset, Path part)
        throws IOException {
      this.sourceId = sourceId;
      this.generation = generation;
      this.startOffset = startOffset;
      this.part = part;
      this.channel =
          FileChannel.open(
              part,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      this.json =
          writer.createGenerator(
              new BufferedOutputStream(Channels.newOutputStream(channel), WRITE_BUFFER_BYTES));
      // NDJSON: records are separated by the newline written below, not the default space.
      json.setRootValueSeparator(null);
    }

    @Override
    public void write(LogBatch batch) throws IOException {
      for (LogRecord record : batch.records()) {
        if (firstTimestamp == null) {
          firstTimestamp = record.getTimestamp();
        }
        writer.writeValue(json, record);
        json.writeRaw('\n');
        records++;
      }
    }

    @Override
    public String commit(long endOffset) throws IOException {
      json.flush();
      channel.force(true);
      json.close();
      if (records == 0) {
        Files.deleteIfExists(part);
        return null;
      }
      Path segmentDir =
          DurableFiles.createDirectories(
              tenantRoot
                  .resolve("dt=" + day(firstTimestamp))
                  .resolve("source=" + sourceId)
                  .resolve("gen=" + generation));
      String prefix = startOffset + "-";
      // A segment committed by a crashed run may have ended at a different offset (the file grew
      // since); replace it rather than leaving two overlapping copies.
      try (DirectoryStream<Path> earlier =
          Files.newDirectoryStream(segmentDir, prefix + "*.ndjson")) {
        for (Path p : earlier) {
          Files.deleteIfExists(p);
        }
      }
      Path target = segmentDir.resolve(prefix + endOffset + ".ndjson");
      // Also makes the deletes above durable: they are in the same directory.
      DurableFiles.moveAtomically(part, target);
      return target.toString();
    }

    @Override
    public void abort() {
      try {
        json.close();
      } catch (IOException e) {
        log.debug("Ignoring close failure while aborting {}", part, e);
      }
      try {
        Files.deleteIfExists(part);
      } catch (IOException e) {
        log.warn("Could not delete aborted segment {}; it is discarded on the next run", part, e);
      }
    }

    private String day(Instant timestamp) {
      return timestamp == null
          ? "unknown"
          : timestamp.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
  }
}
