package dev.dinesh.argus.sink;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.luben.zstd.ZstdOutputStream;
import dev.dinesh.argus.config.LocalSinkProperties;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.tenant.TenantIds;
import dev.dinesh.argus.utils.DurableFiles;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Writes segments as NDJSON files on a local or Kubernetes-mounted volume:
 *
 * <pre>
 * root/tenant=T/_staging/source=ID/START.ndjson.part                   while writing
 * root/tenant=T/dt=YYYY-MM-DD/source=ID/gen=G/START-END.ndjson        after commit (.ndjson.zst with zstd)
 * </pre>
 *
 * <p>Names derive from source offsets, and {@code dt} from the first event time in the segment
 * ({@code unknown} if no record has one; ingest time would change on replay), so replaying a
 * segment after a crash lands on the same path and replaces the earlier copy. {@code gen} separates
 * successive files at the same path (rotation, truncation), whose offsets restart at 0.
 */
@Slf4j
@Component
public class LocalVolumeSink implements LogSink {

  private static final String PART_SUFFIX = ".ndjson.part";
  private static final int WRITE_BUFFER_BYTES = 256 * 1024;

  /** Suffix of a segment written with {@code compression=zstd}. */
  public static final String ZSTD_SUFFIX = ".zst";

  /** zstd's fastest level: ingest writes faster than higher levels compress. */
  private static final int ZSTD_LEVEL = 1;

  private final Path root;
  private final long minFreeBytes;
  private final boolean zstd;
  private final ObjectWriter writer;

  public LocalVolumeSink(LocalSinkProperties props, ObjectMapper mapper) {
    this.root = props.root();
    this.minFreeBytes = props.minFreeBytes().toBytes();
    this.zstd = props.compression() == LocalSinkProperties.Compression.ZSTD;
    // Flushing per record would turn every record into a write syscall; commit flushes instead.
    this.writer = mapper.writer().without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
  }

  @Override
  public void discardUncommitted(String tenantId, String sourceId) throws IOException {
    Path staging = stagingDir(tenantId, sourceId);
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
  public SegmentWriter open(String tenantId, String sourceId, int generation, long startOffset)
      throws IOException {
    Path staging = Files.createDirectories(stagingDir(tenantId, sourceId));
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
        tenantRoot(tenantId),
        sourceId,
        generation,
        startOffset,
        staging.resolve(startOffset + PART_SUFFIX));
  }

  private Path tenantRoot(String tenantId) {
    return root.resolve(TenantIds.pathSegment(tenantId));
  }

  private Path stagingDir(String tenantId, String sourceId) {
    return tenantRoot(tenantId)
        .resolve("_staging")
        .resolve("source=" + SourceOffset.requireValidSourceId(sourceId));
  }

  /** Passes writes through; {@code close} only flushes, leaving the channel to the writer. */
  private static final class KeepOpenOutputStream extends FilterOutputStream {

    KeepOpenOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }

  private final class LocalSegmentWriter implements SegmentWriter {

    private final Path tenantRoot;
    private final String sourceId;
    private final int generation;
    private final long startOffset;
    private final Path part;
    private final FileChannel channel;
    private final OutputStream out;
    private final JsonGenerator json;
    private Instant firstTimestamp;
    private long records;

    LocalSegmentWriter(
        Path tenantRoot, String sourceId, int generation, long startOffset, Path part)
        throws IOException {
      this.tenantRoot = tenantRoot;
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
      // Closing the streams must not close the channel: commit fsyncs it after the zstd frame (if
      // any) is complete.
      OutputStream file = new KeepOpenOutputStream(Channels.newOutputStream(channel));
      this.out = new BufferedOutputStream(zstd ? zstd(file) : file, WRITE_BUFFER_BYTES);
      this.json = writer.createGenerator(out);
      json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
      // NDJSON: records are separated by the newline written below, not the default space.
      json.setRootValueSeparator(null);
    }

    @Override
    public void write(LogBatch batch) throws IOException {
      for (LogEnvelope record : batch.records()) {
        if (firstTimestamp == null) {
          firstTimestamp = record.timestamp();
        }
        writer.writeValue(json, record);
        json.writeRaw('\n');
        records++;
      }
    }

    @Override
    public String commit(long endOffset) throws IOException {
      json.close();
      out.close(); // ends the zstd frame
      channel.force(true);
      channel.close();
      if (records == 0) {
        Files.deleteIfExists(part);
        return null;
      }
      Path segmentDir =
          DurableFiles.createDirectories(
              tenantRoot
                  .resolve("dt=" + EventTimePartition.day(firstTimestamp))
                  .resolve("source=" + sourceId)
                  .resolve("gen=" + generation));
      String prefix = startOffset + "-";
      // A segment committed by a crashed run may have ended at a different offset (the file grew
      // since); replace it rather than leaving two overlapping copies. The trailing * also takes
      // the archiver's markers (.archived, .unreadable) for the replaced copy.
      try (DirectoryStream<Path> earlier =
          Files.newDirectoryStream(segmentDir, prefix + "*.ndjson*")) {
        for (Path p : earlier) {
          Files.deleteIfExists(p);
        }
      }
      Path target = segmentDir.resolve(prefix + endOffset + ".ndjson" + (zstd ? ZSTD_SUFFIX : ""));
      // Also makes the deletes above durable: they are in the same directory.
      DurableFiles.moveAtomically(part, target);
      return target.toString();
    }

    /**
     * A content checksum per frame, so a corrupt segment fails to decompress, not decodes wrong.
     */
    private static OutputStream zstd(OutputStream file) throws IOException {
      return new ZstdOutputStream(file, ZSTD_LEVEL).setChecksum(true);
    }

    @Override
    public void abort() {
      try {
        channel.close();
      } catch (IOException e) {
        log.debug("Ignoring close failure while aborting {}", part, e);
      }
      // The channel is closed first, so this writes nothing: it only frees the zstd context.
      try {
        out.close();
      } catch (IOException e) {
        log.trace("Expected: the aborted segment's channel is closed", e);
      }
      try {
        Files.deleteIfExists(part);
      } catch (IOException e) {
        log.warn("Could not delete aborted segment {}; it is discarded on the next run", part, e);
      }
    }
  }
}
