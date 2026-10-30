package dev.dinesh.argus.tail;

/** A tail connection limit is reached; maps to HTTP 429 + {@code Retry-After}. */
public class TailLimitException extends RuntimeException {

  /** About how long until a connection closes or a client gives up. */
  public static final int RETRY_AFTER_SECONDS = 30;

  public TailLimitException(String message) {
    super(message);
  }

  public int retryAfterSeconds() {
    return RETRY_AFTER_SECONDS;
  }
}
