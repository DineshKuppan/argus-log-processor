package dev.dinesh.argus.archive;

import dev.dinesh.argus.utils.DurableFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * An {@link ObjectStore} on a directory: a Kubernetes PersistentVolume, or a local disk for
 * development. Each object is a file at {@code root/key}, written to a temporary name, fsynced and
 * renamed into place, so readers never see a partial object and a crash leaves at most a temporary
 * file (which {@link #list} ignores).
 */
public final class FileSystemObjectStore implements ObjectStore {

  static final String TEMP_SUFFIX = ".uploading";

  private final Path root;

  public FileSystemObjectStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public String describe() {
    return "file://" + root;
  }

  @Override
  public void put(String key, Path file) throws IOException {
    Path target = resolve(key);
    DurableFiles.createDirectories(target.getParent());
    Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + TEMP_SUFFIX);
    try {
      Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING);
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      DurableFiles.moveAtomically(temp, target);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  @Override
  public List<String> list(String prefix) throws IOException {
    // Walk only the deepest directory the prefix names, as S3 lists only matching keys.
    int slash = prefix.lastIndexOf('/');
    Path dir = slash < 0 ? root : resolve(prefix.substring(0, slash));
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> !p.getFileName().toString().endsWith(TEMP_SUFFIX))
          .map(p -> root.relativize(p).toString().replace(dir.getFileSystem().getSeparator(), "/"))
          .filter(key -> key.startsWith(prefix))
          .toList();
    } catch (NoSuchFileException e) {
      return List.of();
    }
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(resolve(key));
  }

  private Path resolve(String key) {
    return root.resolve(ObjectKeys.requireValid(key));
  }
}
