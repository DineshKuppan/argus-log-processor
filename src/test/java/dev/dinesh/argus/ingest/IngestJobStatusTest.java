package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dinesh.argus.ingest.IngestJob.State;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IngestJobStatusTest {

  private final IngestJob job =
      new IngestJob("job", "app", Path.of("/data/in/app.log"), false, null);

  @Test
  void percentIsZeroBeforeTheRunStarts() {
    assertEquals(0.0, IngestJobStatus.of(job).percent());
  }

  @Test
  void percentTracksBytesReadSinceTheStartOffset() {
    job.running();
    job.progress().started(100, 300, "CHECKPOINT");
    job.progress().read(150, 0);

    assertEquals(25.0, IngestJobStatus.of(job).percent());
  }

  @Test
  void completedJobIsAlwaysHundredPercent() {
    job.progress().started(300, 300, "CHECKPOINT");
    job.finished(State.COMPLETED, null);

    assertEquals(100.0, IngestJobStatus.of(job).percent());
  }
}
