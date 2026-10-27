package dev.dinesh.argus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import dev.dinesh.argus.aggregate.StreamingAggregator;
import dev.dinesh.argus.aggregate.impl.CountAggregator;
import dev.dinesh.argus.aggregate.impl.CountByLevelAggregator;
import dev.dinesh.argus.aggregate.impl.DurationAggregator;
import dev.dinesh.argus.aggregate.impl.ErrorRateAggregator;
import dev.dinesh.argus.aggregate.impl.TopNEndpointsAggregator;
import dev.dinesh.argus.config.IngestProperties;
import dev.dinesh.argus.config.LogProperties;
import dev.dinesh.argus.filter.FilterSpec;
import dev.dinesh.argus.filter.LogFilter;
import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.FormatDetector;
import dev.dinesh.argus.parser.LogParser;
import dev.dinesh.argus.parser.ParserChain;
import dev.dinesh.argus.parser.ParserRegistry;
import dev.dinesh.argus.source.AllowedPaths;
import dev.dinesh.argus.source.AllowedPaths.OutsideAllowedRootsException;
import dev.dinesh.argus.source.FileSource;
import dev.dinesh.argus.source.RawBatch;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

/**
 * Service for parsing, filtering, analyzing, and exporting log records. Files must be under {@code
 * argus.ingest.allowed-roots}, as for v2 (see {@link LogProperties#enforceAllowedRoots()}).
 * Aggregations stream the file in bounded batches, so they work on files of any size; at most
 * {@code log.max-concurrent-aggregations} run at once (more are rejected, never queued). Methods
 * that return every record load the whole file and are limited to {@code log.max-file-size} (50MB
 * by default).
 */
@Service
@Slf4j
public class LogService {

  /** Source id for v1's one-off reads; v1 keeps no checkpoints, so it only labels batches. */
  private static final String V1_SOURCE_ID = "v1";

  /**
   * v1 used {@code readAllLines}, which never cut a line. Streaming needs some bound, so it is far
   * above ingest's 1MB: one buffer of this size per running aggregation, at most.
   */
  static final DataSize V1_MAX_LINE_BYTES = DataSize.ofMegabytes(16);

  /** Thrown when every aggregation slot is busy; the API answers 429 with Retry-After. */
  public static class AggregationSlotsBusyException extends RuntimeException {
    public AggregationSlotsBusyException(int slots) {
      super("All " + slots + " aggregation slots are busy; retry later");
    }
  }

  private final ObjectMapper mapper = new ObjectMapper();
  private final ParserRegistry parsers;
  private final IngestProperties ingest;
  private final AllowedPaths allowedPaths;
  private final boolean enforceAllowedRoots;
  private final long maxFileSize;
  private final int aggregationSlots;
  private final Semaphore aggregations;

  public LogService(ParserRegistry parsers, IngestProperties ingest, LogProperties props) {
    this.parsers = parsers;
    this.ingest = withMaxLineBytes(ingest, V1_MAX_LINE_BYTES);
    this.allowedPaths = new AllowedPaths(ingest.allowedRoots());
    this.enforceAllowedRoots = props.enforceAllowedRoots();
    this.maxFileSize = props.maxFileSize().toBytes();
    this.aggregationSlots = props.maxConcurrentAggregations();
    this.aggregations = new Semaphore(aggregationSlots);
  }

  private static IngestProperties withMaxLineBytes(IngestProperties p, DataSize maxLineBytes) {
    return new IngestProperties(
        p.batchMaxLines(),
        p.batchMaxBytes(),
        p.segmentBytes(),
        p.rawQueueCapacity(),
        p.parsedQueueCapacity(),
        maxLineBytes,
        p.maxConcurrentJobs(),
        p.allowedRoots(),
        p.checkpointDir(),
        p.keepUnparsed());
  }

  public static String getFileSizeInfo(Path path) throws Exception {
    long bytes = Files.size(path);
    double mb = bytes / (1024.0 * 1024.0);
    double gb = bytes / (1024.0 * 1024.0 * 1024.0);
    return String.format("Size: %d bytes, %.2f MB, %.4f GB", bytes, mb, gb);
  }

  public static String formatSmartSize(long bytes) {
    if (bytes < 1024 * 1024) {
      return bytes + " bytes";
    } else if (bytes < 1024L * 1024 * 1024) {
      double mb = bytes / (1024.0 * 1024.0);
      return String.format("%.2f MB", mb);
    } else {
      double gb = bytes / (1024.0 * 1024.0 * 1024.0);
      return String.format("%.4f GB", gb);
    }
  }

