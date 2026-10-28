package dev.dinesh.argus.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Where archived segments go: a bucket or a directory. Keys are {@code /}-separated relative paths
 * ({@link ObjectKeys#requireValid}). A {@code put} to an existing key replaces the object, which is
 * what makes re-uploading a segment idempotent.
 */
public interface ObjectStore {

  /** The media type stores that keep one set it on archived objects. */
  String PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet";

  /** Where the store points, for logs (e.g. {@code s3://bucket}); never contains credentials. */
  String describe();

  /** Uploads {@code file} under {@code key}, replacing any object there; durable on return. */
  void put(String key, Path file) throws IOException;

  /** Keys that start with {@code prefix}, in no particular order. */
  List<String> list(String prefix) throws IOException;

  /** Deletes {@code key}; deleting a missing key is not an error. */
  void delete(String key) throws IOException;
}
