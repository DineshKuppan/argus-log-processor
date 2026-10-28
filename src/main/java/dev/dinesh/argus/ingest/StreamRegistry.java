package dev.dinesh.argus.ingest;

import dev.dinesh.argus.checkpoint.CheckpointStore;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.ingest.PushRejectedException.Reason;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.source.StreamSpool;
import dev.dinesh.argus.source.StreamSpool.ReadPosition;
import dev.dinesh.argus.tenant.TenantIds;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every push stream and its open spool, by tenant. Streams found under {@code spool-dir} are opened
 * at startup, so whatever was acknowledged before a restart is still ingested.
 */
@Slf4j
@Component
public class StreamRegistry {

  private static final String STREAM_PREFIX = "stream=";
  private static final String TENANT_PREFIX = "tenant=";

  private final PushProperties props;
  private final CheckpointStore checkpoints;
  private final LongSupplier nanoTime;
  private final Map<String, PushStream> streams = new ConcurrentHashMap<>();
  private final AtomicLong pendingBytes = new AtomicLong();

  @Autowired
  public StreamRegistry(PushProperties props, CheckpointStore checkpoints) throws IOException {
    this(props, checkpoints, System::nanoTime);
  }

  StreamRegistry(PushProperties props, CheckpointStore checkpoints, LongSupplier nanoTime)
      throws IOException {
    this.props = props;
    this.checkpoints = checkpoints;
    this.nanoTime = nanoTime;
    recover();
  }

  /** The tenant's stream, opening (and creating) its spool if needed. */
  public PushStream getOrCreate(String tenantId, String streamId) throws IOException {
    PushStream stream = streams.get(key(tenantId, streamId));
    return stream != null ? stream : create(tenantId, streamId);
  }

  public Optional<PushStream> find(String tenantId, String streamId) {
    return Optional.ofNullable(streams.get(key(tenantId, streamId)));
  }

  public List<PushStream> forTenant(String tenantId) {
    return streams.values().stream()
        .filter(s -> s.tenantId().equals(tenantId))
        .sorted(Comparator.comparing(PushStream::streamId))
        .toList();
  }

  public Collection<PushStream> all() {
    return streams.values();
  }

  /** Acknowledged bytes not yet ingested, across every stream. */
  public long pendingBytes() {
    return pendingBytes.get();
  }

  @PreDestroy
  public synchronized void close() {
    for (PushStream stream : streams.values()) {
      try {
        stream.spool().close();
      } catch (IOException e) {
        log.warn("Closing spool of stream {} failed: {}", stream.streamId(), e.toString());
      }
    }
    streams.clear();
  }

  private synchronized PushStream create(String tenantId, String streamId) throws IOException {
    PushStream existing = streams.get(key(tenantId, streamId));
    if (existing != null) {
      return existing;
    }
    long count = streams.values().stream().filter(s -> s.tenantId().equals(tenantId)).count();
    if (count >= props.maxStreamsPerTenant()) {
      throw new PushRejectedException(
          Reason.TOO_MANY_STREAMS,
          "Tenant already has " + count + " streams (argus.push.max-streams-per-tenant)");
    }
    return open(tenantId, streamId);
  }

  private PushStream open(String tenantId, String streamId) throws IOException {
    Path dir =
        props
            .spoolDir()
            .resolve(TenantIds.pathSegment(tenantId))
            .resolve(STREAM_PREFIX + SourceOffset.requireValidSourceId(streamId));
    Optional<ReadPosition> read = checkpoints.load(tenantId, streamId).flatMap(ReadPosition::of);
    StreamSpool spool =
        StreamSpool.open(
            dir,
            new StreamSpool.Settings(props.idempotencyWindow(), props.spoolRotateBytes().toBytes()),
            read,
            pendingBytes,
            nanoTime);
    PushStream stream = new PushStream(tenantId, streamId, spool);
    streams.put(key(tenantId, streamId), stream);
    return stream;
  }

  private void recover() throws IOException {
    if (!Files.isDirectory(props.spoolDir())) {
      return;
    }
    try (Stream<Path> tenants = Files.list(props.spoolDir())) {
      for (Path tenantDir : tenants.toList()) {
        String tenantId = idAfter(tenantDir, TENANT_PREFIX);
        if (tenantId == null || !Files.isDirectory(tenantDir)) {
          continue;
        }
        try (Stream<Path> dirs = Files.list(tenantDir)) {
          for (Path streamDir : dirs.toList()) {
            String streamId = idAfter(streamDir, STREAM_PREFIX);
            if (streamId != null && Files.isDirectory(streamDir)) {
              PushStream stream = open(tenantId, streamId);
              log.info(
                  "Push stream recovered tenant={} stream={} pendingBytes={}",
                  tenantId,
                  streamId,
                  stream.spool().pendingBytes());
            }
          }
        }
      }
    }
  }

  private static String idAfter(Path dir, String prefix) {
    String name = dir.getFileName().toString();
    if (!name.startsWith(prefix)) {
      return null;
    }
    String id = name.substring(prefix.length());
    try {
      return prefix.equals(TENANT_PREFIX)
          ? TenantIds.requireValid(id)
          : SourceOffset.requireValidSourceId(id);
    } catch (IllegalArgumentException e) {
      log.warn("Ignoring spool directory with an invalid id: {}", dir);
      return null;
    }
  }

  private static String key(String tenantId, String streamId) {
    return tenantId + "/" + streamId;
  }
}
