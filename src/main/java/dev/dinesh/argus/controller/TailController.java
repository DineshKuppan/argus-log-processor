package dev.dinesh.argus.controller;

import dev.dinesh.argus.config.TailProperties;
import dev.dinesh.argus.tail.TailConnection;
import dev.dinesh.argus.tail.TailFilter;
import dev.dinesh.argus.tail.TailHub;
import dev.dinesh.argus.tail.TailLimitException;
import dev.dinesh.argus.tail.TailWriter;
import dev.dinesh.argus.tenant.CurrentTenant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live tail (v2, {@code P4_PLAN.md}): Server-Sent Events of the caller tenant's lines as they are
 * accepted, after suppression. Each event's {@code data} is one line as JSON; {@code event:
 * dropped} reports lines this client missed (it was slow, or over its rate), and {@code event:
 * reset} a {@code Last-Event-ID} that can no longer be resumed.
 */
@RestController
@RequestMapping("/api/v2/tail")
public class TailController {

  private static final MediaType SSE_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

  private final TailHub hub;
  private final TailProperties props;

  public TailController(TailHub hub, TailProperties props) {
    this.hub = hub;
    this.props = props;
  }

  /**
   * @param level only lines at least this severe ({@code warn}, {@code error}, …)
   * @param source only this source or stream id
   * @param q only lines whose body contains this, ignoring case
   */
  @GetMapping
  public ResponseEntity<SseEmitter> tail(
      @CurrentTenant String tenantId,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String q,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    TailFilter filter = TailFilter.parse(level, source, q);
    SseEmitter emitter = new SseEmitter(props.maxDuration().toMillis());
    EmitterWriter writer = new EmitterWriter(emitter);
    TailConnection connection = hub.open(tenantId, filter, lastEventId, writer);
    emitter.onTimeout(
        () -> {
          connection.close("max_duration");
          writer.close();
        });
    emitter.onError(e -> connection.close("client_gone"));
    emitter.onCompletion(() -> connection.close("completed"));
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .header("X-Accel-Buffering", "no") // nginx would otherwise buffer the stream
        .body(emitter);
  }

  @ExceptionHandler(TailLimitException.class)
  ResponseEntity<Map<String, String>> limit(TailLimitException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", e.getMessage()));
  }

  /** The content type is set here, so an {@code Accept: text/event-stream} client still gets it. */
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", e.getMessage()));
  }

  /** Writes pre-formatted SSE frames: one write and one flush per batch of events. */
  static final class EmitterWriter implements TailWriter {
    private final SseEmitter emitter;
    private final AtomicBoolean completed = new AtomicBoolean();

    EmitterWriter(SseEmitter emitter) {
      this.emitter = emitter;
    }

    @Override
    public void write(String frames) throws IOException {
      emitter.send(Set.of(new DataWithMediaType(frames, SSE_TEXT)));
    }

    @Override
    public void close() {
      if (completed.compareAndSet(false, true)) {
        try {
          emitter.complete();
        } catch (RuntimeException e) {
          // the response is already gone
        }
      }
    }
  }
}
