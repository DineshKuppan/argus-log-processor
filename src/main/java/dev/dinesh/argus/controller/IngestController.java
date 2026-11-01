package dev.dinesh.argus.controller;

import dev.dinesh.argus.ingest.IngestJobService;
import dev.dinesh.argus.ingest.IngestJobService.IngestBusyException;
import dev.dinesh.argus.ingest.IngestJobService.SourceBusyException;
import dev.dinesh.argus.ingest.IngestJobStatus;
import dev.dinesh.argus.ingest.Lane;
import dev.dinesh.argus.tenant.CurrentTenant;
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
 * Every call acts for the tenant of its API key: sources, output and jobs are per tenant.
 */
@RestController
@RequestMapping("/api/v2/ingest")
public class IngestController {

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
   * @param lane {@code live} or {@code replay} (its own slots and read budget); defaults to {@code
   *     replay} with {@code fromBeginning}, else {@code live}
   */
  public record IngestFileRequest(
      String path,
      String sourceId,
      Boolean fromBeginning,
      String format,
      String pattern,
      String lane) {}

  @PostMapping("/file")
  public ResponseEntity<IngestJobStatus> ingestFile(
      @CurrentTenant String tenantId, @RequestBody IngestFileRequest request) throws IOException {
    boolean fromBeginning = Boolean.TRUE.equals(request.fromBeginning());
    var job =
        jobs.submit(
            tenantId,
            request.path(),
            request.sourceId(),
            fromBeginning,
            request.format(),
            request.pattern(),
            Lane.parse(request.lane()));
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/v2/ingest/jobs/" + job.id())
        .body(IngestJobStatus.of(job));
  }

  @GetMapping("/jobs/{jobId}")
  public ResponseEntity<Object> job(@CurrentTenant String tenantId, @PathVariable String jobId) {
    return jobs.find(tenantId, jobId)
        .<ResponseEntity<Object>>map(job -> ResponseEntity.ok(IngestJobStatus.of(job)))
        .orElseGet(() -> missing(jobs.elsewhere(jobId)));
  }

  @DeleteMapping("/jobs/{jobId}")
  public ResponseEntity<Object> cancel(@CurrentTenant String tenantId, @PathVariable String jobId) {
    return jobs.cancel(tenantId, jobId)
        .<ResponseEntity<Object>>map(job -> ResponseEntity.accepted().body(IngestJobStatus.of(job)))
        .orElseGet(() -> missing(jobs.elsewhere(jobId)));
  }

  /** 404; naming the instance when another one started the job (P6 D12). */
  private static ResponseEntity<Object> missing(String elsewhere) {
    return elsewhere == null
        ? ResponseEntity.notFound().build()
        : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", elsewhere));
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

  /** Every job slot is busy, or the WAL is full while the archive catches up. */
  @ExceptionHandler(IngestBusyException.class)
  ResponseEntity<Map<String, String>> tooManyRequests(IngestBusyException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()))
        .body(Map.of("error", e.getMessage()));
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }
}
