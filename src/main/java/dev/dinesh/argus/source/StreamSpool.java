package dev.dinesh.argus.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.utils.DurableFiles;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * One push stream's durable buffer ({@code P3_PLAN.md} D1, D2): numbered spool files ({@code
 * 000001.log}, …) holding the raw lines of acknowledged requests, and an {@code index.log} with one
 * entry per acknowledged request. The stream runner ingests the spool files like any file, reading
 * each only to its acknowledged ("visible") length.
 *
 * <p>An append writes and fsyncs the lines, then writes and fsyncs the index entry, and only then
 * moves the visible length; a failure truncates both back. The index is therefore the record of
 * what was acknowledged: at startup, bytes of the newest spool file past its last index entry
 * belonged to a request that was never acknowledged and are truncated away. The index also holds
 * the {@code Idempotency-Key}s of the last {@code idempotencyWindow} requests, so a retried request
 * is answered with its original offsets and writes nothing, across restarts too.
 *
 * <p>A spool file is sealed once it reaches {@code rotateBytes} (or once it is fully read and at
 * least {@link #IDLE_ROTATE_BYTES}); the next append starts the next number, and a sealed file is
 * deleted once it is fully read. Numbers are never reused, so a spool's path identifies it ({@link
 * dev.dinesh.argus.checkpoint.ResumePoint.Reason#PATH_CHANGED}). Thread-safe: every method holds
 * the spool's lock, so appends to one stream are serialized.
 */
@Slf4j
public final class StreamSpool implements Closeable {

  static final String INDEX_FILE = "index.log";

  /**
   * A fully read spool file at least this large is sealed and deleted even below {@code
   * rotateBytes}, so an idle stream frees its disk; a smaller one is kept, so a quiet stream
   * doesn't start a new file (and output generation) for every request.
   */
  static final long IDLE_ROTATE_BYTES = 1L << 20;

  private static final Pattern SPOOL_NAME = Pattern.compile("(\\d{6})\\.log");
  private static final int MAX_SPOOL_NUMBER = 999_999;
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * @param idempotencyWindow how many of the latest {@code Idempotency-Key}s are remembered
   * @param rotateBytes seal a spool file once it is this large
   */
  public record Settings(int idempotencyWindow, long rotateBytes) {}

  /** The first byte not yet ingested: spool file {@code spool}, offset {@code offset}. */
  public record ReadPosition(int spool, long offset) {

    /** Where a stream's checkpoint says reading stopped, or empty for a checkpoint of no spool. */
    public static Optional<ReadPosition> of(SourceOffset checkpoint) {
      int spool = spoolNumber(Path.of(checkpoint.path()));
      return spool < 0
          ? Optional.empty()
          : Optional.of(new ReadPosition(spool, checkpoint.byteOffset()));
    }
  }

  /**
   * Where a request's lines were spooled.
   *
   * @param duplicate an earlier request had the same {@code Idempotency-Key}: nothing was written,
   *     and the offsets and time are the original request's
   */
  public record Append(
      int spool, long startOffset, long endOffset, Instant receivedAt, boolean duplicate) {}

  /**
   * The next range a run should read.
   *
   * @param endOffset the file's visible length when claimed
   * @param sealed no more appends will go to this file
   * @param claimedSince when the bytes the claim took over started waiting (nanos), or 0
   */
  public record Pending(int spool, Path path, long endOffset, boolean sealed, long claimedSince) {}

  /**
   * @param activeSpool the file the next append goes to (it may not exist yet)
   * @param files spool files on disk
   * @param pendingBytes acknowledged bytes not yet ingested
   */
  public record Status(
      int activeSpool, int files, long bytesOnDisk, long pendingBytes, int idempotencyKeys) {}

  /** One line of {@code index.log}: an acknowledged request. */
  record IndexEntry(int spool, long start, long end, String key, long at) {}

  private static final class SpoolFile {
    final int number;
    final Path path;
    long visible;

    SpoolFile(int number, Path path, long visible) {
      this.number = number;
      this.path = path;
      this.visible = visible;
    }
  }

  private final Path dir;
  private final Settings settings;
  private final AtomicLong pendingTotal;
  private final LongSupplier nanoTime;
  private final TreeMap<Integer, SpoolFile> files = new TreeMap<>();
  private final LinkedHashMap<String, IndexEntry> keys = new LinkedHashMap<>();
  private final Map<Integer, IndexEntry> lastEntryBySpool = new HashMap<>();
  private FileChannel index;
  private int active;
  private FileChannel activeChannel;
  private ReadPosition read;
  private long indexSize;
  private int indexEntries;
  private long pendingSince;
  private boolean closed;

  private StreamSpool(
      Path dir,
      Settings settings,
      AtomicLong pendingTotal,
      LongSupplier nanoTime,
      FileChannel index) {
    this.dir = dir;
    this.settings = settings;
    this.pendingTotal = pendingTotal;
    this.nanoTime = nanoTime;
    this.index = index;
  }

  /**
   * Opens (creating if needed) the spool in {@code dir} and recovers it: unacknowledged bytes are
   * truncated, and the last {@code idempotencyWindow} keys are loaded.
   *
   * @param read where the stream's checkpoint says reading stopped, if it has one
   * @param pendingTotal shared by every stream's spool: their acknowledged, not yet ingested bytes
   */
  public static StreamSpool open(
      Path dir,
      Settings settings,
      Optional<ReadPosition> read,
      AtomicLong pendingTotal,
      LongSupplier nanoTime)
      throws IOException {
    DurableFiles.createDirectories(dir);
    Path indexPath = dir.resolve(INDEX_FILE);
    boolean created = !Files.exists(indexPath);
    FileChannel index =
        FileChannel.open(indexPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      if (created) {
        DurableFiles.fsyncDirectory(dir);
      }
      StreamSpool spool = new StreamSpool(dir, settings, pendingTotal, nanoTime, index);
      spool.recover(indexPath, read.orElse(new ReadPosition(0, 0)));
      return spool;
    } catch (IOException | RuntimeException e) {
      index.close();
      throw e;
    }
  }

  /** The spool number of a spool file's path ({@code 000042.log} → 42), or -1. */
  public static int spoolNumber(Path path) {
    Path name = path.getFileName();
    Matcher m = name == null ? null : SPOOL_NAME.matcher(name.toString());
    return m != null && m.matches() ? Integer.parseInt(m.group(1)) : -1;
  }

  /** The request that used {@code key} among the latest {@code idempotencyWindow}, if any. */
  public synchronized Optional<Append> find(String key) {
    IndexEntry entry = key == null ? null : keys.get(key);
    return Optional.ofNullable(entry).map(e -> toAppend(e, true));
  }

  /**
   * Appends {@code length} bytes of {@code lines} (whole lines, each ending in {@code \n}; may be
   * empty) and makes them durable with their index entry before returning. A request whose {@code
   * key} was already acknowledged writes nothing and returns the original append.
   */
  public synchronized Append append(byte[] lines, int length, String key, Instant receivedAt)
      throws IOException {
    requireOpen();
    if (length > 0 && lines[length - 1] != '\n') {
      throw new IllegalArgumentException("A spool append must end with a newline");
    }
    IndexEntry earlier = key == null ? null : keys.get(key);
    if (earlier != null) {
      return toAppend(earlier, true);
    }
    long pendingBefore = pending();
    SpoolFile file = activeFile();
    long start = file.visible;
    long indexStart = indexSize;
    IndexEntry entry =
        new IndexEntry(file.number, start, start + length, key, receivedAt.toEpochMilli());
    try {
      write(activeChannel, ByteBuffer.wrap(lines, 0, length), start);
      activeChannel.force(false);
      byte[] json = indexLine(entry);
      write(index, ByteBuffer.wrap(json), indexStart);
      index.force(false);
      indexSize += json.length;
    } catch (IOException | RuntimeException e) {
      truncateQuietly(activeChannel, start);
      truncateQuietly(index, indexStart);
      throw e;
    }
    file.visible = start + length;
    remember(entry);
    if (length > 0 && pendingSince == 0) {
      pendingSince = nanoTime.getAsLong();
    }
    pendingTotal.addAndGet(pending() - pendingBefore);
    if (indexEntries > 2 * settings.idempotencyWindow() + files.size()) {
      compactIndex();
    }
    return toAppend(entry, false);
  }

  /**
   * True when a run should start: {@code segmentBytes} are waiting, a sealed file is waiting, or
   * the oldest waiting byte has waited {@code flushIntervalNanos}.
   */
  public synchronized boolean due(long segmentBytes, long flushIntervalNanos) {
    long pending = pending();
    if (pending == 0) {
      return false;
    }
    boolean sealedWaiting =
        files.values().stream().anyMatch(f -> f.number < active && unread(f) > 0);
    return pending >= segmentBytes
        || sealedWaiting
        || pendingSince == 0
        || nanoTime.getAsLong() - pendingSince >= flushIntervalNanos;
  }

  /**
   * The oldest spool file with unread bytes, up to its visible length now. If that covers every
   * waiting byte, the waiting clock restarts with the next append; give it back with {@link
   * #release} if the run doesn't read it all.
   */
  public synchronized Optional<Pending> claim() {
    for (SpoolFile file : files.values()) {
      if (unread(file) > 0) {
        boolean last =
            files.tailMap(file.number, false).values().stream().allMatch(f -> unread(f) == 0);
        long since = 0;
        if (last) {
          since = pendingSince;
          pendingSince = 0;
        }
        return Optional.of(
            new Pending(file.number, file.path, file.visible, file.number < active, since));
      }
    }
    return Optional.empty();
  }

  /** A claimed range that wasn't fully ingested: its bytes keep the time they started waiting. */
  public synchronized void release(Pending claim) {
    if (claim.claimedSince() != 0 && (pendingSince == 0 || claim.claimedSince() < pendingSince)) {
      pendingSince = claim.claimedSince();
    }
  }

  /**
   * Records how far the stream's checkpoint has got, then deletes sealed files that are fully read
   * (and seals and deletes a fully read idle file of at least {@link #IDLE_ROTATE_BYTES}).
   */
  public synchronized void markRead(ReadPosition position) throws IOException {
    requireOpen();
    long pendingBefore = pending();
    read = position;
    SpoolFile current = files.get(active);
    if (current != null && unread(current) == 0 && current.visible >= IDLE_ROTATE_BYTES) {
      seal();
    }
    try {
      for (Iterator<SpoolFile> it = files.values().iterator(); it.hasNext(); ) {
        SpoolFile file = it.next();
        if (file.number < active && unread(file) == 0) {
          Files.deleteIfExists(file.path);
          it.remove();
          log.info("Spool file ingested and deleted path={} bytes={}", file.path, file.visible);
        }
      }
    } finally {
      pendingTotal.addAndGet(pending() - pendingBefore);
    }
  }

  public synchronized Status status() {
    long onDisk = files.values().stream().mapToLong(f -> f.visible).sum();
    return new Status(active, files.size(), onDisk, pending(), keys.size());
  }

  /** Acknowledged bytes not yet ingested. */
  public synchronized long pendingBytes() {
    return pending();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    pendingTotal.addAndGet(-pending());
    try {
      if (activeChannel != null) {
        activeChannel.close();
      }
    } finally {
      index.close();
    }
  }

  private void recover(Path indexPath, ReadPosition position) throws IOException {
    List<IndexEntry> entries = readIndex(indexPath);
    entries.forEach(this::remember);
    int newestInIndex = entries.stream().mapToInt(IndexEntry::spool).max().orElse(0);
    try (Stream<Path> listing = Files.list(dir)) {
      for (Path path : (Iterable<Path>) listing::iterator) {
        int number = spoolNumber(path);
        if (number > 0) {
          files.put(number, new SpoolFile(number, path, Files.size(path)));
        }
      }
    }
    int newestFile = files.isEmpty() ? 0 : files.lastKey();
    for (SpoolFile file : files.values()) {
      IndexEntry last = lastEntryBySpool.get(file.number);
      // A sealed file was complete when sealed; only the newest can hold an unacknowledged append.
      long acknowledged = last != null ? last.end() : file.number == newestFile ? 0 : file.visible;
      if (file.visible > acknowledged) {
        log.warn(
            "Spool {}: dropping {} unacknowledged bytes after offset {}",
            file.path,
            file.visible - acknowledged,
            acknowledged);
        try (FileChannel channel = FileChannel.open(file.path, StandardOpenOption.WRITE)) {
          channel.truncate(acknowledged);
          channel.force(true);
        }
        file.visible = acknowledged;
      } else if (file.visible < acknowledged) {
        log.error(
            "Spool {} is shorter ({} bytes) than acknowledged ({}); its tail is lost",
            file.path,
            file.visible,
            acknowledged);
      }
    }
    read = position;
    int newest = Math.max(newestFile, Math.max(newestInIndex, position.spool()));
    // Never reuse a number: a spool's path is its identity for checkpoints.
    active = files.containsKey(newest) ? newest : newest + 1;
    long pending = pending();
    pendingTotal.addAndGet(pending);
    if (pending > 0) {
      pendingSince = nanoTime.getAsLong();
    }
  }

  private List<IndexEntry> readIndex(Path indexPath) throws IOException {
    // Bounded: compaction keeps it to about 2 x idempotencyWindow entries (~200KB).
    byte[] bytes = Files.readAllBytes(indexPath);
    List<IndexEntry> entries = new ArrayList<>();
    int lineStart = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] != '\n') {
        continue;
      }
      try {
        entries.add(JSON.readValue(bytes, lineStart, i - lineStart, IndexEntry.class));
      } catch (IOException e) {
        log.warn("Spool index {}: skipping unreadable entry at byte {}", indexPath, lineStart);
      }
      lineStart = i + 1;
    }
    // A last entry without its newline was being written when the process stopped: that request
    // was never acknowledged.
    indexSize = lineStart;
    if (lineStart < bytes.length) {
      index.truncate(lineStart);
      index.force(true);
    }
    return entries;
  }

  private void remember(IndexEntry entry) {
    lastEntryBySpool.put(entry.spool(), entry);
    if (entry.key() != null) {
      keys.remove(entry.key());
      keys.put(entry.key(), entry);
      if (keys.size() > settings.idempotencyWindow()) {
        Iterator<String> oldest = keys.keySet().iterator();
        oldest.next();
        oldest.remove();
      }
    }
    indexEntries++;
  }

  /**
   * Rewrites the index with only what recovery needs: the remembered keys and the last entry of
   * every spool file still on disk (its acknowledged length).
   */
  private void compactIndex() throws IOException {
    List<IndexEntry> kept =
        Stream.concat(
                keys.values().stream(),
                files.keySet().stream().map(lastEntryBySpool::get).filter(Objects::nonNull))
            .distinct()
            // In append order, so recovery's "last entry per spool" is still the newest one.
            .sorted(Comparator.comparingInt(IndexEntry::spool).thenComparingLong(IndexEntry::end))
            .toList();
    Path tmp = dir.resolve(INDEX_FILE + ".tmp");
    long size = 0;
    try (FileChannel out =
        FileChannel.open(
            tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      for (IndexEntry e : kept) {
        byte[] line = indexLine(e);
        write(out, ByteBuffer.wrap(line), size);
        size += line.length;
      }
      out.force(true);
    }
    Path indexPath = dir.resolve(INDEX_FILE);
    DurableFiles.moveAtomically(tmp, indexPath);
    // The open channel still points at the replaced file, so later entries go through a new one.
    index.close();
    index = FileChannel.open(indexPath, StandardOpenOption.WRITE);
    indexSize = size;
    indexEntries = kept.size();
    lastEntryBySpool.keySet().retainAll(files.keySet());
  }

  private SpoolFile activeFile() throws IOException {
    SpoolFile file = files.get(active);
    if (file != null && file.visible >= settings.rotateBytes()) {
      seal();
      file = null;
    }
    if (file == null) {
      if (active > MAX_SPOOL_NUMBER) {
        throw new IOException("Spool numbers exhausted in " + dir);
      }
      Path path = dir.resolve(String.format("%06d.log", active));
      activeChannel =
          FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      DurableFiles.fsyncDirectory(dir);
      file = new SpoolFile(active, path, 0);
      files.put(active, file);
    } else if (activeChannel == null) {
      activeChannel = FileChannel.open(file.path, StandardOpenOption.WRITE);
    }
    return file;
  }

  private void seal() throws IOException {
    if (activeChannel != null) {
      activeChannel.close();
      activeChannel = null;
    }
    active++;
  }

  private long pending() {
    long total = 0;
    for (SpoolFile file : files.values()) {
      total += unread(file);
    }
    return total;
  }

  private long unread(SpoolFile file) {
    if (file.number < read.spool()) {
      return 0;
    }
    if (file.number == read.spool()) {
      return Math.max(0, file.visible - read.offset());
    }
    return file.visible;
  }

  private Append toAppend(IndexEntry e, boolean duplicate) {
    return new Append(e.spool(), e.start(), e.end(), Instant.ofEpochMilli(e.at()), duplicate);
  }

  private static byte[] indexLine(IndexEntry entry) throws JsonProcessingException {
    byte[] json = JSON.writeValueAsBytes(entry);
    byte[] line = new byte[json.length + 1];
    System.arraycopy(json, 0, line, 0, json.length);
    line[json.length] = '\n';
    return line;
  }

  private static void write(FileChannel channel, ByteBuffer data, long position)
      throws IOException {
    while (data.hasRemaining()) {
      position += channel.write(data, position);
    }
  }

  private void truncateQuietly(FileChannel channel, long size) {
    try {
      if (channel != null && channel.size() > size) {
        channel.truncate(size);
      }
    } catch (IOException e) {
      log.warn("Spool {}: could not truncate back to {} bytes: {}", dir, size, e.toString());
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("Spool closed: " + dir);
    }
  }
}