  public List<LogRecord> parseFile(String filePath) throws Exception {
    return parseLines(readLines(filePath));
  }

  /** Parses only the lines that fully match {@code pattern}. */
  public List<LogRecord> parseFile(String filePath, String pattern) throws Exception {
    Pattern linePattern = Pattern.compile(pattern);
    return parseLines(
        readLines(filePath).stream().filter(l -> linePattern.matcher(l).matches()).toList());
  }

  /**
   * Detects the format from the first lines, then parses every line with the shared parser chain.
   * Lines no parser understands are dropped: v1 returns only structured records.
   */
  private List<LogRecord> parseLines(List<String> lines) {
    List<String> nonBlank = lines.stream().filter(l -> !l.isBlank()).toList();
    List<String> sample =
        nonBlank.subList(0, Math.min(FormatDetector.SAMPLE_LINES, nonBlank.size()));
    ParserChain chain = parsers.chain(parsers.detect(sample).parser(), false);
    return nonBlank.stream()
        .map(chain::parse)
        .filter(Objects::nonNull)
        .map(ParserChain.Parsed::record)
        .toList();
  }

  private List<String> readLines(String filePath) throws Exception {
    Path path = resolve(filePath);
    long fileSize = Files.size(path);
    log.info("File size: {}", formatSmartSize(fileSize));
    if (fileSize > maxFileSize) {
      throw new IllegalArgumentException(
          "File size exceeds the " + formatSmartSize(maxFileSize) + " limit (log.max-file-size)");
    }
    List<String> lines = Files.readAllLines(path);
    log.info("Total lines: {}", lines.size());
    return lines;
  }

  /** {@code byLevel}, {@code topEndpoints} and per-minute {@code errorRate}, in one pass. */
  public Map<String, Object> summarize(String filePath, int topN) throws IOException {
    Map<String, StreamingAggregator<?, ?>> aggregators = new LinkedHashMap<>();
    aggregators.put("byLevel", new CountByLevelAggregator());
    aggregators.put("topEndpoints", new TopNEndpointsAggregator(topN));
    aggregators.put("errorRate", perMinuteErrorRate());
    return aggregate(filePath, r -> true, aggregators);
  }

  /**
   * {@code totalLogs}, {@code byLevel}, {@code topEndpoints} and hourly {@code duration} counts of
   * the records matching every filter, in one pass.
   */
  public Map<String, Object> aggregate(String filePath, List<LogFilter> filters, int topN)
      throws IOException {
    Map<String, StreamingAggregator<?, ?>> aggregators = new LinkedHashMap<>();
    aggregators.put("totalLogs", new CountAggregator());
    aggregators.put("byLevel", new CountByLevelAggregator());
    aggregators.put("topEndpoints", new TopNEndpointsAggregator(topN));
    aggregators.put("duration", new DurationAggregator(ChronoUnit.HOURS));
    return aggregate(filePath, r -> filters.stream().allMatch(f -> f.matches(r)), aggregators);
  }

  public List<Map<String, Object>> errorRateOverTime(String filePath) throws IOException {
    return aggregate(filePath, perMinuteErrorRate());
  }

  private static ErrorRateAggregator perMinuteErrorRate() {
    return new ErrorRateAggregator(ChronoUnit.MINUTES, "minute");
  }

  /**
   * Feeds every parsed record of {@code filePath} to {@code consumer}, reading ≤10,000-line batches
   * (guardrail 1), so memory does not grow with the file. Lines no parser understands are skipped.
   * Takes one of the aggregation slots for the duration of the scan.
   *
   * @throws AggregationSlotsBusyException if every slot is taken
   */
  public void forEachRecord(String filePath, Consumer<LogRecord> consumer) throws IOException {
    Path path = resolve(filePath);
    if (!aggregations.tryAcquire()) {
      throw new AggregationSlotsBusyException(aggregationSlots);
    }
    long start = System.nanoTime();
    long lines = 0;
    long records = 0;
    long truncated = 0;
    String format = null;
    String outcome = "FAILED";
    try (FileSource source = FileSource.open(path, V1_SOURCE_ID, ingest)) {
      // v1 reads finished files, so the last line counts even without a trailing newline.
      source.startAt(0, true);
      List<String> sample =
          source.headLines(FormatDetector.SAMPLE_BYTES, FormatDetector.SAMPLE_LINES);
      LogParser primary = parsers.detect(sample).parser();
      format = primary.name();
      ParserChain chain = parsers.chain(primary, false);
      for (RawBatch batch = source.nextBatch(); batch != null; batch = source.nextBatch()) {
        lines += batch.lines().size();
        truncated += batch.truncatedLines();
        for (String line : batch.lines()) {
          ParserChain.Parsed parsed = chain.parse(line);
          if (parsed != null) {
            records++;
            consumer.accept(parsed.record());
          }
        }
      }
      outcome = "COMPLETED";
    } finally {
      aggregations.release();
      // Canonical line per request (guardrail 9).
      log.info(
          "v1 scan finished file={} outcome={} format={} lines={} records={} dropped={}"
              + " truncated={} durationMs={}",
          filePath,
          outcome,
          format,
          lines,
          records,
          lines - records,
          truncated,
          (System.nanoTime() - start) / 1_000_000);
    }
  }

