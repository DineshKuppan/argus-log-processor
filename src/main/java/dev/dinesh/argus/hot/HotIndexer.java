package dev.dinesh.argus.hot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.luben.zstd.ZstdIOException;
import com.github.luben.zstd.ZstdInputStream;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Response;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.wal.WalSegments;
import dev.dinesh.argus.wal.WalSegments.Segment;
import dev.dinesh.argus.wal.WalSink;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Indexes committed WAL segments into the hot tier ({@code P5_PLAN.md} D2): one thread, oldest
 * segment first, each line a {@code _bulk} {@code index} action with {@code _id = eventId}, so a
 * retry, a re-run after a crash or a superseded segment's replacement overwrites rather than
 * duplicates. A segment is acknowledged ({@link WalSink#HOT}) once each of its lines was indexed,
 * rejected by the engine (permanent; the cold tier keeps it) or older than the retention. Any other
 * failure stops the pass and backs off, like the {@code Archiver}; within the same process a failed
 * segment resumes at its last acknowledged bulk.
 */
@Slf4j
public class HotIndexer implements SmartLifecycle {

  /** What happened to one segment. */
  public enum Outcome {
    INDEXED,
    /** Replaced in the WAL by a replay while it was indexed; the replacement goes next. */
    SUPERSEDED,
    /** Not valid NDJSON (or zstd); set aside, as the archiver does. */
    UNREADABLE,
    FAILED
  }

  /** Item retries within a segment for 429 / 5xx items, waiting 1s, 2s, 4s. */
  static final int ITEM_ATTEMPTS = 4;

  private static final Duration RETENTION_EVERY = Duration.ofHours(1);
  private static final int MAX_BACKOFF_DOUBLINGS = 30;
  private static final int READ_BUFFER = 64 * 1024;
  private static final int ERROR_CHARS = 300;
  private static final long STOP_TIMEOUT_SECONDS = 30;
  private static final String FILTER =
      "filter_path=errors,items.*.status,items.*.error.type,items.*.error.reason";

  private final SearchEngineClient client;
  private final WalSegments wal;
  private final HotIndices indices;
  private final HotTemplates templates;
  private final HotRetention retention;
  private final HotProperties props;
  private final ObjectMapper mapper;
  private final ObjectReader envelopes;
  private final ArgusMetrics metrics;
  private final Clock clock;
  private final Duration itemRetryBase;

  private volatile boolean running;
  private ScheduledExecutorService worker;

  // Read and written only by the worker thread.
  private boolean prepared;
  private Instant lastRetention = Instant.MIN;
  private int consecutiveFailures;
  private Path resumeFile;
  private long resumeLines;

  public HotIndexer(
      SearchEngineClient client,
      WalSegments wal,
      HotIndices indices,
      HotProperties props,
      ArgusMetrics metrics) {
    this(client, wal, indices, props, metrics, Clock.systemUTC(), Duration.ofSeconds(1));
  }

  HotIndexer(
      SearchEngineClient client,
      WalSegments wal,
      HotIndices indices,
      HotProperties props,
      ArgusMetrics metrics,
      Clock clock,
      Duration itemRetryBase) {
    this.client = client;
    this.wal = wal;
    this.indices = indices;
    this.templates = new HotTemplates(client, indices, props);
    this.retention = new HotRetention(client, indices);
    this.props = props;
    this.mapper = client.mapper();
    this.envelopes = mapper.readerFor(LogEnvelope.class);
    this.metrics = metrics;
    this.clock = clock;
    this.itemRetryBase = itemRetryBase;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    worker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "hot-indexer");
              t.setDaemon(true);
              return t;
            });
    running = true;
    worker.execute(this::loop);
    log.info("Hot indexer started: {} → {} ({})", wal.root(), client.base(), indices.pattern());
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    worker.shutdownNow();
    try {
      if (!worker.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("Hot indexer did not stop within {}s", STOP_TIMEOUT_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** One pass, then schedules the next. Catches {@link Throwable}, as the archiver does. */
  private void loop() {
    Duration next;
    try {
      if (runOnce()) {
        consecutiveFailures = 0;
        next = props.scanInterval();
      } else {
        next = backoff(++consecutiveFailures);
      }
    } catch (InterruptedException e) {
      return;
    } catch (Throwable t) {
      log.error("Hot index pass failed", t);
      next = backoff(++consecutiveFailures);
    }
    if (consecutiveFailures > 0) {
      log.warn(
          "Hot index pass incomplete ({} in a row); retrying in {}ms",
          consecutiveFailures,
          next.toMillis());
    }
    if (running) {
      worker.schedule(this::loop, next.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Prepares the cluster if needed, runs retention hourly, then indexes every pending segment,
   * oldest first, stopping at the first failure. True if the pass completed.
   */
  public boolean runOnce() throws IOException, InterruptedException {
    if (!prepared) {
      HotTemplates.requireSupported(client.engine(), props.store());
      templates.install();
      prepared = true;
    }
    Instant now = clock.instant();
    if (Duration.between(lastRetention, now).compareTo(RETENTION_EVERY) >= 0) {
      try {
        retention.runOnce(now);
        lastRetention = now;
      } catch (IOException e) {
        log.warn("Hot tier retention failed; retrying next pass: {}", e.toString());
      }
    }
    for (Segment segment : wal.pending(WalSink.HOT)) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException();
      }
      if (index(segment) == Outcome.FAILED) {
        return false;
      }
    }
    return true;
  }

  /** Age of the oldest segment not yet indexed, in seconds; 0 when none waits. */
  public double lagSeconds() {
    try {
      List<Segment> pending = wal.pending(WalSink.HOT);
      return pending.isEmpty()
          ? 0
          : Math.max(0, clock.millis() - pending.get(0).modified().toMillis()) / 1e3;
    } catch (IOException | RuntimeException e) {
      return 0;
    }
  }

  /** Counts for one segment, for its canonical line. */
  private static final class Tally {
    long lines;
    long indexed;
    long rejected;
    long skipped;
    int bulks;
    int retries;
    String firstRejection;
  }

  Outcome index(Segment segment) throws InterruptedException {
    long start = System.nanoTime();
    Tally tally = new Tally();
    Outcome outcome = Outcome.FAILED;
    Throwable error = null;
    long resumeAt = segment.file().equals(resumeFile) ? resumeLines : 0;
    try {
      outcome = indexLines(segment, resumeAt, tally);
      if (outcome == Outcome.INDEXED) {
        resumeFile = null;
        if (!wal.exists(segment)) {
          outcome = Outcome.SUPERSEDED;
        } else {
          wal.acknowledge(segment, WalSink.HOT);
        }
      }
    } catch (InterruptedException e) {
      throw e;
    } catch (JsonProcessingException | ZstdIOException e) {
      error = e;
      outcome = wal.exists(segment) ? setAside(segment) : Outcome.SUPERSEDED;
    } catch (Exception | Error e) {
      error = e;
      outcome = Outcome.FAILED;
    } finally {
      // Canonical line: one per segment (guardrail 9).
      log.info(
          "Hot segment finished tenant={} source={} generation={} startOffset={} endOffset={}"
              + " outcome={} lines={} resumedAt={} indexed={} rejected={} outsideRetention={}"
              + " bulks={} itemRetries={} firstRejection={} error={} durationMs={}",
          segment.tenantId(),
          segment.sourceId(),
          segment.generation(),
          segment.startOffset(),
          segment.endOffset(),
          outcome,
          tally.lines,
          resumeAt,
          tally.indexed,
          tally.rejected,
          tally.skipped,
          tally.bulks,
          tally.retries,
          tally.firstRejection,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      metrics.hotSegment(
          segment.tenantId(), outcome.name(), tally.indexed, tally.rejected, tally.skipped);
    }
    return outcome;
  }

  private Outcome indexLines(Segment segment, long resumeAt, Tally tally)
      throws IOException, InterruptedException {
    Instant now = clock.instant();
    LocalDate oldestKept = indices.oldestKept(now);
    Bulk bulk = new Bulk();
    try (BufferedReader reader = open(segment)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        tally.lines++;
        if (tally.lines <= resumeAt) {
          continue;
        }
        LogEnvelope e = envelopes.readValue(line);
        LocalDate day = HotIndices.day(e, now);
        if (day.isBefore(oldestKept)) {
          tally.skipped++;
        } else {
          bulk.add(action(e, day), HotDocument.of(e, now, mapper));
        }
        if (bulk.full()) {
          send(bulk, tally);
          remember(segment, tally.lines);
          bulk = new Bulk();
        }
      }
    }
    if (!bulk.isEmpty()) {
      send(bulk, tally);
    }
    return Outcome.INDEXED;
  }

  private void remember(Segment segment, long lines) {
    resumeFile = segment.file();
    resumeLines = lines;
  }

  private ObjectNode action(LogEnvelope e, LocalDate day) {
    ObjectNode action = mapper.createObjectNode();
    ObjectNode meta = action.putObject("index");
    meta.put("_index", indices.index(e.tenantId(), day));
    meta.put("_id", e.eventId());
    String routing = indices.routing(e.tenantId());
    if (routing != null) {
      meta.put("routing", routing);
    }
    return action;
  }

  /** One {@code _bulk} body: action and document lines, kept per item for retries. */
  private final class Bulk {
    final List<byte[]> items = new ArrayList<>();
    long bytes;

    void add(ObjectNode action, ObjectNode doc) throws IOException {
      ByteArrayOutputStream item = new ByteArrayOutputStream(512);
      mapper.writeValue(item, action);
      item.write('\n');
      mapper.writeValue(item, doc);
      item.write('\n');
      byte[] b = item.toByteArray();
      items.add(b);
      bytes += b.length;
    }

    boolean full() {
      return items.size() >= props.bulkMaxDocs() || bytes >= props.bulkMaxBytes().toBytes();
    }

    boolean isEmpty() {
      return items.isEmpty();
    }

    byte[] body(List<byte[]> subset) {
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(bytes, 1 << 30));
      for (byte[] item : subset) {
        out.writeBytes(item);
      }
      return out.toByteArray();
    }
  }

  /**
   * Sends the bulk; 429 / 5xx items are retried up to {@link #ITEM_ATTEMPTS} times, other item
   * errors are counted as rejected. Throws if the request fails, or items still fail after the
   * retries.
   */
  private void send(Bulk bulk, Tally tally) throws IOException, InterruptedException {
    List<byte[]> items = bulk.items;
    for (int attempt = 1; ; attempt++) {
      long start = System.nanoTime();
      Response response;
      try {
        response =
            client.send("POST", "/_bulk?" + FILTER, bulk.body(items), SearchEngineClient.NDJSON);
      } catch (IOException e) {
        metrics.hotBulk(false, System.nanoTime() - start);
        throw e;
      }
      metrics.hotBulk(response.ok(), System.nanoTime() - start);
      tally.bulks++;
      if (!response.ok()) {
        throw new IOException(
            "_bulk answered " + response.status() + ": " + response.excerpt(ERROR_CHARS));
      }
      JsonNode body = mapper.readTree(response.body());
      if (!body.path("errors").asBoolean(false)) {
        tally.indexed += items.size();
        return;
      }
      JsonNode results = body.path("items");
      if (results.size() != items.size()) {
        throw new IOException(
            "_bulk answered " + results.size() + " results for " + items.size() + " items");
      }
      List<byte[]> retry = new ArrayList<>();
      for (int i = 0; i < items.size(); i++) {
        JsonNode result = results.get(i).elements().next();
        int status = result.path("status").asInt(500);
        if (status >= 200 && status < 300) {
          tally.indexed++;
        } else if (status == 429 || status >= 500) {
          retry.add(items.get(i));
        } else {
          tally.rejected++;
          if (tally.firstRejection == null) {
            tally.firstRejection =
                result.path("error").path("type").asText()
                    + ": "
                    + truncate(result.path("error").path("reason").asText());
          }
        }
      }
      if (retry.isEmpty()) {
        return;
      }
      if (attempt >= ITEM_ATTEMPTS) {
        throw new IOException(retry.size() + " items still failing after " + attempt + " attempts");
      }
      tally.retries += retry.size();
      Thread.sleep(itemRetryBase.toMillis() << (attempt - 1));
      items = retry;
    }
  }

  private static String truncate(String text) {
    return text.length() > ERROR_CHARS ? text.substring(0, ERROR_CHARS) + "…" : text;
  }

  private Outcome setAside(Segment segment) {
    try {
      Path kept = wal.quarantine(segment);
      log.error(
          "WAL segment {} is not valid NDJSON; kept as {} and not indexed or archived. Inspect it,"
              + " and rename it back to retry.",
          segment.file(),
          kept);
      return Outcome.UNREADABLE;
    } catch (IOException e) {
      log.error("Could not set aside unreadable segment {}", segment.file(), e);
      return Outcome.FAILED;
    }
  }

  private static BufferedReader open(Segment segment) throws IOException {
    InputStream file = new BufferedInputStream(Files.newInputStream(segment.file()), READ_BUFFER);
    InputStream in = segment.compressed() ? new ZstdInputStream(file) : file;
    return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), READ_BUFFER);
  }

  /** As the archiver's: {@code retry-initial × 2^(n-1)} capped at {@code retry-max}, jittered. */
  Duration backoff(int failures) {
    long initial = props.retryInitial().toMillis();
    long max = props.retryMax().toMillis();
    int doublings = Math.min(Math.max(failures - 1, 0), MAX_BACKOFF_DOUBLINGS);
    long base = initial > (max >> doublings) ? max : Math.min(max, initial << doublings);
    long half = Math.max(base / 2, 1);
    return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
  }
}
