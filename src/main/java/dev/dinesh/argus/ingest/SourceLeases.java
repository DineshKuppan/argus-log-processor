package dev.dinesh.argus.ingest;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Which run owns a tenant's source right now. File jobs and stream runs share each source's
 * checkpoint and output, so at most one of them may run per source at a time.
 */
@Component
public class SourceLeases {

  private final Map<String, String> owners = new ConcurrentHashMap<>();

  /** Takes the source for {@code owner}, or returns its current owner if it is taken. */
  public Optional<String> tryAcquire(String tenantId, String sourceId, String owner) {
    return Optional.ofNullable(owners.putIfAbsent(key(tenantId, sourceId), owner));
  }

  /** Releases the source if {@code owner} still holds it. */
  public void release(String tenantId, String sourceId, String owner) {
    owners.remove(key(tenantId, sourceId), owner);
  }

  private static String key(String tenantId, String sourceId) {
    return tenantId + "/" + sourceId;
  }
}
