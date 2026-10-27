package dev.dinesh.argus.utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * File operations that survive power loss. A rename or create only changes the parent directory,
 * and that change is not durable until the directory itself is fsynced, so fsyncing the file alone
 * is not enough.
 */
public final class DurableFiles {

  // Windows cannot open a directory as a channel; NTFS journals metadata changes instead.
  private static final boolean CAN_FSYNC_DIRECTORIES =
      !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

  private DurableFiles() {}

  /** Like {@link Files#createDirectories}, but also makes each newly created entry durable. */
  public static Path createDirectories(Path dir) throws IOException {
    List<Path> missing = new ArrayList<>();
    for (Path p = dir.toAbsolutePath(); p != null && !Files.isDirectory(p); p = p.getParent()) {
      missing.add(p);
    }
    Files.createDirectories(dir);
    for (Path created : missing) {
      fsyncDirectory(created.getParent());
    }
    return dir;
  }

  /**
   * Atomically renames {@code source} over {@code target} and fsyncs the target directory, so the
   * new name (and any deletes made earlier in that directory) is durable when this returns. The
   * caller must already have fsynced {@code source}'s contents.
   */
  public static void moveAtomically(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    fsyncDirectory(target.toAbsolutePath().getParent());
  }

  public static void fsyncDirectory(Path dir) throws IOException {
    if (!CAN_FSYNC_DIRECTORIES) {
      return;
    }
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }
}
