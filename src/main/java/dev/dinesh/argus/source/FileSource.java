package dev.dinesh.argus.source;

import dev.dinesh.argus.config.IngestProperties;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Streams a file as {@link RawBatch}es with exact byte offsets, so processing can resume from any
 * committed offset. Batches only end on line boundaries, which keeps every offset resumable.
 *
 * <p>Reads up to the file size observed at open time; bytes appended later are picked up by the
 * next run resuming from its checkpoint. Not thread-safe: one reader thread per source.
 */
public final class FileSource implements Closeable {

  private static final int READ_BUFFER_BYTES = 64 * 1024;
  private static final int INITIAL_LINE_BYTES = 8 * 1024;

  private final String sourceId;
  private final FileChannel channel;
  private final long limit;
  private final int batchMaxLines;
  private final long batchMaxBytes;
  private final long segmentBytes;
  private final int maxLineBytes;
  private final ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);

  private byte[] lineBytes = new byte[INITIAL_LINE_BYTES];
  private int lineLength;
  private boolean lineTruncated;
  private long filePosition;
  private long consumed;
  private long segmentStart;
  private int segmentIndex;
  private long sequence;

  private FileSource(String sourceId, FileChannel channel, long startOffset, IngestProperties props)
      throws IOException {
    this.sourceId = sourceId;
    this.channel = channel;
    this.limit = channel.size();
    if (startOffset < 0 || startOffset > limit) {
      throw new IllegalArgumentException(
          "Start offset " + startOffset + " is outside file of " + limit + " bytes");
    }
    this.batchMaxLines = props.batchMaxLines();
    this.batchMaxBytes = props.batchMaxBytes().toBytes();
    this.segmentBytes = props.segmentBytes().toBytes();
    this.maxLineBytes = Math.toIntExact(props.maxLineBytes().toBytes());
    this.filePosition = startOffset;
    this.consumed = startOffset;
    this.segmentStart = startOffset;
    buffer.flip();
  }

  public static FileSource open(
      Path path, String sourceId, long startOffset, IngestProperties props) throws IOException {
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
    try {
      return new FileSource(sourceId, channel, startOffset, props);
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  /** Filesystem identity of {@code path} (inode on Linux/macOS), or null if not exposed. */
  public static String fileKey(Path path) throws IOException {
    Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    return key == null ? null : key.toString();
  }

  /** Returns the next batch, or null once the file size seen at open has been consumed. */
  public RawBatch nextBatch() throws IOException {
    if (consumed >= limit) {
      return null;
    }
    long start = consumed;
    List<String> lines = new ArrayList<>();
    int truncated = 0;
    while (consumed < limit && lines.size() < batchMaxLines && consumed - start < batchMaxBytes) {
      readLine();
      if (lineTruncated) {
        truncated++;
      }
      String line = decodeLine();
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    boolean endOfSource = consumed >= limit;
    boolean lastInSegment = endOfSource || consumed - segmentStart >= segmentBytes;
    RawBatch batch =
        new RawBatch(
            sourceId, segmentIndex, sequence++, lines, start, consumed, lastInSegment, truncated);
    if (lastInSegment && !endOfSource) {
      segmentIndex++;
      segmentStart = consumed;
    }
    return batch;
  }

  /** Total bytes in the source as seen at open time. */
  public long limit() {
    return limit;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  /** Consumes bytes up to and including the next newline, or up to the read limit. */
  private void readLine() throws IOException {
    lineLength = 0;
    lineTruncated = false;
    while (consumed < limit) {
      if (!buffer.hasRemaining()) {
        fill();
      }
      byte b = buffer.get();
      consumed++;
      if (b == '\n') {
        return;
      }
      appendToLine(b);
    }
  }

  private void fill() throws IOException {
    buffer.clear();
    buffer.limit((int) Math.min(buffer.capacity(), limit - filePosition));
    int read = channel.read(buffer, filePosition);
    buffer.flip();
    if (read <= 0) {
      throw new IOException(
          "File shrank while reading: expected " + limit + " bytes, got " + filePosition);
    }
    filePosition += read;
  }

  private void appendToLine(byte b) {
    if (lineLength >= maxLineBytes) {
      lineTruncated = true;
      return;
    }
    if (lineLength == lineBytes.length) {
      lineBytes = Arrays.copyOf(lineBytes, Math.min(lineBytes.length * 2, maxLineBytes));
    }
    lineBytes[lineLength++] = b;
  }

  private String decodeLine() {
    int length = lineLength;
    if (length > 0 && lineBytes[length - 1] == '\r') {
      length--;
    }
    // Malformed or truncated multi-byte sequences decode to U+FFFD rather than failing the batch.
    return new String(lineBytes, 0, length, StandardCharsets.UTF_8);
  }
}
