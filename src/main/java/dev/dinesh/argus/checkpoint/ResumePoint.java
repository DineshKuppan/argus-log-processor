package dev.dinesh.argus.checkpoint;

import dev.dinesh.argus.source.FileSource;
import dev.dinesh.argus.source.SourceOffset;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a run starts reading, and why.
 *
 * @param offset first byte to read
 * @param generation output generation for this run; bumped whenever the path holds a different file
 *     than the one checkpointed, so its output never replaces the earlier file's output
 * @param reason why this offset was chosen; surfaced in job status and logs
 */
public record ResumePoint(long offset, int generation, Reason reason) {

  public enum Reason {
    FROM_BEGINNING_REQUESTED,
    NO_CHECKPOINT,
    CHECKPOINT,
    /** Different file key (inode): the file was rotated or recreated. */
    FILE_REPLACED,
    /** Smaller than the checkpointed offset: truncated in place. */
    FILE_TRUNCATED,
    /**
     * Same inode and large enough, but its first bytes changed: truncated in place, then regrown.
     */
    FILE_REWRITTEN
  }

  /**
   * Resumes from the checkpoint unless {@code file} is no longer the file that was checkpointed, in
   * which case it restarts from 0 in a new generation.
   */
  public static ResumePoint resolve(
      Optional<SourceOffset> checkpoint, FileSource file, boolean fromBeginning)
      throws IOException {
    if (checkpoint.isEmpty()) {
      return new ResumePoint(
          0, 0, fromBeginning ? Reason.FROM_BEGINNING_REQUESTED : Reason.NO_CHECKPOINT);
    }
    SourceOffset cp = checkpoint.get();
    Reason change = changeSince(cp, file);
    if (change != null) {
      return new ResumePoint(0, cp.generation() + 1, change);
    }
    if (fromBeginning) {
      // Same file, so the replay replaces this generation's output instead of adding to it.
      return new ResumePoint(0, cp.generation(), Reason.FROM_BEGINNING_REQUESTED);
    }
    return new ResumePoint(cp.byteOffset(), cp.generation(), Reason.CHECKPOINT);
  }

  /** Returns how the file differs from the checkpointed one, or null if it is the same file. */
  private static Reason changeSince(SourceOffset cp, FileSource file) throws IOException {
    if (cp.fileKey() != null && file.fileKey() != null && !cp.fileKey().equals(file.fileKey())) {
      return Reason.FILE_REPLACED;
    }
    if (file.size() < Math.max(cp.byteOffset(), cp.headBytes())) {
      return Reason.FILE_TRUNCATED;
    }
    if (cp.headBytes() > 0
        && !Objects.equals(cp.headSha256(), file.sha256OfFirst(cp.headBytes()))) {
      return Reason.FILE_REWRITTEN;
    }
    return null;
  }
}
