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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Streams a file as {@link RawBatch}es with exact byte offsets, so processing can resume from any
 * committed offset. Batches only end on line boundaries, which keeps every offset resumable.
 *
 * <p>A run reads up to the last newline present when it starts ({@link #startAt}). A trailing line
 * without a newline may still be being written, so it is left for the next run rather than split in
 * two (the same rule as Filebeat and Fluent Bit). Bytes appended later are picked up by the next
 * run resuming from its checkpoint. Not thread-safe: one reader thread per source.
 */
public final class FileSource implements Closeable {

  /** Leading bytes covered by {@link #sha256OfFirst} fingerprints. */
  public static final int FINGERPRINT_BYTES = 1024;

  private static final int READ_BUFFER_BYTES = 64 * 1024;
  private static final int INITIAL_LINE_BYTES = 8 * 1024;
  private static final int INITIAL_OFFSETS = 1024;
  private static final int OPEN_ATTEMPTS = 3;

  private final String sourceId;
  private final FileChannel channel;
  private final String fileKey;
  private final long size;
  private final int batchMaxLines;
  private final long batchMaxBytes;
  private final long segmentBytes;
  private final int maxLineBytes;
  private final ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_BYTES);

  private byte[] lineBytes = new byte[INITIAL_LINE_BYTES];
  private int lineLength;
  private boolean lineTruncated;
  private long limit = -1;
  private long filePosition;
  private long consumed;
  private long segmentStart;
  private int segmentIndex;
  private long sequence;

  private FileSource(String sourceId, FileChannel channel, String fileKey, IngestProperties props)
      throws IOException {
    this.sourceId = sourceId;
    this.channel = channel;
    this.fileKey = fileKey;
    this.size = channel.size();
    this.batchMaxLines = props.batchMaxLines();
    this.batchMaxBytes = props.batchMaxBytes().toBytes();
    this.segmentBytes = props.segmentBytes().toBytes();
    this.maxLineBytes = Math.toIntExact(props.maxLineBytes().toBytes());
  }

  /**
   * Opens {@code path} without choosing a start offset yet, so the caller can compare the opened
   * file ({@link #fileKey}, {@link #size}, {@link #sha256OfFirst}) with its checkpoint first. Call
   * {@link #startAt} before reading.
   */
  public static FileSource open(Path path, String sourceId, IngestProperties props)
      throws IOException {
    String key = fileKey(path);
    for (int attempt = 1; ; attempt++) {
      FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
      try {
        // Only trust the key if the path named the same file before and after the open.
        String keyAfterOpen = fileKey(path);
        if (Objects.equals(key, keyAfterOpen)) {
          return new FileSource(sourceId, channel, key, props);
        }
        channel.close();
        if (attempt == OPEN_ATTEMPTS) {
          throw new IOException("File kept being replaced while opening: " + path);
        }
        key = keyAfterOpen;
      } catch (IOException | RuntimeException e) {
        channel.close();
        throw e;
      }
    }
  }

  /** Opens {@code path} and positions it at {@code startOffset}. */
  public static FileSource open(
      Path path, String sourceId, long startOffset, IngestProperties props) throws IOException {
    FileSource source = open(path, sourceId, props);
    try {
      source.startAt(startOffset);
      return source;
    } catch (IOException | RuntimeException e) {
      source.close();
      throw e;
    }
  }

  /**
   * Starts reading at {@code startOffset}, and fixes this run's end at the last newline in the
   * file.
   */
  public void startAt(long startOffset) throws IOException {
    if (startOffset < 0 || startOffset > size) {
      throw new IllegalArgumentException(
          "Start offset " + startOffset + " is outside file of " + size + " bytes");
    }
    this.limit = endOfLastCompleteLine(startOffset);
    this.filePosition = startOffset;
    this.consumed = startOffset;
    this.segmentStart = startOffset;
    buffer.clear().flip();
  }

  /** Filesystem identity of the opened file (inode on Linux/macOS), or null if not exposed. */
  public String fileKey() {
    return fileKey;
  }

  /** Size of the opened file when it was opened, including any incomplete trailing line. */
  public long size() {
    return size;
  }

  /** Hex SHA-256 of the file's first {@code length} bytes. */
  public String sha256OfFirst(int length) throws IOException {
    if (length > size) {
      throw new IllegalArgumentException(
          "Cannot fingerprint " + length + " bytes of a " + size + "-byte file");
    }
    ByteBuffer head = ByteBuffer.allocate(length);
    readFully(head, 0);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(head.array()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String fileKey(Path path) throws IOException {
    Object key = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    return key == null ? null : key.toString();
  }

  /** Returns the next batch, or null once the file size seen at open has been consumed. */
  public RawBatch nextBatch() throws IOException {
    requireStarted();
    if (consumed >= limit) {
      return null;
    }
    long start = consumed;
    List<String> lines = new ArrayList<>();
    long[] lineOffsets = new long[Math.min(batchMaxLines, INITIAL_OFFSETS)];
    int truncated = 0;
    while (consumed < limit && lines.size() < batchMaxLines && consumed - start < batchMaxBytes) {
      long lineStart = consumed;
      readLine();
      if (lineTruncated) {
        truncated++;
      }
      String line = decodeLine();
      if (!line.isBlank()) {
        if (lines.size() == lineOffsets.length) {
          lineOffsets = Arrays.copyOf(lineOffsets, lineOffsets.length * 2);
        }
        lineOffsets[lines.size()] = lineStart;
        lines.add(line);
      }
    }
    boolean endOfSource = consumed >= limit;
    boolean lastInSegment = endOfSource || consumed - segmentStart >= segmentBytes;
    RawBatch batch =
        new RawBatch(
            sourceId,
            segmentIndex,
            sequence++,
            lines,
            Arrays.copyOf(lineOffsets, lines.size()),
            start,
            consumed,
            lastInSegment,
            truncated);
    if (lastInSegment && !endOfSource) {
      segmentIndex++;
      segmentStart = consumed;
    }
    return batch;
  }

  /** End of this run: just past the last newline in the file when {@link #startAt} ran. */
  public long limit() {
    requireStarted();
    return limit;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  private void requireStarted() {
    if (limit < 0) {
      throw new IllegalStateException("startAt has not been called");
    }
  }

  /** Scans back from the end for the last newline at or after {@code from}. */
  private long endOfLastCompleteLine(long from) throws IOException {
    long end = size;
    while (end > from) {
      long chunkStart = Math.max(from, end - buffer.capacity());
      buffer.clear().limit((int) (end - chunkStart));
      readFully(buffer, chunkStart);
      for (int i = buffer.limit() - 1; i >= 0; i--) {
        if (buffer.get(i) == '\n') {
          return chunkStart + i + 1;
        }
      }
      end = chunkStart;
    }
    return from;
  }

  private void readFully(ByteBuffer target, long position) throws IOException {
    long start = position - target.position();
    while (target.hasRemaining()) {
      if (channel.read(target, start + target.position()) < 0) {
        throw new IOException("File shrank while reading: expected " + size + " bytes");
      }
    }
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
