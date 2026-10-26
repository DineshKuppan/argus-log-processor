package dev.dinesh.argus.source;

import java.util.regex.Pattern;

/**
 * Resumable position in a file source.
 *
 * @param sourceId stable id of the source (same file, same id)
 * @param path absolute path of the file when the offset was recorded
 * @param fileKey filesystem identity (inode on Linux/macOS) used to detect a replaced file; may be
 *     null where the filesystem does not expose one
 * @param generation incremented each time the path holds a different file (rotated, truncated or
 *     rewritten), so output from an earlier file is never mistaken for a replay of the current one
 * @param byteOffset first byte that has not yet been committed
 * @param headBytes how many leading bytes {@code headSha256} covers (0 if none)
 * @param headSha256 hex SHA-256 of the file's first {@code headBytes} bytes; detects a file that
 *     was truncated in place and has since grown past the old offset
 */
public record SourceOffset(
    String sourceId,
    String path,
    String fileKey,
    int generation,
    long byteOffset,
    int headBytes,
    String headSha256) {

  /** Source ids become file and directory names, so restrict them to prevent path traversal. */
  private static final Pattern SOURCE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

  public static String requireValidSourceId(String sourceId) {
    if (sourceId == null
        || !SOURCE_ID.matcher(sourceId).matches()
        || sourceId.chars().allMatch(c -> c == '.')) {
      throw new IllegalArgumentException(
          "Invalid source id (allowed: 1-128 of A-Z a-z 0-9 . _ -): " + sourceId);
    }
    return sourceId;
  }
}
