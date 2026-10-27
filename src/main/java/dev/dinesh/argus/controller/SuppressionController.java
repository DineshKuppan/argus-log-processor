package dev.dinesh.argus.controller;

import dev.dinesh.argus.pipeline.EnvelopeFactory;
import dev.dinesh.argus.suppression.SuppressionDecision;
import dev.dinesh.argus.suppression.Suppressor;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Print this message only if it hasn't been printed in the last 10 seconds": answers ALLOW or DENY
 * immediately, per tenant. File ingest does not go through this (files are backfill, guardrail 8);
 * HTTP push ingest (P3) will.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/suppression")
public class SuppressionController {

  /** Stand-in for P3's API keys. The tenant never comes from the body (guardrail 4). */
  static final String TENANT_HEADER = "X-Argus-Tenant";

  public record CheckRequest(String message) {}

  private final Suppressor suppressor;

  public SuppressionController(Suppressor suppressor) {
    this.suppressor = suppressor;
  }

  @PostMapping("/check")
  public SuppressionDecision check(
      @RequestHeader(name = TENANT_HEADER, defaultValue = EnvelopeFactory.DEFAULT_TENANT)
          String tenantId,
      @RequestBody CheckRequest request) {
    long start = System.nanoTime();
    SuppressionDecision decision = null;
    try {
      decision = suppressor.check(tenantId, request.message());
      return decision;
    } finally {
      // Canonical line per request (guardrail 9), failures included. DEBUG because this endpoint
      // is called once per log message: at 4k checks/s an INFO line would outweigh the logs.
      log.debug(
          "Suppression check tenant={} decision={} suppressedCount={} durationUs={}",
          tenantId,
          decision == null ? "REJECTED" : decision.decision(),
          decision == null ? 0 : decision.suppressedCount(),
          (System.nanoTime() - start) / 1_000);
    }
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }
}
