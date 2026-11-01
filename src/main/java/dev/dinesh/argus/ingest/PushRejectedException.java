package dev.dinesh.argus.ingest;

/**
 * A push request refused at one of the push path's bounds ({@code P3_PLAN.md} D4). Nothing of the
 * request was spooled.
 */
public class PushRejectedException extends RuntimeException {

  public enum Reason {
    /** Every push request slot is busy (HTTP 429). */
    BUSY,
    /** A request with the same {@code Idempotency-Key} is still in progress (HTTP 429). */
    KEY_IN_FLIGHT,
    /** The tenant is over its ingest quota (HTTP 429). */
    QUOTA,
    /** The WAL is above its high watermark (HTTP 429). */
    WAL_FULL,
    /** The spools hold {@code max-spool-bytes} not yet ingested (HTTP 429). */
    SPOOL_FULL,
    /** The body or its line count is over the limit (HTTP 413). */
    TOO_LARGE,
    /** The tenant already has {@code max-streams-per-tenant} streams (HTTP 409). */
    TOO_MANY_STREAMS,
    /** The stream id is a file ingest source's id (HTTP 409). */
    SOURCE_IN_USE,
    /** A {@code Content-Encoding} other than gzip (HTTP 415). */
    UNSUPPORTED_ENCODING,
    /** The instance is shutting down; the retry goes to another one (HTTP 503, P6 D12). */
    DRAINING
  }

  private final Reason reason;
  private final long retryAfterSeconds;

  PushRejectedException(Reason reason, String message) {
    this(reason, 0, message);
  }

  PushRejectedException(Reason reason, long retryAfterSeconds, String message) {
    super(message);
    this.reason = reason;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public Reason reason() {
    return reason;
  }

  /** When to retry, in seconds, or 0 when retrying the same request can't succeed. */
  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
