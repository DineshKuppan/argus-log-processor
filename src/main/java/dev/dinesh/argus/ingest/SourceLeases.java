package dev.dinesh.argus.ingest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Which run owns a tenant's source right now, and which sources are push streams. File jobs and
 * stream runs share each source's checkpoint and output, so at most one of them may run per source
 * at a time, and a stream's id is never a file job's source id: the file's checkpoint would replace
 * the stream's, and the stream would re-read its spool or delete files not yet read.
 */
@Component
public class SourceLeases {

  private final Map<String, String> owners = new HashMap<>();
  private final Set<String> streams = new HashSet<>();

  /** Takes the source for {@code owner}, or returns its current owner if it is taken. */
  public synchronized Optional<String> tryAcquire(String tenantId, String sourceId, String owner) {
    return Optional.ofNullable(owners.putIfAbsent(key(tenantId, sourceId), owner));
  }

  /**
   * Takes the source for a file job: like {@link #tryAcquire}, but a push stream's source is always
   * taken.
   */
  public synchronized Optional<String> tryAcquireForFile(
      String tenantId, String sourceId, String owner) {
    if (streams.contains(key(tenantId, sourceId))) {
      return Optional.of("push stream " + sourceId);
    }
    return tryAcquire(tenantId, sourceId, owner);
  }

  /**
   * Marks the source as a push stream's, for good.
   *
   * @return the file job holding it now (the stream must not be created), or empty
   */
  public synchronized Optional<String> registerStream(String tenantId, String sourceId) {
    String key = key(tenantId, sourceId);
    if (!streams.contains(key) && owners.containsKey(key)) {
      return Optional.of(owners.get(key));
    }
    streams.add(key);
    return Optional.empty();
  }

  /** Releases the source if {@code owner} still holds it. */
  public synchronized void release(String tenantId, String sourceId, String owner) {
    owners.remove(key(tenantId, sourceId), owner);
  }

  private static String key(String tenantId, String sourceId) {
    return tenantId + "/" + sourceId;
  }
}
