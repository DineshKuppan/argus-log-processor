package dev.dinesh.argus.tail;

import java.io.IOException;

/** Where a tail connection's SSE text goes: the HTTP response, or a test's buffer. */
public interface TailWriter {

  /** Writes and flushes {@code frames}; may block while the client is slow to read. */
  void write(String frames) throws IOException;

  /** Ends the response; idempotent. */
  void close();
}
