package dev.dinesh.argus.hot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dinesh.argus.config.HotProperties;
import dev.dinesh.argus.engine.SearchEngineClient;
import dev.dinesh.argus.engine.SearchEngineClient.Response;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes envelopes into the hot tier with {@code _bulk} ({@code P5_PLAN.md} D2), for both the WAL
 * indexer and the Kafka hot consumer ({@code P6_PLAN.md} D4): {@code index} actions with {@code _id
 * = eventId} and routing, 429 / 5xx items retried, other item errors counted as rejected. Also the
 * cluster upkeep both need: the engine check and template on first use, retention hourly. Used by
 * one thread.
 */
@Slf4j
public final class HotBulkWriter {

  /** Item retries for 429 / 5xx items, waiting 1s, 2s, 4s. */
  static final int ITEM_ATTEMPTS = 4;

  private static final Duration RETENTION_EVERY = Duration.ofHours(1);
  private static final int ERROR_CHARS = 300;
  private static final String FILTER =
      "filter_path=errors,items.*.status,items.*.error.type,items.*.error.reason";

  private final SearchEngineClient client;
  private final HotIndices indices;
  private final HotTemplates templates;
  private final HotRetention retention;
  private final HotProperties props;
  private final ObjectMapper mapper;
  private final ArgusMetrics metrics;
  private final Clock clock;
  private final Duration itemRetryBase;

  private boolean prepared;
  private Instant lastRetention = Instant.MIN;

  public HotBulkWriter(
      SearchEngineClient client,
      HotIndices indices,
      HotProperties props,
      ArgusMetrics metrics,
      Clock clock,
      Duration itemRetryBase) {
    this.client = client;
    this.indices = indices;
    this.templates = new HotTemplates(client, indices, props);
    this.retention = new HotRetention(client, indices);
    this.props = props;
    this.mapper = client.mapper();
    this.metrics = metrics;
    this.clock = clock;
    this.itemRetryBase = itemRetryBase;
  }

  public SearchEngineClient client() {
    return client;
  }

  public HotIndices indices() {
    return indices;
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  public Clock clock() {
    return clock;
  }

  /**
   * The engine check and template install, until they succeed once; then retention, hourly (a
   * failed retention only warns).
   */
  public void maintain() throws IOException, InterruptedException {
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
  }

  /** Counts for one unit of work (a segment, a bulk), for its canonical line and meters. */
  public static final class Tally {
    public long lines;
    public long indexed;
    public long rejected;
    public long skipped;
    public int bulks;
    public int retries;
    public String firstRejection;

    /** {@code [indexed, rejected, skipped]} per tenant. */
    final Map<String, long[]> byTenant = new LinkedHashMap<>();

    private void count(String tenant, int outcome) {
      byTenant.computeIfAbsent(tenant, t -> new long[3])[outcome]++;
    }

    /** Records the documents per tenant, as {@code argus.hot.documents}. */
    public void record(ArgusMetrics metrics, String outcome) {
      byTenant.forEach((tenant, n) -> metrics.hotSegment(tenant, outcome, n[0], n[1], n[2]));
    }
  }

  private static final int INDEXED = 0;
  private static final int REJECTED = 1;
  private static final int SKIPPED = 2;

  public Bulk newBulk() {
    return new Bulk();
  }

  /** One {@code _bulk} body: action and document lines, kept per item for retries. */
  public final class Bulk {
    private final List<byte[]> items = new ArrayList<>();
    private final List<String> tenants = new ArrayList<>();
    private long bytes;

    /**
     * Adds the envelope, or counts it as outside the retention (an index retention would delete).
     *
     * @return false if it was skipped
     */
    public boolean add(LogEnvelope e, Instant now, Tally tally) throws IOException {
      tally.lines++;
      LocalDate day = HotIndices.day(e, now);
      if (day.isBefore(indices.oldestKept(now))) {
        tally.skipped++;
        tally.count(e.tenantId(), SKIPPED);
        return false;
      }
      ByteArrayOutputStream item = new ByteArrayOutputStream(512);
      mapper.writeValue(item, action(e, day));
      item.write('\n');
      mapper.writeValue(item, HotDocument.of(e, now, mapper));
      item.write('\n');
      byte[] b = item.toByteArray();
      items.add(b);
      tenants.add(e.tenantId());
      bytes += b.length;
      return true;
    }

    public boolean full() {
      return items.size() >= props.bulkMaxDocs() || bytes >= props.bulkMaxBytes().toBytes();
    }

    public boolean isEmpty() {
      return items.isEmpty();
    }

    public int size() {
      return items.size();
    }

    private byte[] body(List<Integer> subset) {
      ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(bytes, 1 << 30));
      for (int i : subset) {
        out.writeBytes(items.get(i));
      }
      return out.toByteArray();
    }
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

  /**
   * Sends the bulk; 429 / 5xx items are retried up to {@link #ITEM_ATTEMPTS} times, other item
   * errors are counted as rejected. Throws if the request fails (or its answer isn't JSON), or
   * items still fail after the retries; the bulk can then be sent again whole.
   */
  public void send(Bulk bulk, Tally tally) throws IOException, InterruptedException {
    List<Integer> items = new ArrayList<>(bulk.items.size());
    for (int i = 0; i < bulk.items.size(); i++) {
      items.add(i);
    }
    long indexed = 0;
    long rejected = 0;
    Map<String, long[]> counts = new LinkedHashMap<>();
    String firstRejection = null;
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
      JsonNode body;
      try {
        body = mapper.readTree(response.body());
      } catch (JsonProcessingException e) {
        // The engine's (or a proxy's) fault, not the data's: never set the data aside for it.
        throw new IOException(
            "_bulk answered " + response.status() + " that isn't JSON: " + response.excerpt(80), e);
      }
      List<Integer> retry = new ArrayList<>();
      if (!body.path("errors").asBoolean(false)) {
        for (int i : items) {
          indexed++;
          counts.computeIfAbsent(bulk.tenants.get(i), t -> new long[3])[INDEXED]++;
        }
      } else {
        JsonNode results = body.path("items");
        if (results.size() != items.size()) {
          throw new IOException(
              "_bulk answered " + results.size() + " results for " + items.size() + " items");
        }
        for (int n = 0; n < items.size(); n++) {
          int i = items.get(n);
          JsonNode result = results.get(n).elements().next();
          int status = result.path("status").asInt(500);
          if (status >= 200 && status < 300) {
            indexed++;
            counts.computeIfAbsent(bulk.tenants.get(i), t -> new long[3])[INDEXED]++;
          } else if (status == 429 || status >= 500) {
            retry.add(i);
          } else {
            rejected++;
            counts.computeIfAbsent(bulk.tenants.get(i), t -> new long[3])[REJECTED]++;
            if (firstRejection == null) {
              firstRejection =
                  result.path("error").path("type").asText()
                      + ": "
                      + truncate(result.path("error").path("reason").asText());
            }
          }
        }
      }
      if (retry.isEmpty()) {
        // Counted only once the whole bulk is done, so a bulk sent again isn't counted twice.
        tally.indexed += indexed;
        tally.rejected += rejected;
        counts.forEach(
            (tenant, n) -> {
              long[] t = tally.byTenant.computeIfAbsent(tenant, k -> new long[3]);
              t[INDEXED] += n[INDEXED];
              t[REJECTED] += n[REJECTED];
            });
        if (tally.firstRejection == null) {
          tally.firstRejection = firstRejection;
        }
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
}
