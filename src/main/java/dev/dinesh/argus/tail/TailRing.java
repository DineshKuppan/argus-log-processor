package dev.dinesh.argus.tail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One tenant's recent tail events ({@code P4_PLAN.md} D2), numbered by a sequence. Appending never
 * waits: past {@code maxLines} or {@code maxBytes} the oldest events are dropped. Each reader keeps
 * its own cursor (the next sequence it wants), so a slow reader loses the oldest lines without
 * holding up the writer or the other readers. Thread-safe.
 */
final class TailRing {

  /**
   * What one {@link #read} returned.
   *
   * @param events events from {@code first} on, in order
   * @param first the sequence of {@code events.get(0)} (or of the next event if none)
   * @param dropped events the reader wanted that the ring no longer holds
   */
  record Read(List<TailEvent> events, long first, long dropped) {
    long next() {
      return first + events.size();
    }
  }

  private final String id = UUID.randomUUID().toString().substring(0, 8);
  private final TailEvent[] slots;
  private final long maxBytes;

  /** Sequence of the oldest event held, and of the next event to append. */
  private long oldest;

  private long next;
  private long bytes;
  private boolean closed;

  TailRing(int maxLines, long maxBytes) {
    this.slots = new TailEvent[maxLines];
    this.maxBytes = maxBytes;
  }

  /** Identifies this ring in event ids, so a resume against another ring (a restart) is seen. */
  String id() {
    return id;
  }

  synchronized long next() {
    return next;
  }

  synchronized long bytes() {
    return bytes;
  }

  synchronized int size() {
    return (int) (next - oldest);
  }

  /** Appends {@code events}, dropping the oldest held events to stay within the bounds. */
  synchronized void append(List<TailEvent> events) {
    if (closed || events.isEmpty()) {
      return;
    }
    for (TailEvent event : events) {
      // The newest event is always kept, even one larger than maxBytes on its own.
      while (next - oldest >= slots.length
          || (next > oldest && bytes + event.weight() > maxBytes)) {
        evictOldest();
      }
      slots[slot(next)] = event;
      bytes += event.weight();
      next++;
    }
    notifyAll();
  }

  /**
   * Returns up to {@code max} events from {@code cursor} on, waiting up to {@code waitMillis} while
   * there are none.
   *
   * @return null once the ring is closed
   */
  synchronized Read read(long cursor, int max, long waitMillis) throws InterruptedException {
    long deadline = System.nanoTime() + waitMillis * 1_000_000;
    while (!closed && cursor >= next) {
      long left = (deadline - System.nanoTime()) / 1_000_000;
      if (left <= 0) {
        break;
      }
      wait(left);
    }
    if (closed) {
      return null;
    }
    long first = Math.max(cursor, oldest);
    long dropped = first - cursor;
    int count = (int) Math.max(0, Math.min(max, next - first));
    List<TailEvent> events = new ArrayList<>(count);
    for (long seq = first; seq < first + count; seq++) {
      events.add(slots[slot(seq)]);
    }
    return new Read(events, first, dropped);
  }

  /** Ends every wait; reads then return null. */
  synchronized void close() {
    closed = true;
    notifyAll();
  }

  private void evictOldest() {
    int slot = slot(oldest);
    bytes -= slots[slot].weight();
    slots[slot] = null;
    oldest++;
  }

  private int slot(long seq) {
    return (int) (seq % slots.length);
  }
}
