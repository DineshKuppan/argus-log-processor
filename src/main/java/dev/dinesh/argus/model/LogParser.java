package dev.dinesh.argus.model;

/**
 * Interface for log parsers. Implementations should provide logic to determine if a log line can be
 * parsed, parse a log line into a {@link LogRecord}, and return the parser's name.
 */
public interface LogParser {

  boolean canParse(String sampleLine);

  LogRecord parseLine(String line) throws Exception;

  String name();
}
