package dev.dinesh.argus.controller;

import dev.dinesh.argus.search.SearchException;
import dev.dinesh.argus.search.SearchQuery;
import dev.dinesh.argus.search.SearchResult;
import dev.dinesh.argus.search.SearchService;
import dev.dinesh.argus.tenant.CurrentTenant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search of the hot tier (v2, {@code P5_PLAN.md} D6): the caller tenant's lines in {@code [from,
 * to)}, newest first, at most 14 days and within the hot window. Pages continue with the previous
 * page's {@code next} as {@code cursor}; lines arriving meanwhile can shift a page boundary by a
 * line (no point in time is held between pages). 404 without a hot tier.
 */
@RestController
@RequestMapping("/api/v2/search")
public class SearchController {

  private final ObjectProvider<SearchService> service;

  public SearchController(ObjectProvider<SearchService> service) {
    this.service = service;
  }

  /**
   * @param from start, inclusive (ISO 8601)
   * @param to end, exclusive (ISO 8601)
   * @param q words in the body; {@code "a phrase"}, {@code -word}, {@code a | b}, {@code prefix*}
   * @param level only lines at least this severe
   * @param source only this source or stream id
   * @param service only this {@code service.name}
   * @param attr {@code key:value}, repeatable: the attribute must equal the value (as text)
   * @param size hits per page (default 100)
   * @param cursor the previous page's {@code next}
   */
  @GetMapping
  public ResponseEntity<?> search(
      @CurrentTenant String tenantId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String service,
      @RequestParam(required = false) List<String> attr,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String cursor) {
    SearchService search = this.service.getIfAvailable();
    if (search == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("error", "No hot tier is configured (argus.hot.store)"));
    }
    SearchQuery query =
        SearchQuery.parse(
            tenantId,
            from,
            to,
            q,
            level,
            source,
            service,
            attr,
            size,
            search.oldest(),
            search.props());
    SearchResult result = search.search(query, cursor);
    return ResponseEntity.ok(result);
  }

  @ExceptionHandler(SearchException.class)
  ResponseEntity<Map<String, String>> unavailable(SearchException e) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON);
    if (e.retryAfterSeconds() > 0) {
      response.header(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()));
    }
    return response.body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", e.getMessage()));
  }
}
