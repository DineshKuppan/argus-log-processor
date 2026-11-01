package dev.dinesh.argus.controller;

import dev.dinesh.argus.ingest.PushIngestService;
import dev.dinesh.argus.ingest.PushIngestService.PushRequest;
import dev.dinesh.argus.ingest.PushIngestService.PushResult;
import dev.dinesh.argus.ingest.PushRejectedException;
import dev.dinesh.argus.ingest.StreamRegistry;
import dev.dinesh.argus.ingest.StreamStatus;
import dev.dinesh.argus.tenant.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP push ingest (v2). An agent posts lines (NDJSON or plain text, optionally gzip) to a stream;
 * they are acknowledged once durable in the stream's spool and ingested by a stream run shortly
 * after. Streams, spools and quotas are per tenant (the caller's API key).
 */
@RestController
@RequestMapping("/api/v2/ingest/streams")
public class StreamController {

  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private final PushIngestService push;
  private final StreamRegistry streams;

  public StreamController(PushIngestService push, StreamRegistry streams) {
    this.push = push;
    this.streams = streams;
  }

  /**
   * Spools the body's lines. 202 with where they landed; 200 with the original offsets for a
   * repeated {@code Idempotency-Key}.
   *
   * @param format the stream's format from now on; detected from each spool file when never given
   * @param pattern a Logback layout; implies {@code logback}
   */
  @PostMapping("/{streamId}")
  public ResponseEntity<PushResult> pushLines(
      @CurrentTenant String tenantId,
      @PathVariable String streamId,
      @RequestParam(required = false) String format,
      @RequestParam(required = false) String pattern,
      @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String encoding,
      @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
      HttpServletRequest request)
      throws IOException {
    PushResult result =
        push.push(
            new PushRequest(
                tenantId,
                streamId,
                request.getInputStream(),
                encoding,
                request.getContentLengthLong(),
                idempotencyKey,
                format,
                pattern));
    return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(result);
  }

  @GetMapping("/{streamId}")
  public ResponseEntity<StreamStatus> stream(
      @CurrentTenant String tenantId, @PathVariable String streamId) {
    return streams
        .find(tenantId, streamId)
        .map(s -> ResponseEntity.ok(StreamStatus.of(s)))
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping
  public List<StreamStatus> list(@CurrentTenant String tenantId) {
    return streams.forTenant(tenantId).stream().map(StreamStatus::of).toList();
  }

  @ExceptionHandler(PushRejectedException.class)
  ResponseEntity<Map<String, String>> rejected(PushRejectedException e) {
    HttpStatus status =
        switch (e.reason()) {
          case BUSY, KEY_IN_FLIGHT, QUOTA, WAL_FULL, SPOOL_FULL -> HttpStatus.TOO_MANY_REQUESTS;
          case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
          case TOO_MANY_STREAMS, SOURCE_IN_USE -> HttpStatus.CONFLICT;
          case UNSUPPORTED_ENCODING -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
          case DRAINING -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    if (e.retryAfterSeconds() > 0) {
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
    }
    return response.body(Map.of("error", e.getMessage(), "reason", e.reason().name()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }
}
