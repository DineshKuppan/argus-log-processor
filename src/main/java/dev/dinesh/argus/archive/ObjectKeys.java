package dev.dinesh.argus.archive;

/** Rules for {@link ObjectStore} keys, shared so every store accepts and rejects the same keys. */
public final class ObjectKeys {

  private ObjectKeys() {}

  /**
   * A key is relative and has no empty, {@code .} or {@code ..} segments: on a filesystem store any
   * of those could point outside the root, and S3 would store them as confusing literal names.
   */
  public static String requireValid(String key) {
    if (key == null || key.isEmpty() || key.startsWith("/") || key.endsWith("/")) {
      throw new IllegalArgumentException("Invalid object key: " + key);
    }
    for (String segment : key.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("Invalid object key: " + key);
      }
    }
    return key;
  }
}
