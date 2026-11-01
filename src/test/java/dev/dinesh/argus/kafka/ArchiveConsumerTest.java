package dev.dinesh.argus.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.dinesh.argus.archive.Archiver;
import dev.dinesh.argus.archive.FileSystemObjectStore;
import dev.dinesh.argus.archive.ParquetTestFiles;
import dev.dinesh.argus.config.ArchiveProperties;
import dev.dinesh.argus.config.KafkaProperties;
import dev.dinesh.argus.kafka.KafkaContainers.Broker;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.sink.LogSink.SegmentWriter;
import dev.dinesh.argus.source.LogBatch;
import dev.dinesh.argus.wal.WalFixture;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The archive consumer against Apache Kafka and Redpanda, compared with the local archiver. */
class ArchiveConsumerTest {

  /** Lines a second apart from 11:59:50, so a 30-line segment spans two hours. */
  private static final Instant T = Instant.parse("2026-10-31T11:59:50Z");

  @TempDir Path dir;

  private ArchiveProperties archive(String name) {
    return new ArchiveProperties(
        ArchiveProperties.Store.FILESYSTEM,
        "argus",
        true,
        Duration.ofMillis(50),
        dir.resolve(name + "-parquet"),
        null,
        Duration.ofMillis(10),
        Duration.ofMillis(80),
        new ArchiveProperties.FileSystem(dir.resolve(name)),
        null,
        null);
  }

  private Archiver archiver(String name, Path walRoot) throws IOException {
    ArchiveProperties props = archive(name);
    Files.createDirectories(props.stagingDir());
    return new Archiver(
        new FileSystemObjectStore(props.filesystem().root()),
        new WalSegments(Files.createDirectories(walRoot), Set.of(WalSink.ARCHIVE), true),
        props,
        WalFixture.MAPPER);
  }

  private ArchiveConsumer consumer(KafkaWal wal) throws IOException {
    return new ArchiveConsumer(
        wal, wal.props(), archiver("kafka", dir.resolve("staging")), ArgusMetrics.noop());
  }

