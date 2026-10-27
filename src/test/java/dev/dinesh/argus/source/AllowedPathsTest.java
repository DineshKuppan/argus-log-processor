package dev.dinesh.argus.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dinesh.argus.source.AllowedPaths.OutsideAllowedRootsException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowedPathsTest {

  @TempDir Path root;
  @TempDir Path outside;

  private AllowedPaths paths() {
    return new AllowedPaths(List.of(root));
  }

  @Test
  void acceptsRegularFilesUnderARoot() throws IOException {
    Path file = Files.writeString(root.resolve("app.log"), "x\n");

    assertEquals(file.toRealPath(), paths().requireFile(file.toString()));
  }

  @Test
  void refusesPathsOutsideEveryRootBeforeCheckingExistence() throws IOException {
    Path file = Files.writeString(outside.resolve("secret.log"), "x\n");

    assertThrows(OutsideAllowedRootsException.class, () -> paths().requireFile(file.toString()));
    // Same answer whether or not the outside file exists: no existence oracle.
    assertThrows(
        OutsideAllowedRootsException.class,
        () -> paths().requireFile(outside.resolve("missing.log").toString()));
    assertThrows(
        OutsideAllowedRootsException.class,
        () ->
            paths()
                .requireFile(
                    root.resolve("../" + outside.getFileName() + "/secret.log").toString()));
  }

  @Test
  void refusesSymlinksThatPointOutside() throws IOException {
    Path target = Files.writeString(outside.resolve("secret.log"), "x\n");
    Path link = Files.createSymbolicLink(root.resolve("link.log"), target);

    assertThrows(OutsideAllowedRootsException.class, () -> paths().requireFile(link.toString()));
  }

  @Test
  void missingFilesInsideAreNotFoundAndDirectoriesAreRejected() throws IOException {
    Files.createDirectory(root.resolve("sub"));

    assertThrows(
        NoSuchFileException.class,
        () -> paths().requireFile(root.resolve("missing.log").toString()));
    assertThrows(
        IllegalArgumentException.class, () -> paths().requireFile(root.resolve("sub").toString()));
    assertThrows(IllegalArgumentException.class, () -> paths().requireFile(" "));
  }
}
