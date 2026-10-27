package dev.dinesh.argus.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * The directories file sources may be read from ({@code argus.ingest.allowed-roots}), shared by v1
 * and v2 so both APIs follow one rule. Thread-safe.
 */
public final class AllowedPaths {

  /** A path outside every allowed root; the APIs answer 400. */
  public static class OutsideAllowedRootsException extends IllegalArgumentException {
    OutsideAllowedRootsException() {
      super("Path is outside the allowed roots (argus.ingest.allowed-roots)");
    }
  }

  private final List<Path> roots;

  public AllowedPaths(List<Path> roots) {
    this.roots = List.copyOf(roots);
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

  /** {@code path} must be absolute and normalized; compared with each root as written and real. */
  private void requireAllowed(Path path) throws IOException {
    for (Path root : roots) {
      if (Files.isDirectory(root)
          && (path.startsWith(root.toRealPath())
              || path.startsWith(root.toAbsolutePath().normalize()))) {
        return;
      }
    }
    throw new OutsideAllowedRootsException();
  }
}
