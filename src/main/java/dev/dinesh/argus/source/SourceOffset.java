package dev.dinesh.argus.source;

/**
 * Resumable position in a file source.
 *
 * @param sourceId stable id of the source (same file, same id)
 * @param path absolute path of the file when the offset was recorded
 * @param fileKey filesystem identity (inode on Linux/macOS) used to detect a replaced file; may be
 *     null where the filesystem does not expose one
 * @param byteOffset first byte that has not yet been committed
 */
public record SourceOffset(String sourceId, String path, String fileKey, long byteOffset) {}