  /** Each archived object's key (under the store root) and row count. */
  private Map<String, Integer> objects(String name) throws IOException {
    Path root = dir.resolve(name);
    Map<String, Integer> objects = new TreeMap<>();
    if (!Files.isDirectory(root)) {
      return objects;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : (Iterable<Path>) files::iterator) {
        if (f.toString().endsWith(".parquet")) {
          objects.put(root.relativize(f).toString(), ParquetTestFiles.rows(f).size());
        }
      }
    }
    return objects;
  }

  private static void await(String what, BooleanSupplier done) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (!done.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      Thread.sleep(100);
    }
  }

  private int rows(String name) {
    try {
      return objects(name).values().stream().mapToInt(Integer::intValue).sum();
    } catch (IOException e) {
      return -1;
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void archivesTheSameObjectsAsTheLocalArchiver(Broker broker) throws Exception {
    Path localWal = dir.resolve("wal");
    WalFixture local = new WalFixture(localWal);
    local.segment("acme", "app", 0, 0, 30, T);
    local.segment("acme", "app", 0, 300, 4, T.plus(Duration.ofHours(3)));
    local.segment("globex", "web", 1, 0, 10, null);
    archiver("local", localWal).runOnce();
    Map<String, Integer> expected = objects("local");
    // app: hour 11 and hour 12, then hour 14; web: dt=unknown
    assertEquals(4, expected.size(), expected.toString());

    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir);
    try (KafkaWal wal = new KafkaWal(props)) {
      WalFixture kafka = new WalFixture(new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop()));
      kafka.write("acme", "app", 0, 0, 30, T);
      kafka.write("acme", "app", 0, 300, 4, T.plus(Duration.ofHours(3)));
      kafka.write("globex", "web", 1, 0, 10, null);

      ArchiveConsumer consumer = consumer(wal);
      consumer.start();
      try {
        await("44 rows archived", () -> rows("kafka") == 44);
        assertEquals(expected, objects("kafka"));
      } finally {
        consumer.stop();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aSegmentSentAgainAfterACrashIsArchivedOnce(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir);
    try (KafkaWal wal = new KafkaWal(props)) {
      KafkaSink sink = new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop());
      // First attempt: three lines, then the producer dies (no marker).
      SegmentWriter crashed = sink.open("acme", "app", 0, 0);
      crashed.write(
          new LogBatch(
              "app",
              0,
              0,
              WalFixture.envelopes("acme", "app", 0, 0, WalFixture.times(3, T)),
              0,
              30,
              false,
              0,
              0,
              0));
      wal.producer().flush();
      // The restart replays the segment from its checkpoint, in full.
      new WalFixture(sink).write("acme", "app", 0, 0, 8, T);

      ArchiveConsumer consumer = consumer(wal);
      consumer.start();
      try {
        await("8 rows archived", () -> rows("kafka") == 8);
        Thread.sleep(500);
        assertEquals(8, rows("kafka"));
      } finally {
        consumer.stop();
      }
    }
  }

  private static LogBatch batch(String source, long start, int lines, Instant first, long end) {
    return new LogBatch(
        source,
        0,
        start,
        WalFixture.envelopes("acme", source, 0, start, WalFixture.times(lines, first)),
        start,
        end,
        false,
        0,
        0,
        0);
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aRestartInsideAnArchivedSegmentDoesNotArchiveItsTailOverIt(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir, 1, 1000);
    try (KafkaWal wal = new KafkaWal(props)) {
      KafkaSink sink = new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop());
      // app's lines straddle slow's first record, where the first consumer's commit stops.
      SegmentWriter app = sink.open("acme", "app", 0, 0);
      app.write(batch("app", 0, 2, T, 20));
      wal.producer().flush();
      SegmentWriter slow = sink.open("acme", "slow", 0, 0);
      slow.write(batch("slow", 0, 5, T, 50));
      wal.producer().flush();
      app.write(batch("app", 20, 1, T.plusSeconds(2), 30));
      app.commit(30);

      ArchiveConsumer first = consumer(wal);
      first.start();
      try {
        await("app archived", () -> rows("kafka") == 3);
        Thread.sleep(500); // past a commit-interval
      } finally {
        first.stop();
      }
      Map<String, Integer> archived = objects("kafka");

      slow.commit(50);
      ArchiveConsumer second = consumer(wal);
      second.start();
      try {
        await("slow archived after the restart", () -> rows("kafka") == 8);
        Thread.sleep(500);
      } finally {
        second.stop();
      }
      Map<String, Integer> after = objects("kafka");
      archived.forEach((key, rows) -> assertEquals(rows, after.get(key), key));
      assertEquals(8, rows("kafka"));
    }
  }

  @ParameterizedTest
  @EnumSource(Broker.class)
  void aRestartReadsAgainEverySegmentNotYetArchived(Broker broker) throws Exception {
    KafkaProperties props = KafkaContainers.props(broker, KafkaContainers.topic(), dir, 1, 1000);
    try (KafkaWal wal = new KafkaWal(props)) {
      KafkaSink sink = new KafkaSink(wal, WalFixture.MAPPER, ArgusMetrics.noop());
      SegmentWriter open = sink.open("acme", "slow", 0, 0);
      open.write(
          new LogBatch(
              "slow",
              0,
              0,
              WalFixture.envelopes("acme", "slow", 0, 0, WalFixture.times(5, T)),
              0,
              50,
              false,
              0,
              0,
              0));
      new WalFixture(sink).write("acme", "app", 0, 0, 3, T);

      ArchiveConsumer first = consumer(wal);
      first.start();
      try {
        await("app archived", () -> rows("kafka") == 3);
        Thread.sleep(500); // past a commit-interval: the commit stops at slow's first record
      } finally {
        first.stop();
      }

      assertEquals("kafka:", open.commit(50).substring(0, 6));
      ArchiveConsumer second = consumer(wal);
      second.start();
      try {
        await("slow archived after the restart", () -> rows("kafka") == 8);
      } finally {
        second.stop();
      }
      assertFalse(
          Files.exists(dir.resolve("staging/tenant=acme/dt=kafka/source=slow/gen=0/0.staging")));
      List<String> keys = List.copyOf(objects("kafka").keySet());
      assertEquals(2, keys.size(), keys.toString());
    }
  }
}
