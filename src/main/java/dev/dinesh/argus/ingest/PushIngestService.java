package dev.dinesh.argus.ingest;

import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.PushProperties;
import dev.dinesh.argus.ingest.PushRejectedException.Reason;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.parser.FormatDetector;
import dev.dinesh.argus.parser.LogParser;
import dev.dinesh.argus.parser.ParserChain;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.pipeline.EnvelopeFactory;
import dev.dinesh.argus.quota.QuotaDecision;
import dev.dinesh.argus.quota.QuotaService;
import dev.dinesh.argus.source.SourceOffset;
import dev.dinesh.argus.source.StreamSpool;
import dev.dinesh.argus.suppression.Suppressor;
import dev.dinesh.argus.tail.TailEvent;
import dev.dinesh.argus.tail.TailHub;
import dev.dinesh.argus.tenant.TenantIds;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * HTTP push ingest ({@code P3_PLAN.md} D1–D5): admits a request through each push bound in turn,
 * suppresses repeated messages at receive time, and appends the remaining lines to the stream's
 * spool, durably, before the caller is answered. The stream runner ingests the spool later.
 */
@Slf4j
@Service
public class PushIngestService {

  public static final int MAX_IDEMPOTENCY_KEY_CHARS = 256;

  /** About how long until a push request slot frees up. */
  static final int BUSY_RETRY_AFTER_SECONDS = 1;

  /** About how long the runs need to drain a full spool. */
  static final int SPOOL_FULL_RETRY_AFTER_SECONDS = 30;

  private static final int GZIP_BUFFER_BYTES = 64 * 1024;

  /**
   * One push request.
   *
   * @param contentEncoding {@code gzip}, {@code identity} or null
   * @param contentLength the body's length on the wire, or -1 if unknown
   * @param idempotencyKey retries with the same key are answered from the first request
   * @param format the stream's format from now on ({@code json}, …), or null to keep or detect it
   * @param pattern a Logback layout; implies format {@code logback}
   */
  public record PushRequest(
      String tenantId,
      String streamId,
      InputStream body,
      String contentEncoding,
      long contentLength,
      String idempotencyKey,
      String format,
      String pattern) {}

  /**
   * What was spooled.
   *
   * @param accepted lines spooled for ingest
   * @param suppressed lines dropped as repeats within the suppression window
   * @param truncated accepted lines cut to {@code argus.ingest.max-line-bytes}
   * @param duplicate the {@code Idempotency-Key} was seen before: nothing was written, and the
   *     offsets are the first request's (the line counts are then 0)
   */
  public record PushResult(
      String streamId,
      int accepted,
      int suppressed,
      int truncated,
      int spool,
      long startOffset,
      long endOffset,
      boolean duplicate,
      Instant receivedAt) {}

  private final StreamRegistry streams;
  private final IngestPipeline pipeline;
  private final QuotaService quotas;
  private final Suppressor suppressor;
  private final ParserRegistry parsers;
  private final PushProperties props;
  private final int maxLineBytes;
  private final Semaphore requestSlots;
  private final Set<String> keysInFlight = ConcurrentHashMap.newKeySet();
  private final ArgusMetrics metrics;

  /** Where accepted lines are published, or null for none. */
  private final TailHub tail;

  public PushIngestService(
      StreamRegistry streams,
      IngestPipeline pipeline,
      QuotaService quotas,
      Suppressor suppressor,
      ParserRegistry parsers,
      PushProperties props,
      IngestProperties ingest) {
    this(streams, pipeline, quotas, suppressor, parsers, props, ingest, ArgusMetrics.noop(), null);
  }

  @Autowired
  public PushIngestService(
      StreamRegistry streams,
      IngestPipeline pipeline,
      QuotaService quotas,
      Suppressor suppressor,
      ParserRegistry parsers,
      PushProperties props,
      IngestProperties ingest,
      ObjectProvider<ArgusMetrics> metrics,
      ObjectProvider<TailHub> tail) {
    this(
        streams,
        pipeline,
        quotas,
        suppressor,
        parsers,
        props,
        ingest,
        metrics.getIfAvailable(ArgusMetrics::noop),
        tail.getIfAvailable());
  }

  public PushIngestService(
      StreamRegistry streams,
      IngestPipeline pipeline,
      QuotaService quotas,
      Suppressor suppressor,
      ParserRegistry parsers,
      PushProperties props,
      IngestProperties ingest,
      ArgusMetrics metrics,
      TailHub tail) {
    this.metrics = metrics;
    this.tail = tail;
    this.streams = streams;
    this.pipeline = pipeline;
    this.quotas = quotas;
    this.suppressor = suppressor;
    this.parsers = parsers;
    this.props = props;
    this.maxLineBytes = Math.toIntExact(ingest.maxLineBytes().toBytes());
    this.requestSlots = new Semaphore(props.maxConcurrentRequests());
  }

