package dev.dinesh.argus.wal;

/**
 * A consumer of the WAL ({@code P5_PLAN.md} D1, ADR-7). Each acknowledges a segment with an empty
 * marker file next to it; once every routed sink has, the segment may be deleted.
 */
public enum WalSink {
  /** The Parquet archive (P2); its marker predates P5, so upgrades keep their state. */
  ARCHIVE(".archived"),
  /** The hot search tier (P5). */
  HOT(".indexed");

  private final String markerSuffix;

  WalSink(String markerSuffix) {
    this.markerSuffix = markerSuffix;
  }

  public String markerSuffix() {
    return markerSuffix;
  }
}
