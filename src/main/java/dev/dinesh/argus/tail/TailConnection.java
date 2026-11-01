package dev.dinesh.argus.tail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.TailProperties;
import dev.dinesh.argus.metrics.ArgusMetrics;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * One tail client ({@code P4_PLAN.md} D2–D3): a cursor into its tenant's ring, read by its own
 * thread, which filters, rate-limits and writes batched SSE frames. A slow client blocks only this
 * thread; the ring overtakes its cursor and the client is told how many lines it missed.
 */
@Slf4j
public final class TailConnection {

  /** Events taken from the ring per wakeup: one write and one flush for all of them. */
  static final int READ_BATCH = 512;

  /** Lines a connection may look at per second, as a multiple of the lines it may send. */
  static final int EXAMINED_PER_SENT = 50;

  private final String id;
  private final String tenantId;
  private final TailFilter filter;
  private final TailRing ring;
  private final TailWriter writer;
  private final TailProperties props;
  private final ObjectMapper mapper;
  private final ArgusMetrics metrics;
  private final Runnable onClose;
  private final String greeting;
  private final long startNanos = System.nanoTime();
  private final AtomicReference<String> closeReason = new AtomicReference<>();
  private volatile Thread thread;

  private long cursor;
  private long sent;
  private long dropped;

  TailConnection(
      String id,
      String tenantId,
      TailFilter filter,
      TailRing ring,
      long cursor,
      String greeting,
      TailWriter writer,
      TailProperties props,
      ObjectMapper mapper,
      ArgusMetrics metrics,
      Runnable onClose) {
    this.id = id;
    this.tenantId = tenantId;
    this.filter = filter;
    this.ring = ring;
    this.cursor = cursor;
    this.greeting = greeting;
    this.writer = writer;
    this.props = props;
    this.mapper = mapper;
    this.metrics = metrics;
    this.onClose = onClose;
  }

  public String id() {
    return id;
  }

  public String tenantId() {
    return tenantId;
  }

  void start(Thread sender) {
    thread = sender;
    sender.start();
  }

  /** Ends the connection from another thread (timeout, client gone, shutdown); idempotent. */
  public void close(String reason) {
    if (closeReason.compareAndSet(null, reason)) {
      Thread sender = thread;
      if (sender != null && sender != Thread.currentThread()) {
        sender.interrupt();
      }
    }
  }

  boolean isClosed() {
    return closeReason.get() != null;
  }

  /** The sender thread's loop. */
  void run() {
    Throwable error = null;
    try {
      send();
    } catch (InterruptedException e) {
      closeReason.compareAndSet(null, "interrupted");
    } catch (IOException e) {
      closeReason.compareAndSet(null, "client_gone");
    } catch (RuntimeException | Error e) {
      error = e;
      closeReason.compareAndSet(null, "failed");
    } finally {
      writer.close();
      onClose.run();
      // Canonical line: one per connection.
      log.info(
          "Tail connection finished tenant={} connection={} filter=[{}] sent={} dropped={}"
              + " reason={} error={} durationMs={}",
          tenantId,
          id,
          filter,
          sent,
          dropped,
          closeReason.get(),
          error == null ? null : error.toString(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
  }

  private void send() throws IOException, InterruptedException {
    long heartbeatNanos = props.heartbeat().toNanos();
    int maxSent = props.maxLinesPerSecond();
    int maxExamined = maxSent * EXAMINED_PER_SENT;
    write(": connected\n\n" + greeting);
    long lastWrite = System.nanoTime();
    long windowStart = lastWrite;
    int sentInWindow = 0;
    int examinedInWindow = 0;
    long pendingDropped = 0;
    while (!isClosed()) {
      long now = System.nanoTime();
      if (now - windowStart >= TimeUnit.SECONDS.toNanos(1)) {
        windowStart = now;
        sentInWindow = 0;
        examinedInWindow = 0;
      }
      if (sentInWindow >= maxSent || examinedInWindow >= maxExamined) {
        // Over the rate: wait out the second, then skip to the newest line. What was skipped is
        // reported as dropped, like lines the ring overtook.
        TimeUnit.NANOSECONDS.sleep(windowStart + TimeUnit.SECONDS.toNanos(1) - now);
        long newest = ring.next();
        pendingDropped += Math.max(0, newest - cursor);
        cursor = Math.max(cursor, newest);
        continue;
      }
      // Lines just skipped are reported now, not at the next line or heartbeat.
      long waitMillis =
          pendingDropped > 0
              ? 0
              : Math.max(1, TimeUnit.NANOSECONDS.toMillis(heartbeatNanos - (now - lastWrite)));
      TailRing.Read read = ring.read(cursor, READ_BATCH, waitMillis);
      if (read == null) {
        closeReason.compareAndSet(null, "shutdown");
        return;
      }
      StringBuilder out = new StringBuilder();
      pendingDropped += read.dropped();
      if (pendingDropped > 0) {
        out.append("event: dropped\ndata: {\"dropped\":").append(pendingDropped).append("}\n\n");
      }
      long droppedNow = pendingDropped;
      pendingDropped = 0;
      long sentNow = 0;
      for (int i = 0; i < read.events().size(); i++) {
        TailEvent event = read.events().get(i);
        examinedInWindow++;
        if (!filter.matches(event)) {
          continue;
        }
        if (sentInWindow >= maxSent) {
          pendingDropped++;
          continue;
        }
        appendEvent(out, read.ids().get(i), event);
        sentInWindow++;
        sentNow++;
      }
      cursor = read.next();
      if (out.isEmpty() && System.nanoTime() - lastWrite >= heartbeatNanos) {
        out.append(":\n\n");
      }
      if (!out.isEmpty()) {
        write(out.toString());
        lastWrite = System.nanoTime();
        sent += sentNow;
        dropped += droppedNow;
        metrics.tailSent(tenantId, sentNow);
        metrics.tailDropped(tenantId, droppedNow);
      }
    }
  }

  private void appendEvent(StringBuilder out, String eventId, TailEvent event)
      throws JsonProcessingException {
    // JSON escapes newlines, so the event is one data line.
    out.append("id: ")
        .append(eventId)
        .append("\ndata: ")
        .append(mapper.writeValueAsString(event))
        .append("\n\n");
  }

  private void write(String frames) throws IOException {
    if (isClosed()) {
      return;
    }
    writer.write(frames);
  }
}