  /**
   * Spools the request's lines and returns once they are durable.
   *
   * @throws PushRejectedException at a push bound; nothing was spooled
   * @throws IllegalArgumentException for an invalid stream id, key, format or gzip body
   */
  public PushResult push(PushRequest request) throws IOException {
    long startNanos = System.nanoTime();
    Instant receivedAt = Instant.now();
    Counts counts = new Counts();
    PushResult result = null;
    Throwable error = null;
    try {
      result = admitAndSpool(request, receivedAt, counts);
      return result;
    } catch (Throwable t) {
      error = t;
      throw t;
    } finally {
      long nanos = System.nanoTime() - startNanos;
      String outcome = outcome(result, error);
      // Canonical line: one per push request, accepted or not.
      log.info(
          "Push request finished tenant={} stream={} outcome={} error={} bodyBytes={} lines={}"
              + " accepted={} suppressed={} truncated={} spool={} startOffset={} endOffset={}"
              + " durationMs={}",
          request.tenantId(),
          request.streamId(),
          outcome,
          error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage(),
          counts.bodyBytes,
          counts.lines,
          result == null ? 0 : result.accepted(),
          result == null ? 0 : result.suppressed(),
          result == null ? 0 : result.truncated(),
          result == null ? null : result.spool(),
          result == null ? null : result.startOffset(),
          result == null ? null : result.endOffset(),
          TimeUnit.NANOSECONDS.toMillis(nanos));
      metrics.pushRequest(
          request.tenantId() == null ? "unknown" : request.tenantId(),
          outcome,
          nanos,
          result == null ? 0 : result.accepted(),
          result == null ? 0 : result.suppressed(),
          result == null ? 0 : result.truncated());
    }
  }

  private PushResult admitAndSpool(PushRequest request, Instant receivedAt, Counts counts)
      throws IOException {
    String tenantId = TenantIds.requireValid(request.tenantId());
    String streamId = SourceOffset.requireValidSourceId(request.streamId());
    String key = validKey(request.idempotencyKey());
    LogParser requested = parsers.resolve(request.format(), request.pattern()).orElse(null);
    // Cheapest checks first, all before the body is read.
    if (!requestSlots.tryAcquire()) {
      throw new PushRejectedException(
          Reason.BUSY,
          BUSY_RETRY_AFTER_SECONDS,
          "All " + props.maxConcurrentRequests() + " push request slots are busy; retry later");
    }
    try {
      if (pipeline.walFull()) {
        throw new PushRejectedException(
            Reason.WAL_FULL,
            IngestJobService.WalFullException.RETRY_AFTER_SECONDS,
            "The WAL is full while the archive catches up; retry later");
      }
      if (streams.pendingBytes() >= props.maxSpoolBytes().toBytes()) {
        throw new PushRejectedException(
            Reason.SPOOL_FULL,
            SPOOL_FULL_RETRY_AFTER_SECONDS,
            "The spool holds argus.push.max-spool-bytes not yet ingested; retry later");
      }
      if (key == null) {
        return readAndSpool(request, tenantId, streamId, requested, null, receivedAt, counts);
      }
      // One request per key at a time: a retry racing its original would otherwise miss the key,
      // have its lines suppressed as the original's copies, and could be stored under the key.
      String inFlight = tenantId + "/" + streamId + "/" + key;
      if (!keysInFlight.add(inFlight)) {
        throw new PushRejectedException(
            Reason.KEY_IN_FLIGHT,
            BUSY_RETRY_AFTER_SECONDS,
            "A request with this Idempotency-Key is still in progress; retry later");
      }
      try {
        // Not creating the stream: a request that is then rejected must not take a stream slot.
        Optional<PushStream> existing = streams.find(tenantId, streamId);
        Optional<StreamSpool.Append> earlier = existing.flatMap(s -> s.spool().find(key));
        if (earlier.isPresent()) {
          PushStream stream = existing.get();
          stream.requests.incrementAndGet();
          stream.lastRequestAt = receivedAt;
          return duplicate(stream, earlier.get());
        }
        return readAndSpool(request, tenantId, streamId, requested, key, receivedAt, counts);
      } finally {
        keysInFlight.remove(inFlight);
      }
    } finally {
      requestSlots.release();
    }
  }

