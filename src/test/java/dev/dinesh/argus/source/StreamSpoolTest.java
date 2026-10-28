package dev.dinesh.argus.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.source.StreamSpool.Append;
import dev.dinesh.argus.source.StreamSpool.Pending;
import dev.dinesh.argus.source.StreamSpool.ReadPosition;
import dev.dinesh.argus.source.StreamSpool.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamSpoolTest {

  private static final Instant AT = Instant.parse("2026-10-28T12:00:00Z");
  private static final long MINUTE = 60_000_000_000L;

  @TempDir Path dir;

  private final AtomicLong pendingTotal = new AtomicLong();
  private final AtomicLong nanos = new AtomicLong(1);
  private StreamSpool spool;

  @AfterEach
  void close() throws IOException {
    if (spool != null) {
      spool.close();
    }
  }

  private StreamSpool open(int window, long rotateBytes, Optional<ReadPosition> read)
      throws IOException {
    if (spool != null) {
      spool.close();
    }
    spool =
        StreamSpool.open(dir, new Settings(window, rotateBytes), read, pendingTotal, nanos::get);
    return spool;
  }

  private StreamSpool open() throws IOException {
    return open(16, 1L << 30, Optional.empty());
  }

  private Append append(String lines, String key) throws IOException {
    byte[] bytes = lines.getBytes(StandardCharsets.UTF_8);
    return spool.append(bytes, bytes.length, key, AT);
  }

  private String spoolFile(int number) throws IOException {
    return Files.readString(dir.resolve(String.format("%06d.log", number)));
  }

  @Test
  void appendsAreAcknowledgedInOrderAndCountAsPending() throws IOException {
    open();

    Append first = append("a\nb\n", null);
    Append second = append("c\n", null);

    assertEquals(new Append(1, 0, 4, AT, false), first);
    assertEquals(new Append(1, 4, 6, AT, false), second);
    assertEquals("a\nb\nc\n", spoolFile(1));
    assertEquals(6, spool.pendingBytes());
    assertEquals(6, pendingTotal.get());
    assertThrows(IllegalArgumentException.class, () -> append("no newline", null));
    assertEquals(6, spool.status().bytesOnDisk());
  }

  @Test
  void bytesPastTheLastAcknowledgedAppendAreDroppedAtStartup() throws IOException {
    open();
    append("acked\n", null);
    spool.close();
    // A crash after the lines were written but before their index entry: never acknowledged.
    Files.writeString(dir.resolve("000001.log"), "torn\npart", StandardOpenOption.APPEND);
    Files.writeString(
        dir.resolve(StreamSpool.INDEX_FILE), "{\"spool\":1,\"sta", StandardOpenOption.APPEND);

    open();

    assertEquals("acked\n", spoolFile(1));
    assertEquals(new Append(1, 6, 9, AT, false), append("ok\n", null));
    assertEquals(9, spool.pendingBytes());
  }

  @Test
  void aNewestSpoolFileWithoutAnIndexEntryIsEmptiedAtStartup() throws IOException {
    open();
    append("acked\n", null);
    spool.close();
    Files.writeString(dir.resolve("000002.log"), "never acknowledged\n");

    open();

    assertEquals("", spoolFile(2));
    assertEquals(6, spool.pendingBytes());
  }

  @Test
  void aRepeatedKeyWritesNothingAndReturnsTheOriginalOffsetsEvenAfterARestart() throws IOException {
    open();
    append("one\n", "k1");
    append("two\n", null);
    open();

    Append retry = append("one\n", "k1");

    assertEquals(new Append(1, 0, 4, AT, true), retry);
    assertEquals("one\ntwo\n", spoolFile(1));
    assertEquals(Optional.of(retry), spool.find("k1"));
    assertEquals(Optional.empty(), spool.find("other"));
  }

  @Test
  void onlyTheLatestKeysAreRemembered() throws IOException {
    open(2, 1L << 30, Optional.empty());
    append("1\n", "k1");
    append("2\n", "k2");
    append("3\n", "k3");

    assertFalse(spool.find("k1").isPresent());
    assertTrue(spool.find("k2").isPresent());
    assertFalse(append("1\n", "k1").duplicate());
  }

  @Test
  void theIndexIsCompactedButKeepsKeysAndAcknowledgedLengths() throws IOException {
    open(4, 1L << 30, Optional.empty());
    for (int i = 0; i < 100; i++) {
      append("line " + i + "\n", "k" + i);
    }
    long lines = Files.readAllLines(dir.resolve(StreamSpool.INDEX_FILE)).size();
    assertTrue(lines <= 2 * 4 + 2, "index entries: " + lines);
    long size = Files.size(dir.resolve("000001.log"));

    open(4, 1L << 30, Optional.empty());

    assertTrue(spool.find("k99").isPresent());
    assertFalse(spool.find("k10").isPresent());
    assertEquals(size, Files.size(dir.resolve("000001.log")));
    assertEquals(size, append("next\n", null).startOffset());
  }

  @Test
  void aFullSpoolIsSealedAndDeletedOnceRead() throws IOException {
    open(16, 8, Optional.empty());
    append("12345678\n", null);
    Append next = append("b\n", null);

    assertEquals(2, next.spool());
    Pending first = spool.claim().orElseThrow();
    assertEquals(new Pending(1, dir.resolve("000001.log"), 9, true, 0), first);

    spool.markRead(new ReadPosition(1, 9));

    assertFalse(Files.exists(dir.resolve("000001.log")));
    assertEquals(2, spool.claim().orElseThrow().spool());
    assertEquals(2, pendingTotal.get());
  }

  @Test
  void numbersAreNeverReusedAfterEveryFileIsDeleted() throws IOException {
    open(16, 1, Optional.empty());
    append("a\n", null);
    append("b\n", null);
    spool.markRead(new ReadPosition(2, 2));
    // 000002 is still the active file; 000001 is gone.
    assertFalse(Files.exists(dir.resolve("000001.log")));
    Files.delete(dir.resolve("000002.log"));

    open(16, 1, Optional.of(new ReadPosition(2, 2)));

    assertEquals(3, append("c\n", null).spool());
  }

  @Test
  void aRunIsDueBySizeBySealedFileOrByAge() throws IOException {
    open(16, 1L << 30, Optional.empty());
    assertFalse(spool.due(100, MINUTE));

    append("small\n", null);
    assertFalse(spool.due(100, MINUTE));
    assertTrue(spool.due(6, MINUTE), "segment-bytes waiting");
    nanos.addAndGet(MINUTE);
    assertTrue(spool.due(100, MINUTE), "oldest byte waited the flush interval");

    Pending claim = spool.claim().orElseThrow();
    append("later\n", null);
    assertFalse(spool.due(100, MINUTE), "the clock restarted with the append after the claim");

    spool.release(claim);
    assertTrue(spool.due(100, MINUTE), "an unfinished run keeps the bytes' original age");
  }

  @Test
  void anIdleFullyReadSpoolOfAtLeastOneMegabyteIsRotated() throws IOException {
    open();
    byte[] big = new byte[(int) StreamSpool.IDLE_ROTATE_BYTES];
    Arrays.fill(big, (byte) 'x');
    big[big.length - 1] = '\n';
    spool.append(big, big.length, null, AT);

    spool.markRead(new ReadPosition(1, big.length));

    assertFalse(Files.exists(dir.resolve("000001.log")));
    assertEquals(0, pendingTotal.get());
    assertEquals(2, append("next\n", null).spool());
  }

  @Test
  void closingGivesBackThisSpoolsShareOfTheTotal() throws IOException {
    open();
    append("abc\n", null);
    assertEquals(4, pendingTotal.get());

    spool.close();
    spool = null;

    assertEquals(0, pendingTotal.get());
  }
}
