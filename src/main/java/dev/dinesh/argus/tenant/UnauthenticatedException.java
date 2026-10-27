package dev.dinesh.argus.tenant;

/** The request has no valid credential; the API answers 401. */
public class UnauthenticatedException extends RuntimeException {

  public UnauthenticatedException(String message) {
    super(message);
  }
}
