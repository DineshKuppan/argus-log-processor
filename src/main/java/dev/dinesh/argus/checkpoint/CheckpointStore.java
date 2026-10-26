package dev.dinesh.argus.checkpoint;

import dev.dinesh.argus.source.SourceOffset;
import java.io.IOException;
import java.util.Optional;

/** Durable store of the last committed offset per source. */
public interface CheckpointStore {

  Optional<SourceOffset> load(String sourceId) throws IOException;

  /** Persists {@code offset} atomically: after a crash, either the old or the new value is read. */
  void save(SourceOffset offset) throws IOException;
}
