package dev.dinesh.argus.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.dinesh.argus.config.KafkaProperties;
import dev.dinesh.argus.config.TailProperties;
import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.tail.TailEvent;
import dev.dinesh.argus.tail.TailFilter;
import dev.dinesh.argus.tail.TailHub;
import dev.dinesh.argus.tail.TailWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Two instances' hubs sharing one tail topic, on Apache Kafka and Redpanda. */
class KafkaTailBusTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final Pattern ID =
      Pattern.compile("id: (\\S+)\\ndata: [^\\n]*\"body\":\"([^\"]+)\"");

  @TempDir Path dir;

  private final List<AutoCloseable> closing = new ArrayList<>();

  @AfterEach
  void close() throws Exception {
    for (int i = closing.size() - 1; i >= 0; i--) {
      closing.get(i).close();
    }
  }

  /** One instance: its hub and bus. */
  private TailHub instance(Broker broker, KafkaProperties props, String id) {
    TailHub hub = new TailHub(TailProperties.defaults(), MAPPER, ArgusMetrics.noop());
    hub.start();
    KafkaWal wal = new KafkaWal(props, id);
    KafkaTailBus bus = new KafkaTailBus(wal, hub, MAPPER, ArgusMetrics.noop());
    bus.start();
    closing.add(wal);
    closing.add(hub::stop);
    closing.add(bus::stop);
    return hub;
  }

  private static TailEvent event(String body) {
    return new TailEvent(
        null, "app", "json", null, null, null, null, 9, body, null, null, null, null, 10);
  }

  /** What a connection writes. */
  static final class Recorder implements TailWriter {
    final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    final StringBuilder all = new StringBuilder();

    @Override
    public void write(String text) {
      frames.add(text);
    }

    @Override
    public void close() {}

    String awaitContaining(String expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (all.indexOf(expected) < 0) {
        String next = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (next == null) {
          throw new AssertionError("never received " + expected + " in: " + all);
        }
        all.append(next);
      }
      return all.toString();
    }

    /** The id of the event whose body is {@code body}. */
    String idOf(String body) {
      Matcher m = ID.matcher(all);
      while (m.find()) {
        if (m.group(2).equals(body)) {
          return m.group(1);
        }
      }
      throw new AssertionError("no event " + body + " in: " + all);
    }
  }

  private static void await(String what, BooleanSupplier done) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!done.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      Thread.sleep(50);
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aLinePublishedOnOneInstanceReachesATailOnAnother(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir);
    props = withTailTopic(props, "tail-" + KafkaContainers.topic());
    TailHub a = instance(broker, props, "argus-0");
    TailHub b = instance(broker, props, "argus-1");

    Recorder onB = new Recorder();
    await("b attached", () -> attached(b));
    b.open("acme", TailFilter.ALL, null, onB);
    onB.awaitContaining(": connected");
    await("a sees b's watch", () -> a.isWatched("acme"));
    assertFalse(a.isWatched("globex"), "taps stay off for tenants nobody tails");

    a.publish("acme", List.of(event("from-a")));
    a.publish("globex", List.of(event("not-watched")));

    String text = onB.awaitContaining("from-a");
    assertTrue(onB.idOf("from-a").matches("p\\d+-\\d+"), text);
    assertFalse(text.contains("not-watched"));
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aClientResumesOnAnotherInstanceWithItsLastEventId(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir);
    props = withTailTopic(props, "tail-" + KafkaContainers.topic());
    TailHub a = instance(broker, props, "argus-0");
    TailHub b = instance(broker, props, "argus-1");
    await("attached", () -> attached(a) && attached(b));

    Recorder onA = new Recorder();
    a.open("acme", TailFilter.ALL, null, onA);
    Recorder onB = new Recorder();
    b.open("acme", TailFilter.ALL, null, onB);
    await("both watched", () -> a.isWatched("acme") && b.isWatched("acme"));

    b.publish("acme", List.of(event("one")));
    onA.awaitContaining("one");
    onB.awaitContaining("one");
    String seen = onB.idOf("one");
    assertEquals(onA.idOf("one"), seen, "the same id on every instance");
    b.publish("acme", List.of(event("two")));
    onA.awaitContaining("two");

    // B's client reconnects to A.
    Recorder resumed = new Recorder();
    a.open("acme", TailFilter.ALL, seen, resumed);
    String text = resumed.awaitContaining("two");
    assertFalse(text.contains("\"one\""));
    assertFalse(text.contains("event: reset"));
  }

  /** The bus attaches once it knows the topic's partitions. */
  private static boolean attached(TailHub hub) {
    return hub.shared();
  }

  private static KafkaProperties withTailTopic(KafkaProperties p, String tailTopic) {
    return new KafkaProperties(
        p.bootstrapServers(),
        p.topic(),
        p.createTopics(),
        p.checkTopic(),
        p.partitions(),
        p.replicationFactor(),
        p.minRetention(),
        p.maxOutage(),
        p.maxLagRecords(),
        p.orphanTimeout(),
        p.commitInterval(),
        p.hotLinger(),
        p.retryInitial(),
        p.retryMax(),
        p.stagingDir(),
        p.properties(),
        tailTopic);
  }
}
