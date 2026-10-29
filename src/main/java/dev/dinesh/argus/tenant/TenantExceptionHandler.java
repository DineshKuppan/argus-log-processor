package dev.dinesh.argus.tenant;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 401 for requests without a valid credential, on every controller. The content type is set here,
 * so a client that accepts only {@code text/event-stream} (a live tail) still gets the 401, not a
 * 500 for an unwritable error body.
 */
@RestControllerAdvice
public class TenantExceptionHandler {

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<Map<String, String>> unauthorized(UnauthenticatedException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"argus\"")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", e.getMessage()));
  }
}
