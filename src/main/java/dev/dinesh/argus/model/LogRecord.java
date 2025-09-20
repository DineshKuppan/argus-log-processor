package dev.dinesh.argus.model;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single log record with timestamp, level, message, and metadata. Used for storing
 * parsed log entries and their associated information.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LogRecord {

  private Instant timestamp;
  private String level;
  private String message;
  private Map<String, Object> meta;
}