  private PushResult readAndSpool(
      PushRequest request,
      String tenantId,
      String streamId,
      LogParser requested,
      String key,
      Instant receivedAt,
      Counts counts)
      throws IOException {
    byte[] body = readBody(request);
    counts.bodyBytes = body.length;
    counts.lines = countLines(body);
    if (counts.lines > props.maxRequestLines()) {
      throw new PushRejectedException(
          Reason.TOO_LARGE,
          "Request has more than "
              + props.maxRequestLines()
              + " lines (argus.push.max-request-lines)");
    }
    QuotaDecision quota = quotas.takeIngestBytes(tenantId, body.length);
    if (!quota.allowed()) {
      throw new PushRejectedException(
          Reason.QUOTA,
          quota.retryAfterSeconds(),
          "Tenant is over its ingest quota (argus.quota.ingest-bytes-per-second); retry later");
    }
    PushStream stream = streams.getOrCreate(tenantId, streamId);
    stream.requests.incrementAndGet();
    stream.lastRequestAt = receivedAt;
    if (requested != null) {
      stream.useParser(requested);
    }
    return spool(stream, body, key, receivedAt);
  }

  private PushResult spool(PushStream stream, byte[] body, String key, Instant receivedAt)
      throws IOException {
    // Lines are parsed to find their messages (suppression) and, while the tenant has a tail open,
    // to publish them; otherwise not at all.
    boolean tailed = tail != null && tail.isWatched(stream.tenantId());
    ParserChain chain = props.suppress() || tailed ? messageChain(stream, body) : null;
    List<ParserChain.Parsed> tailLines = tailed ? new ArrayList<>() : null;
    List<Integer> tailLengths = tailed ? new ArrayList<>() : null;
    List<String> allowedKeys = new ArrayList<>();
    byte[] out = body;
    int written = 0;
    int accepted = 0;
    int suppressed = 0;
    int truncated = 0;
    // Compacts the accepted lines in place: what is written never overtakes what is read.
    for (int start = 0; start < body.length; ) {
      int newline = indexOf(body, (byte) '\n', start);
      int end = newline < 0 ? body.length : newline;
      int next = newline < 0 ? body.length : newline + 1;
      if (isBlank(body, start, end)) {
        start = next;
        continue;
      }
      int length = Math.min(end - start, maxLineBytes);
      String line = null;
      ParserChain.Parsed parsed = null;
      if (chain != null) {
        int textLength = length > 0 && body[start + length - 1] == '\r' ? length - 1 : length;
        line = new String(body, start, textLength, StandardCharsets.UTF_8);
        parsed = chain.parse(line);
      }
      if (props.suppress() && isSuppressed(stream.tenantId(), parsed, line, allowedKeys)) {
        suppressed++;
        start = next;
        continue;
      }
      if (tailLines != null && parsed != null) {
        tailLines.add(parsed);
        tailLengths.add(line.length());
      }
      if (length < end - start) {
        truncated++;
      }
      if (written + length + 1 > out.length) {
        // Only the last line can lack its newline, and only it can need one more byte.
        out = Arrays.copyOf(out, written + length + 1);
      }
      System.arraycopy(body, start, out, written, length);
      written += length;
      out[written++] = '\n';
      accepted++;
      start = next;
    }
    StreamSpool.Append append;
    try {
      append = stream.spool().append(out, written, key, receivedAt);
    } catch (IOException | RuntimeException e) {
      // Nothing was spooled: the client retries, and its lines must not be suppressed as copies.
      suppressor.release(allowedKeys);
      throw e;
    }
    if (append.duplicate()) {
      suppressor.release(allowedKeys);
      return duplicate(stream, append);
    }
    stream.acceptedLines.addAndGet(accepted);
    stream.suppressedLines.addAndGet(suppressed);
    stream.truncatedLines.addAndGet(truncated);
    if (tailLines != null && !tailLines.isEmpty()) {
      publish(stream, tailLines, tailLengths, receivedAt);
    }
    return new PushResult(
        stream.streamId(),
        accepted,
        suppressed,
        truncated,
        append.spool(),
        append.startOffset(),
        append.endOffset(),
        false,
        receivedAt);
  }

  private static PushResult duplicate(PushStream stream, StreamSpool.Append original) {
    stream.duplicates.incrementAndGet();
    return new PushResult(
        stream.streamId(),
        0,
        0,
        0,
        original.spool(),
        original.startOffset(),
        original.endOffset(),
        true,
        original.receivedAt());
  }

  /** Suppression compares messages, so lines are parsed with the stream's format to find them. */
  private ParserChain messageChain(PushStream stream, byte[] body) {
    LogParser parser = stream.messageParser();
    if (parser == null) {
      parser = parsers.detect(sample(body)).parser();
      stream.detectedMessageParser(parser);
    }
    return parsers.chain(parser, true);
  }

