package dev.dinesh.argus.checkpoint;

import dev.dinesh.argus.source.SourceOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a run starts reading, and why.
 *
 * @param offset first byte to read
 * @param reason why this offset was chosen; surfaced in job status and logs
 */
public record ResumePoint(long offset, Reason reason) {

  public enum Reason {
    FROM_BEGINNING_REQUESTED,
    NO_CHECKPOINT,
    CHECKPOINT,
    FILE_REPLACED,
    FILE_TRUNCATED
  }

  /**
   * Resumes from the checkpoint unless the file is no longer the one that was checkpointed: a
   * different file key means it was replaced (e.g. rotated), a smaller size means it was truncated.
   */
  public static ResumePoint resolve(
      Optional<SourceOffset> checkpoint, String fileKey, long fileSize, boolean fromBeginning) {
    if (fromBeginning) {
      return new ResumePoint(0, Reason.FROM_BEGINNING_REQUESTED);
    }
    if (checkpoint.isEmpty()) {
      return new ResumePoint(0, Reason.NO_CHECKPOINT);
    }
    SourceOffset cp = checkpoint.get();
    if (cp.fileKey() != null && fileKey != null && !Objects.equals(cp.fileKey(), fileKey)) {
      return new ResumePoint(0, Reason.FILE_REPLACED);
    }
    if (fileSize < cp.byteOffset()) {
      return new ResumePoint(0, Reason.FILE_TRUNCATED);
    }
    return new ResumePoint(cp.byteOffset(), Reason.CHECKPOINT);
  }
}
