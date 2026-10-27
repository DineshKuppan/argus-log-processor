package dev.dinesh.argus.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dinesh.argus.checkpoint.ResumePoint.Reason;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.source.FileSource;
import dev.dinesh.argus.source.SourceOffset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResumePointTest {

  private static final IngestProperties PROPS =
      new IngestProperties(0, null, null, 0, 0, null, 0, List.of(), null, null);

  @TempDir Path dir;

  private Path file;

  private Path write(String content) throws IOException {
    file = dir.resolve("app.log");
    // Overwrites in place, keeping the inode, like logrotate's copytruncate.
    Files.writeString(file, content);
    return file;
  }

  private ResumePoint resolve(Optional<SourceOffset> cp, boolean fromBeginning) throws IOException {
    try (FileSource source = FileSource.open(file, "app", PROPS)) {
      return ResumePoint.resolve(cp, source, fromBeginning);
    }
  }

  /** The checkpoint the pipeline would have saved after committing {@code offset} bytes. */
  private Optional<SourceOffset> checkpointAt(long offset, int generation) throws IOException {
    try (FileSource source = FileSource.open(file, "app", PROPS)) {
      int headBytes = (int) Math.min(FileSource.FINGERPRINT_BYTES, offset);
      return Optional.of(
          new SourceOffset(
              "app",
              file.toString(),
              source.fileKey(),
              generation,
              offset,
              headBytes,
              source.sha256OfFirst(headBytes)));
    }
  }

  @Test
  void resumesFromCheckpointWhenTheFileOnlyGrew() throws IOException {
    write("first line\n");
    Optional<SourceOffset> cp = checkpointAt(11, 3);
    Files.writeString(file, "first line\nsecond line\n");

    assertEquals(new ResumePoint(11, 3, Reason.CHECKPOINT), resolve(cp, false));
  }

  @Test
  void replacedFileStartsANewGeneration() throws IOException {
    write("old file\n");
    Optional<SourceOffset> cp = checkpointAt(9, 0);
    Path replacement = Files.writeString(dir.resolve("new.log"), "old file\nnew file\n");
    Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);

    assertEquals(new ResumePoint(0, 1, Reason.FILE_REPLACED), resolve(cp, false));
  }

  @Test
  void fileSmallerThanTheCheckpointWasTruncated() throws IOException {
    write("0123456789\n0123456789\n");
    Optional<SourceOffset> cp = checkpointAt(22, 0);
    write("short\n");

    assertEquals(new ResumePoint(0, 1, Reason.FILE_TRUNCATED), resolve(cp, false));
  }

  @Test
  void truncatedInPlaceThenRegrownPastTheCheckpointIsDetectedByContent() throws IOException {
    write("[01/Oct/2025:00:00:00] old\n");
    Optional<SourceOffset> cp = checkpointAt(27, 4);
    write("[02/Oct/2025:00:00:00] new content that is now longer than before\n");

    assertEquals(new ResumePoint(0, 5, Reason.FILE_REWRITTEN), resolve(cp, false));
  }

  @Test
  void checkpointWithoutAFingerprintStillResumes() throws IOException {
    write("line\nline\n");
    SourceOffset old = checkpointAt(5, 0).orElseThrow();
    Optional<SourceOffset> cp =
        Optional.of(
            new SourceOffset(
                old.sourceId(), old.path(), old.fileKey(), 0, old.byteOffset(), 0, null));

    assertEquals(new ResumePoint(5, 0, Reason.CHECKPOINT), resolve(cp, false));
  }

  @Test
  void fromBeginningKeepsTheGenerationOfTheSameFile() throws IOException {
    write("line\n");
    Optional<SourceOffset> cp = checkpointAt(5, 2);

    assertEquals(new ResumePoint(0, 2, Reason.FROM_BEGINNING_REQUESTED), resolve(cp, true));
    assertEquals(
        new ResumePoint(0, 0, Reason.FROM_BEGINNING_REQUESTED), resolve(Optional.empty(), true));
    assertEquals(new ResumePoint(0, 0, Reason.NO_CHECKPOINT), resolve(Optional.empty(), false));
  }
}
