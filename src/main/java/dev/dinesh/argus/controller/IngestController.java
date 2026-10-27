package dev.dinesh.argus.controller;

import dev.dinesh.argus.ingest.IngestJobService;
import dev.dinesh.argus.ingest.IngestJobService.JobSlotsBusyException;
import dev.dinesh.argus.ingest.IngestJobService.SourceBusyException;
import dev.dinesh.argus.ingest.IngestJobStatus;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Streaming ingest API (v2). Jobs run asynchronously with bounded memory; poll the job for
 * progress. Unlike v1, results are persisted to the configured sink rather than returned inline.
 */
@RestController
@RequestMapping("/api/v2/ingest")
public class IngestController {

  /** Seconds a client should wait before retrying when all job slots are busy. */
  static final String RETRY_AFTER_SECONDS = "30";

  private final IngestJobService jobs;

  public IngestController(IngestJobService jobs) {
    this.jobs = jobs;
  }

  /**
   * Request body for {@code POST /file}.
   *
   * @param path file to ingest; must be under {@code argus.ingest.allowed-roots}
   * @param sourceId optional stable id; defaults to a hash of the file's real path
   * @param fromBeginning ignore any checkpoint and start at byte 0
   * @param format primary format ({@code nginx_access}, {@code json}, {@code cri}, {@code kv},
   *     {@code raw}, {@code logback}); detected from the file's first lines when omitted
   * @param pattern Logback layout such as {@code %d{ISO8601} [%t] %-5p %c - %m%n}; implies {@code
   *     logback}
   */
  public record IngestFileRequest(
      String path, String sourceId, Boolean fromBeginning, String format, String pattern) {}

  @PostMapping("/file")
  public ResponseEntity<IngestJobStatus> ingestFile(@RequestBody IngestFileRequest request)
      throws IOException {
    boolean fromBeginning = Boolean.TRUE.equals(request.fromBeginning());
    var job =
        jobs.submit(
            request.path(), request.sourceId(), fromBeginning, request.format(), request.pattern());
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/v2/ingest/jobs/" + job.id())
        .body(IngestJobStatus.of(job));
  }

  @GetMapping("/jobs/{jobId}")
  public ResponseEntity<IngestJobStatus> job(@PathVariable String jobId) {
    return jobs.find(jobId)
        .map(job -> ResponseEntity.ok(IngestJobStatus.of(job)))
        .orElse(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/jobs/{jobId}")
  public ResponseEntity<IngestJobStatus> cancel(@PathVariable String jobId) {
    return jobs.cancel(jobId)
        .map(job -> ResponseEntity.accepted().body(IngestJobStatus.of(job)))
        .orElse(ResponseEntity.notFound().build());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NoSuchFileException.class)
  ResponseEntity<Map<String, String>> notFound(NoSuchFileException e) {
    return error(HttpStatus.NOT_FOUND, "File not found: " + e.getFile());
  }

  @ExceptionHandler(SourceBusyException.class)
  ResponseEntity<Map<String, String>> conflict(SourceBusyException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(JobSlotsBusyException.class)
  ResponseEntity<Map<String, String>> tooManyRequests(JobSlotsBusyException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .body(Map.of("error", e.getMessage()));
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
