package dev.dinesh.argus.tenant;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 401 for requests without a valid credential, on every controller. */
@RestControllerAdvice
public class TenantExceptionHandler {

  @ExceptionHandler(UnauthenticatedException.class)
  ResponseEntity<Map<String, String>> unauthorized(UnauthenticatedException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .header(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"argus\"")
        .body(Map.of("error", e.getMessage()));
  }
}
