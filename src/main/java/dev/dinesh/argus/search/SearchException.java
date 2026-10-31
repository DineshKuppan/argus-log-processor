package dev.dinesh.argus.search;

/** A search that can't be answered now; {@code status} and {@code retryAfterSeconds} say how. */
public class SearchException extends RuntimeException {

  private final int status;
  private final int retryAfterSeconds;

  public SearchException(int status, int retryAfterSeconds, String message) {
    super(message);
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int status() {
    return status;
  }

  /** 0 for none. */
  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
