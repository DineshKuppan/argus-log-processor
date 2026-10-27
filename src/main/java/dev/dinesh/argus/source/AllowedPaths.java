package dev.dinesh.argus.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * The directories file sources may be read from, shared by v1 and v2 so both APIs follow one rule.
 * Each root's real path is resolved once, when it first exists (roots may be created after
 * startup). Thread-safe.
 */
public final class AllowedPaths {

  /** A path outside every allowed root; the APIs answer 400. */
  public static class OutsideAllowedRootsException extends IllegalArgumentException {
    OutsideAllowedRootsException() {
      super("Path is outside the allowed roots");
    }
  }

  private static final class Root {
    final Path asWritten;
    volatile Path real;

    Root(Path root) {
      this.asWritten = root.toAbsolutePath().normalize();
    }

    /** True if {@code path} is under this root, as written or after resolving its symlinks. */
    boolean contains(Path path) {
      if (path.startsWith(asWritten)) {
        return Files.isDirectory(asWritten);
      }
      Path resolved = real;
      if (resolved == null) {
        if (!Files.isDirectory(asWritten)) {
          return false;
        }
        try {
          resolved = real = asWritten.toRealPath();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return path.startsWith(resolved);
    }
  }

  private final List<Root> roots;

  public AllowedPaths(List<Path> roots) {
    this.roots = roots.stream().map(Root::new).toList();
  }

  /**
   * Resolves {@code rawPath} to the real path of a regular file under an allowed root.
   *
   * @throws OutsideAllowedRootsException if the path, or its target after resolving symlinks, is
   *     outside every root; checked before existence, so a 404 never reveals whether a path outside
   *     the roots exists
   * @throws NoSuchFileException if the file does not exist
   * @throws IllegalArgumentException if the path is blank or not a regular file
   */
  public Path requireFile(String rawPath) throws IOException {
    if (rawPath == null || rawPath.isBlank()) {
      throw new IllegalArgumentException("path is required");
    }
    Path path = Path.of(rawPath).toAbsolutePath().normalize();
    requireAllowed(path);
    if (!Files.exists(path)) {
      throw new NoSuchFileException(rawPath);
    }
    // Again after resolving symlinks, which may point outside the roots.
    Path realPath = path.toRealPath();
    requireAllowed(realPath);
    if (!Files.isRegularFile(realPath)) {
      throw new IllegalArgumentException("Not a regular file: " + rawPath);
    }
    return realPath;
  }

  /** {@code path} must be absolute and normalized. */
  private void requireAllowed(Path path) {
    for (Root root : roots) {
      if (root.contains(path)) {
        return;
      }
    }
    throw new OutsideAllowedRootsException();
  }
}
