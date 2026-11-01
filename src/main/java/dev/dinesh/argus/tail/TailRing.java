package dev.dinesh.argus.tail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One tenant's recent tail events ({@code P4_PLAN.md} D2), numbered by a sequence. Appending never
 * waits: past {@code maxLines} or {@code maxBytes} the oldest events are dropped. Each reader keeps
 * its own cursor (the next sequence it wants), so a slow reader loses the oldest lines without
 * holding up the writer or the other readers. Thread-safe.
 *
 * <p>A ring fed by a {@link TailBus} also keeps each event's bus position (its Kafka offset), and
 * event ids carry that position instead of the sequence: the ring id and the positions are the same
 * on every instance, so a client can resume on another one (P6 D10).
 */
final class TailRing {

  /**
   * What one {@link #read} returned.
   *
   * @param events events from {@code first} on, in order
   * @param first the sequence of {@code events.get(0)} (or of the next event if none)
   * @param dropped events the reader wanted that the ring no longer holds
   * @param ids each event's id, as sent to clients
   */
  record Read(List<TailEvent> events, long first, long dropped, List<String> ids) {
    long next() {
      return first + events.size();
    }
  }

  private final String id;
  private final TailEvent[] slots;

  /** Each slot's bus position; null for a ring fed directly. */
  private final long[] positions;

  private final long maxBytes;

  /** Sequence of the oldest event held, and of the next event to append. */
  private long oldest;

  private long next;
  private long bytes;
  private boolean closed;

  TailRing(int maxLines, long maxBytes) {
    this(maxLines, maxBytes, UUID.randomUUID().toString().substring(0, 8), false);
  }

  /** A ring fed by a bus: {@code id} is the same on every instance. */
  TailRing(int maxLines, long maxBytes, String id) {
    this(maxLines, maxBytes, id, true);
  }

  private TailRing(int maxLines, long maxBytes, String id, boolean positioned) {
    this.id = id;
    this.slots = new TailEvent[maxLines];
    this.positions = positioned ? new long[maxLines] : null;
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
    append(events, null);
  }

  /**
   * Appends events delivered by a bus, {@code positions[i]} being {@code events.get(i)}'s
   * (increasing).
   */
  synchronized void append(List<TailEvent> events, long[] positions) {
    if (closed || events.isEmpty()) {
      return;
    }
    for (int i = 0; i < events.size(); i++) {
      TailEvent event = events.get(i);
      // The newest event is always kept, even one larger than maxBytes on its own.
      while (next - oldest >= slots.length
          || (next > oldest && bytes + event.weight() > maxBytes)) {
        evictOldest();
      }
      slots[slot(next)] = event;
      if (this.positions != null) {
        this.positions[slot(next)] = positions == null ? next : positions[i];
      }
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
    List<String> ids = new ArrayList<>(count);
    for (long seq = first; seq < first + count; seq++) {
      events.add(slots[slot(seq)]);
      ids.add(id + "-" + (positions == null ? seq : positions[slot(seq)]));
    }
    return new Read(events, first, dropped, ids);
  }

  /**
   * The cursor just after {@code lastEventId}, or -1 if it can't be resumed here: another ring's id
   * (a restart), or, for a bus-fed ring, a position the ring holds nothing at or before (the gap
   * since may hold lines it never saw, e.g. a reconnect to an instance whose ring is newer).
   */
  synchronized long resume(String lastEventId) {
    int dash = lastEventId.lastIndexOf('-');
    if (dash <= 0 || !lastEventId.substring(0, dash).equals(id)) {
      return -1;
    }
    long last;
    try {
      last = Long.parseLong(lastEventId.substring(dash + 1));
    } catch (NumberFormatException e) {
      return -1;
    }
    if (positions == null) {
      return last + 1;
    }
    if (next == oldest || positions[slot(oldest)] > last) {
      return -1;
    }
    long seq = oldest;
    while (seq < next && positions[slot(seq)] <= last) {
      seq++;
    }
    return seq;
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
