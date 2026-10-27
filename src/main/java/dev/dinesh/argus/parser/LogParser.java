package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;

/**
 * Parses one log line of a single format. Implementations are stateless and thread-safe: one
 * instance is shared by every ingest job.
 */
public interface LogParser {

  /** Format name used in API requests, job status and {@code source.format} of each record. */
  String name();

  /**
   * Cheap probe that may return false positives but never false negatives: when it returns false,
   * {@link #parse} would return null. Lets the parser chain skip formats without running a full
   * parse.
   */
  boolean canParse(String line);

  /** Returns the parsed record, or null if the line is not in this format. Never throws. */
  LogRecord parse(String line);
}
