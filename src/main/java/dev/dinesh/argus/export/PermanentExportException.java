package dev.dinesh.argus.export;

import java.io.IOException;

/** An export source refused the request in a way retrying can't fix (a bad query, a bad key). */
public class PermanentExportException extends IOException {

  public PermanentExportException(String message) {
    super(message);
  }
}
