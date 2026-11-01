package dev.dinesh.argus.ingest;

import java.util.UUID;

/**
 * Ids of file and export jobs. With {@code argus.instance.id} set they start with it ({@code
 * argus-1:…}): a job runs where it was accepted, and the id says where ({@code P6_PLAN.md} D12).
 */
public final class JobIds {

  private JobIds() {}

  public static String next(String instance) {
    String id = UUID.randomUUID().toString();
    return instance == null ? id : instance + ":" + id;
  }

  /**
   * Why {@code jobId} isn't known here: null if it would be ours, else a message naming the
   * instance that started it.
   */
  public static String elsewhere(String jobId, String instance) {
    int colon = jobId.indexOf(':');
    String owner = colon < 0 ? null : jobId.substring(0, colon);
    if (owner == null || owner.equals(instance)) {
      return null;
    }
    return "Job " + jobId + " was started on instance " + owner + "; ask that instance";
  }
}