  /**
   * The file v1 may read for {@code filePath}: under the allowed roots, or (with enforcement off)
   * anywhere, with a warning.
   */
  private Path resolve(String filePath) throws IOException {
    try {
      return allowedPaths.requireFile(filePath);
    } catch (OutsideAllowedRootsException e) {
      if (enforceAllowedRoots) {
        throw e;
      }
      log.warn(
          "v1 read outside argus.ingest.allowed-roots file={}; refused once"
              + " log.enforce-allowed-roots=true",
          filePath);
      return Path.of(filePath);
    }
  }

  private <A, T> T aggregate(String filePath, StreamingAggregator<A, T> aggregator)
      throws IOException {
    A state = aggregator.newState();
    forEachRecord(filePath, r -> aggregator.accept(state, r));
    return aggregator.result(state);
  }

  /** Runs every aggregator over the records matching {@code filter}, in one streaming pass. */
  private Map<String, Object> aggregate(
      String filePath, Predicate<LogRecord> filter, Map<String, StreamingAggregator<?, ?>> byName)
      throws IOException {
    List<Running<?, ?>> running =
        byName.values().stream().<Running<?, ?>>map(Running::new).toList();
    forEachRecord(
        filePath,
        r -> {
          if (filter.test(r)) {
            running.forEach(a -> a.accept(r));
          }
        });
    Map<String, Object> results = new LinkedHashMap<>();
    int i = 0;
    for (String name : byName.keySet()) {
      results.put(name, running.get(i++).result());
    }
    return results;
  }

  /** An aggregator bound to its state, so differently-typed aggregators can share one loop. */
  private static final class Running<A, T> {
    private final StreamingAggregator<A, T> aggregator;
    private final A state;

    Running(StreamingAggregator<A, T> aggregator) {
      this.aggregator = aggregator;
      this.state = aggregator.newState();
    }

    void accept(LogRecord record) {
      aggregator.accept(state, record);
    }

    T result() {
      return aggregator.result(state);
    }
  }

  public List<LogRecord> filterLogs(List<LogRecord> logs, FilterSpec spec) {
    Predicate<LogRecord> pred = spec.toPredicate();
    return logs.stream().filter(pred).toList();
  }

  public String exportToJson(Map<String, Object> results) throws Exception {
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results);
  }

  public String exportErrorRateToCsv(List<Map<String, Object>> errorRate) throws Exception {
    StringWriter sw = new StringWriter();
    try (CSVWriter writer = new CSVWriter(sw)) {
      writer.writeNext(new String[] {"minute", "total", "errors", "errorRate"});
      for (Map<String, Object> row : errorRate) {
        writer.writeNext(
            new String[] {
              row.get("minute").toString(),
              row.get("total").toString(),
              row.get("errors").toString(),
              row.get("errorRate").toString()
            });
      }
    }
    return sw.toString();
  }

  /** Hourly ERROR counts as a fixed-width table, in time order; hours without errors omitted. */
  public String errorSummary(String filePath) throws IOException {
    List<Map<String, Object>> rows = aggregate(filePath, new ErrorRateAggregator());

    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-8s %-8s %-40s%n", "Total", "Level", "Time Range"));
    for (Map<String, Object> row : rows) {
      long errors = (long) row.get("errors");
      Object hour = row.get("hour");
      if (errors > 0) {
        sb.append(String.format("%-8d %-8s logs between %s–%s:59%n", errors, "ERROR", hour, hour));
      }
    }
    return sb.toString();
  }
}
