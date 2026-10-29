package dev.dinesh.argus.controller;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dinesh.argus.export.ExportJobService;
import dev.dinesh.argus.export.ExportJobService.ExportBusyException;
import dev.dinesh.argus.export.ExportJobService.ExportRequest;
import dev.dinesh.argus.export.ExportJobService.ExportSourceNotFoundException;
import dev.dinesh.argus.export.ExportJobStatus;
import dev.dinesh.argus.ingest.IngestJobService.IngestBusyException;
import dev.dinesh.argus.ingest.PushRejectedException;
import dev.dinesh.argus.tenant.CurrentTenant;
import java.io.IOException;
import java.time.Instant;
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
 * Exports from Elasticsearch / OpenSearch or Coralogix into a stream ({@code P3_PLAN.md} D7–D9), as
 * replay-lane jobs. Sources are configured per tenant; a request names one, never a URL.
 */
@RestController
@RequestMapping("/api/v2/ingest/exports")
public class ExportController {

  private final ExportJobService exports;

  public ExportController(ExportJobService exports) {
    this.exports = exports;
  }

  /**
   * Request body for {@code POST /exports}.
   *
   * @param source a configured export source of the caller's tenant
   * @param streamId the stream the export's lines are ingested as
   * @param query an Elasticsearch query object, or DataPrime text for Coralogix; omit for
   *     everything
   * @param from start of the range, inclusive (ISO-8601)
   * @param to end of the range, exclusive
   * @param fromBeginning export again even if this export already ran on the stream
   */
  public record StartExportRequest(
      String source,
      String streamId,
      JsonNode query,
      Instant from,
      Instant to,
      Boolean fromBeginning) {}

  @PostMapping
  public ResponseEntity<ExportJobStatus> start(
      @CurrentTenant String tenantId, @RequestBody StartExportRequest request) throws IOException {
    JsonNode query = request.query() == null || request.query().isNull() ? null : request.query();
    var job =
        exports.submit(
            tenantId,
            new ExportRequest(
                request.source(),
                request.streamId(),
                query,
                request.from(),
                request.to(),
                Boolean.TRUE.equals(request.fromBeginning())));
    return ResponseEntity.accepted()
        .header(HttpHeaders.LOCATION, "/api/v2/ingest/exports/" + job.id())
        .body(ExportJobStatus.of(job));
  }

  @GetMapping("/{jobId}")
  public ResponseEntity<ExportJobStatus> job(
      @CurrentTenant String tenantId, @PathVariable String jobId) {
    return exports
        .find(tenantId, jobId)
        .map(job -> ResponseEntity.ok(ExportJobStatus.of(job)))
        .orElse(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/{jobId}")
  public ResponseEntity<ExportJobStatus> cancel(
      @CurrentTenant String tenantId, @PathVariable String jobId) {
    return exports
        .cancel(tenantId, jobId)
        .map(job -> ResponseEntity.accepted().body(ExportJobStatus.of(job)))
        .orElse(ResponseEntity.notFound().build());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(ExportSourceNotFoundException.class)
  ResponseEntity<Map<String, String>> notFound(ExportSourceNotFoundException e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  /** The stream has an export running, too many streams, or is a file source's id. */
  @ExceptionHandler({ExportBusyException.class, PushRejectedException.class})
  ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  /** The replay lane's slots are busy, or the WAL is full. */
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
