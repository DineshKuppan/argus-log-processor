package dev.dinesh.argus.tail;

import static dev.dinesh.argus.tail.TailRingTest.event;
import static dev.dinesh.argus.tail.TailRingTest.events;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.config.TailProperties;
import dev.dinesh.argus.metrics.ArgusMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class TailHubTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final Pattern ID = Pattern.compile("id: (\\S+)");
  private static final Pattern DROPPED = Pattern.compile("\"dropped\":(\\d+)");

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private TailHub hub;

  @AfterEach
  void stop() {
    if (hub != null) {
      hub.stop();
    }
  }

  private TailHub hub(int perTenant, int perNode, int linesPerSecond, Duration heartbeat) {
    hub =
        new TailHub(
            new TailProperties(
                perNode,
                perTenant,
                linesPerSecond,
                null,
                heartbeat,
                1000,
                DataSize.ofMegabytes(1),
                Duration.ofMillis(50)),
            MAPPER,
            new ArgusMetrics(meters));
    hub.start();
    return hub;
  }

  private TailHub hub() {
    return hub(4, 8, 10_000, Duration.ofSeconds(15));
  }

  /** Collects what a connection writes. */
  static final class Recorder implements TailWriter {
    final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final StringBuilder all = new StringBuilder();
    volatile boolean closed;

    @Override
    public void write(String text) {
      frames.add(text);
    }

    @Override
    public void close() {
      closed = true;
    }

    /** Everything written until {@code expected} appears, or fails after 5s. */
    String awaitContaining(String expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (all.indexOf(expected) < 0) {
        String next = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (next == null) {
          throw new AssertionError("never received " + expected + " in: " + all);
        }
        all.append(next);
      }
      return all.toString();
    }

    String drain(long millis) throws InterruptedException {
      Thread.sleep(millis);
      List<String> rest = new ArrayList<>();
      frames.drainTo(rest);
      rest.forEach(all::append);
      return all.toString();
    }
  }

  private static List<String> ids(String text) {
    List<String> ids = new ArrayList<>();
    Matcher m = ID.matcher(text);
    while (m.find()) {
      ids.add(m.group(1));
    }
    return ids;
  }

  private static long dropped(String text) {
    long total = 0;
    Matcher m = DROPPED.matcher(text);
    while (m.find()) {
      total += Long.parseLong(m.group(1));
    }
    return total;
  }

  @Test
  void nothingIsKeptForATenantNobodyTails() {
    TailHub hub = hub();
    assertFalse(hub.isWatched("acme"));
    hub.publish("acme", events(3));
    assertFalse(hub.isWatched("acme"));
  }

  @Test
  void aConnectionReceivesItsTenantsNewLinesOnly() throws Exception {
    TailHub hub = hub();
    hub.open("globex", TailFilter.ALL, null, new Recorder());
    Recorder acme = new Recorder();
    hub.open("acme", TailFilter.ALL, null, acme);
    acme.awaitContaining(": connected");
    assertTrue(hub.isWatched("acme"));

    hub.publish("globex", List.of(event("their secret", 9, 10)));
    hub.publish("acme", List.of(event("disk full", 17, 10)));

    String text = acme.awaitContaining("disk full");
    assertFalse(text.contains("their secret"));
    assertTrue(text.contains("\"severityNumber\":17"));
    assertEquals(1, ids(text).size());
  }

  @Test
  void filtersRunOnTheConnection() throws Exception {
    TailHub hub = hub();
    Recorder recorder = new Recorder();
    hub.open("acme", TailFilter.parse("warn", null, "DISK"), null, recorder);
    recorder.awaitContaining(": connected");

    hub.publish(
        "acme",
        List.of(
            event("disk ok", 9, 10),
            event("Disk full", 17, 10),
            event("cpu hot", 13, 10),
            event("disk slow", 13, 10)));

    String text = recorder.awaitContaining("disk slow");
    assertTrue(text.contains("Disk full"));
    assertFalse(text.contains("disk ok"));
    assertFalse(text.contains("cpu hot"));
    assertThrows(IllegalArgumentException.class, () -> TailFilter.parse("loud", null, null));
  }

  @Test
  void connectionLimitsPerTenantAndPerNode() {
    TailHub hub = hub(1, 2, 100, Duration.ofSeconds(15));
    hub.open("acme", TailFilter.ALL, null, new Recorder());
    assertThrows(
        TailLimitException.class, () -> hub.open("acme", TailFilter.ALL, null, new Recorder()));
    hub.open("globex", TailFilter.ALL, null, new Recorder());
    assertThrows(
        TailLimitException.class, () -> hub.open("initech", TailFilter.ALL, null, new Recorder()));
    assertEquals(2, hub.connections());
  }

  /** A client that never reads blocks its own thread only; it then learns what it missed. */
  @Test
  void aStalledClientNeverBlocksPublishingAndIsToldWhatItMissed() throws Exception {
    TailHub hub = hub();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch writing = new CountDownLatch(1);
    Recorder after = new Recorder();
    TailWriter stalled =
        new TailWriter() {
          boolean first = true;

          @Override
          public void write(String frames) throws IOException {
            if (first) {
              first = false;
              return; // the greeting
            }
            writing.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            after.write(frames);
          }

          @Override
          public void close() {}
        };
    hub.open("acme", TailFilter.ALL, null, stalled);
    hub.publish("acme", events(1));
    assertTrue(writing.await(5, TimeUnit.SECONDS), "the client is now stalled in a write");

    long start = System.nanoTime();
    for (int i = 0; i < 50; i++) {
      hub.publish("acme", events(1000));
    }
    long publishMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    release.countDown();

    String text = after.awaitContaining("\"dropped\"");
    assertTrue(publishMillis < 1000, "publishing 50,000 lines took " + publishMillis + "ms");
    assertTrue(dropped(text) >= 49_000, text.length() + " chars, dropped " + dropped(text));
  }

  @Test
  void theRateCapSkipsAheadAndReportsTheRestAsDropped() throws Exception {
    TailHub hub = hub(4, 8, 5, Duration.ofSeconds(15));
    Recorder recorder = new Recorder();
    hub.open("acme", TailFilter.ALL, null, recorder);
    recorder.awaitContaining(": connected");

    hub.publish("acme", events(100));
    String text = recorder.drain(1500);

    assertTrue(ids(text).size() <= 10, ids(text).size() + " sent");
    assertEquals(100, ids(text).size() + dropped(text), text);
    assertEquals(ids(text).size(), meters.get("argus.tail.lines.sent").counter().count());
  }

  @Test
  void lastEventIdResumesAfterTheLastLineSeenAndAnUnknownIdResets() throws Exception {
    TailHub hub = hub();
    Recorder first = new Recorder();
    TailConnection connection = hub.open("acme", TailFilter.ALL, null, first);
    first.awaitContaining(": connected");
    hub.publish("acme", List.of(event("one", 9, 10)));
    String seen = ids(first.awaitContaining("one")).get(0);
    connection.close("test");

    hub.publish("acme", List.of(event("two", 9, 10), event("three", 9, 10)));
    Recorder resumed = new Recorder();
    hub.open("acme", TailFilter.ALL, seen, resumed);
    String text = resumed.awaitContaining("three");
    assertTrue(text.contains("two"));
    assertFalse(text.contains("\"one\""));

    Recorder reset = new Recorder();
    hub.open("acme", TailFilter.ALL, "other-7", reset);
    assertTrue(reset.awaitContaining("event: reset").contains("reset"));
  }

  @Test
  void anIdleConnectionGetsHeartbeats() throws Exception {
    TailHub hub = hub(4, 8, 100, Duration.ofMillis(50));
    Recorder recorder = new Recorder();
    hub.open("acme", TailFilter.ALL, null, recorder);
    assertTrue(recorder.awaitContaining(":\n\n").contains(": connected"));
  }

  @Test
  void aRingIsReleasedAfterItsLastConnectionHasBeenIdle() throws Exception {
    TailHub hub = hub();
    Recorder recorder = new Recorder();
    TailConnection connection = hub.open("acme", TailFilter.ALL, null, recorder);
    recorder.awaitContaining(": connected");
    connection.close("test");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (hub.isWatched("acme") && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertFalse(hub.isWatched("acme"));
    assertEquals(0, hub.connections());
    assertTrue(recorder.closed);
  }

  @Test
  void stoppingClosesEveryConnection() throws Exception {
    TailHub hub = hub();
    Recorder recorder = new Recorder();
    hub.open("acme", TailFilter.ALL, null, recorder);
    recorder.awaitContaining(": connected");

    hub.stop();

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!recorder.closed && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(recorder.closed);
    assertThrows(
        TailLimitException.class, () -> hub.open("acme", TailFilter.ALL, null, new Recorder()));
  }
}