  /** Durable in the spool now: shown on the tail at once, not when the stream runner ingests it. */
  private void publish(
      PushStream stream,
      List<ParserChain.Parsed> lines,
      List<Integer> lengths,
      Instant receivedAt) {
    // Generation and offset are assigned when the spool is ingested, so these envelopes' ids are
    // never shown (TailEvent.ofPush).
    EnvelopeFactory envelopes = new EnvelopeFactory(stream.tenantId(), stream.streamId(), 0);
    List<TailEvent> events = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      events.add(TailEvent.ofPush(envelopes.create(lines.get(i), 0, receivedAt), lengths.get(i)));
    }
    tail.publish(stream.tenantId(), events);
  }

  private boolean isSuppressed(
      String tenantId, ParserChain.Parsed parsed, String line, List<String> allowedKeys) {
    String message =
        parsed == null || parsed.record().getMessage() == null
            ? line
            : parsed.record().getMessage();
    if (message.length() > Suppressor.MAX_MESSAGE_CHARS) {
      return false; // too long to fingerprint cheaply: kept, like any line suppression can't judge
    }
    return !suppressor.check(tenantId, message, allowedKeys).allowed();
  }

  private byte[] readBody(PushRequest request) throws IOException {
    long max = props.maxRequestBytes().toBytes();
    String encoding = request.contentEncoding();
    InputStream in = request.body();
    if (encoding == null || encoding.isBlank() || encoding.equalsIgnoreCase("identity")) {
      if (request.contentLength() > max) {
        throw tooLarge();
      }
    } else if (encoding.equalsIgnoreCase("gzip") || encoding.equalsIgnoreCase("x-gzip")) {
      try {
        in = new GZIPInputStream(in, GZIP_BUFFER_BYTES);
      } catch (ZipException | EOFException e) {
        throw new IllegalArgumentException("Body is not valid gzip: " + e.getMessage());
      }
    } else {
      throw new PushRejectedException(
          Reason.UNSUPPORTED_ENCODING, "Unsupported Content-Encoding: " + encoding);
    }
    byte[] body;
    try {
      // Reads at most one byte past the limit, whatever the body claims (gzip bombs included).
      body = in.readNBytes(Math.toIntExact(max + 1));
    } catch (ZipException | EOFException e) {
      throw new IllegalArgumentException("Body is not valid gzip: " + e.getMessage());
    }
    if (body.length > max) {
      throw tooLarge();
    }
    return body;
  }

  private PushRejectedException tooLarge() {
    return new PushRejectedException(
        Reason.TOO_LARGE,
        "Request body is larger than "
            + props.maxRequestBytes()
            + " (argus.push.max-request-bytes)");
  }

  private static String validKey(String key) {
    if (key == null) {
      return null;
    }
    if (key.isEmpty() || key.length() > MAX_IDEMPOTENCY_KEY_CHARS) {
      throw new IllegalArgumentException(
          "Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_CHARS + " characters");
    }
    if (!key.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
      throw new IllegalArgumentException("Idempotency-Key must be printable ASCII without spaces");
    }
    return key;
  }

  private static int countLines(byte[] body) {
    int lines = 0;
    for (int start = 0; start < body.length; ) {
      int newline = indexOf(body, (byte) '\n', start);
      int end = newline < 0 ? body.length : newline;
      if (!isBlank(body, start, end)) {
        lines++;
      }
      start = newline < 0 ? body.length : newline + 1;
    }
    return lines;
  }

  private static List<String> sample(byte[] body) {
    List<String> lines = new ArrayList<>();
    int limit = Math.min(body.length, FormatDetector.SAMPLE_BYTES);
    for (int start = 0; start < limit && lines.size() < FormatDetector.SAMPLE_LINES; ) {
      int newline = indexOf(body, (byte) '\n', start);
      int end = newline < 0 || newline > limit ? limit : newline;
      if (!isBlank(body, start, end)) {
        lines.add(new String(body, start, end - start, StandardCharsets.UTF_8).strip());
      }
      start = end + 1;
    }
    return lines;
  }

  private static int indexOf(byte[] bytes, byte b, int from) {
    for (int i = from; i < bytes.length; i++) {
      if (bytes[i] == b) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isBlank(byte[] bytes, int start, int end) {
    for (int i = start; i < end; i++) {
      byte b = bytes[i];
      if (b != ' ' && b != '\t' && b != '\r') {
        return false;
      }
    }
    return true;
  }

  private static String outcome(PushResult result, Throwable error) {
    if (result != null) {
      return result.duplicate() ? "DUPLICATE" : "ACCEPTED";
    }
    if (error instanceof PushRejectedException rejected) {
      return "REJECTED_" + rejected.reason();
    }
    return error instanceof IllegalArgumentException ? "BAD_REQUEST" : "FAILED";
  }

  /** Filled in as the request is read, for the canonical line. */
  private static final class Counts {
    long bodyBytes = -1;
    int lines;
  }
}
