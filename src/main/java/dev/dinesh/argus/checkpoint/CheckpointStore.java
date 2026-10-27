package dev.dinesh.argus.checkpoint;

import dev.dinesh.argus.source.SourceOffset;
import java.io.IOException;
import java.util.Optional;

/**
 * Durable store of the last committed offset per source. Sources are per tenant: two tenants may
 * use the same source id without sharing offsets (guardrail 4).
 */
public interface CheckpointStore {

  Optional<SourceOffset> load(String tenantId, String sourceId) throws IOException;

  /** Persists {@code offset} atomically: after a crash, either the old or the new value is read. */
  void save(String tenantId, SourceOffset offset) throws IOException;
}
