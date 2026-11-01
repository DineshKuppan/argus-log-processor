package dev.dinesh.argus.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JobIdsTest {

  @Test
  void anInstancesJobIdsNameIt() {
    String id = JobIds.next("argus-1");
    assertTrue(id.startsWith("argus-1:"), id);
    assertFalse(JobIds.next(null).contains(":"), "single instance: a plain UUID as before");
  }

  @Test
  void anotherInstancesJobIsNamedInThe404() {
    String id = JobIds.next("argus-1");
    assertNull(JobIds.elsewhere(id, "argus-1"));
    assertNull(JobIds.elsewhere(JobIds.next(null), "argus-0"), "an unknown plain id: plain 404");
    String message = JobIds.elsewhere(id, "argus-0");
    assertTrue(message.contains("instance argus-1"), message);
  }
}
