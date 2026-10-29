package dev.dinesh.argus.tail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.TailProperties;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.tenant.TenantIds;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The live tail ({@code P4_PLAN.md} D1–D4): a ring per tenant that has a tail open, which ingest
 * publishes to without ever waiting, and the connections reading it. While nobody tails a tenant,
 * {@link #isWatched} is false and publishing costs one map lookup.
 */
@Slf4j
@Component
public class TailHub implements SmartLifecycle {

  /** Sender threads spend their time blocked in a ring wait or a socket write: small stacks. */
  private static final long SENDER_STACK_BYTES = 256 * 1024;

  /** One tenant's ring and how many connections read it. Guarded by {@code tenants.compute}. */
  private static final class TenantTail {
    final TailRing ring;
    int connections;
    long idleSinceNanos = System.nanoTime();

    TenantTail(TailRing ring) {
      this.ring = ring;
    }
  }

  private final TailProperties props;
  private final ObjectMapper mapper;
  private final ArgusMetrics metrics;
  private final Map<String, TenantTail> tenants = new ConcurrentHashMap<>();
  private final Set<TailConnection> open = ConcurrentHashMap.newKeySet();
  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicLong connectionIds = new AtomicLong();
  private volatile boolean running;
  private ScheduledExecutorService sweeper;

  @Autowired
  public TailHub(TailProperties props, ObjectMapper mapper, ObjectProvider<ArgusMetrics> metrics) {
    this(props, mapper, metrics.getIfAvailable(ArgusMetrics::noop));
  }

  public TailHub(TailProperties props, ObjectMapper mapper, ArgusMetrics metrics) {
    this.props = props;
    this.mapper = mapper;
    this.metrics = metrics;
  }

  /** True while the tenant has a ring: a tail open, or closed less than {@code ring-idle} ago. */
  public boolean isWatched(String tenantId) {
    return tenants.containsKey(tenantId);
  }

  /** Appends to the tenant's ring if it has one; never waits on a tail client. */
  public void publish(String tenantId, List<TailEvent> events) {
    TenantTail tail = tenants.get(tenantId);
    if (tail != null) {
      tail.ring.append(events);
    }
  }

  /** Open tail connections on this node. */
  public int connections() {
    return connections.get();
  }

  /**
   * Opens a connection and starts its sender thread.
   *
   * @param lastEventId the client's {@code Last-Event-ID} (an event id of a previous connection),
   *     or null to start at the newest line
   * @throws TailLimitException at the node's or the tenant's connection limit
   */
  public TailConnection open(
      String tenantId, TailFilter filter, String lastEventId, TailWriter writer) {
    TenantIds.requireValid(tenantId);
    if (!running) {
      throw new TailLimitException("Live tail is not running");
    }
    if (connections.incrementAndGet() > props.maxConnections()) {
      connections.decrementAndGet();
      throw new TailLimitException(
          "All "
              + props.maxConnections()
              + " tail connections are in use (argus.tail.max-connections)");
    }
    boolean[] tenantFull = new boolean[1];
    TenantTail tail =
        tenants.compute(
            tenantId,
            (id, t) -> {
              TenantTail current =
                  t != null
                      ? t
                      : new TenantTail(
                          new TailRing(props.ringLines(), props.ringBytes().toBytes()));
              if (current.connections >= props.maxConnectionsPerTenant()) {
                tenantFull[0] = true;
              } else {
                current.connections++;
              }
              return current;
            });
    if (tenantFull[0]) {
      connections.decrementAndGet();
      throw new TailLimitException(
          "The tenant has "
              + props.maxConnectionsPerTenant()
              + " tail connections open (argus.tail.max-connections-per-tenant)");
    }
    TailRing ring = tail.ring;
    long newest = ring.next();
    long cursor = newest;
    String greeting = "";
    if (lastEventId != null && !lastEventId.isBlank()) {
      long resumeAt = resumeCursor(ring, lastEventId.strip());
      if (resumeAt < 0) {
        greeting = "event: reset\ndata: {\"reason\":\"unknown Last-Event-ID\"}\n\n";
      } else {
        cursor = Math.min(resumeAt, newest);
      }
    }
    String id = Long.toString(connectionIds.incrementAndGet());
    TailConnection[] self = new TailConnection[1];
    TailConnection connection =
        new TailConnection(
            id,
            tenantId,
            filter,
            ring,
            cursor,
            greeting,
            writer,
            props,
            mapper,
            metrics,
            () -> closed(tenantId, self[0]));
    self[0] = connection;
    open.add(connection);
    Thread sender =
        new Thread(null, connection::run, "tail-" + tenantId + "-" + id, SENDER_STACK_BYTES);
    sender.setDaemon(true);
    connection.start(sender);
    return connection;
  }

  /** The cursor after {@code lastEventId}, or -1 if it belongs to another ring. */
  private static long resumeCursor(TailRing ring, String lastEventId) {
    int dash = lastEventId.lastIndexOf('-');
    if (dash <= 0 || !lastEventId.substring(0, dash).equals(ring.id())) {
      return -1;
    }
    try {
      return Long.parseLong(lastEventId.substring(dash + 1)) + 1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private void closed(String tenantId, TailConnection connection) {
    if (!open.remove(connection)) {
      return;
    }
    connections.decrementAndGet();
    tenants.computeIfPresent(
        tenantId,
        (id, t) -> {
          if (--t.connections == 0) {
            t.idleSinceNanos = System.nanoTime();
          }
          return t;
        });
  }

  /** Releases the rings of tenants nobody has tailed for {@code ring-idle}. */
  void releaseIdleRings() {
    long now = System.nanoTime();
    long idle = props.ringIdle().toNanos();
    for (String tenantId : tenants.keySet()) {
      tenants.computeIfPresent(
          tenantId,
          (id, t) -> {
            if (t.connections == 0 && now - t.idleSinceNanos >= idle) {
              t.ring.close();
              return null;
            }
            return t;
          });
    }
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    sweeper =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread thread = new Thread(r, "tail-sweeper");
              thread.setDaemon(true);
              return thread;
            });
    long periodMillis = Math.max(100, props.ringIdle().toMillis() / 2);
    sweeper.scheduleWithFixedDelay(
        this::releaseIdleRings, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    running = true;
  }

  /** Closes every connection, so a graceful shutdown doesn't wait for tail clients. */
  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    sweeper.shutdownNow();
    open.forEach(c -> c.close("shutdown"));
    tenants.values().forEach(t -> t.ring.close());
    tenants.clear();
    log.info("Live tail stopped");
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
