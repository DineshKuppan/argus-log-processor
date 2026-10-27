package dev.dinesh.argus.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The log formats Argus understands. Built-in parsers are listed in priority order, which decides
 * both detection ties and the fallback order. Add a format by implementing {@link LogParser} and
 * registering it here.
 */
@Component
public class ParserRegistry {

  private final List<LogParser> builtIns =
      List.of(
          new ServerAccessLogParser(),
          new JsonLogParser(),
          new CriLogParser(),
          new KeyValueLogParser());
  private final RawTextParser raw = new RawTextParser();
  private final FormatDetector detector = new FormatDetector(builtIns, raw);

  /** Format names a request may name, in priority order, plus {@code raw} and {@code logback}. */
  public List<String> formatNames() {
    List<String> names = new ArrayList<>();
    builtIns.forEach(p -> names.add(p.name()));
    names.add(raw.name());
    names.add(LogbackPatternParser.NAME);
    return List.copyOf(names);
  }

  /**
   * Resolves a requested format; empty means "detect it".
   *
   * @param pattern a Logback layout; implies (and requires) format {@code logback}
   * @throws IllegalArgumentException for an unknown format or an unsupported layout
   */
  public Optional<LogParser> resolve(String format, String pattern) {
    boolean hasFormat = format != null && !format.isBlank();
    if (pattern != null && !pattern.isBlank()) {
      if (hasFormat && !format.equals(LogbackPatternParser.NAME)) {
        throw new IllegalArgumentException(
            "pattern is only valid with format " + LogbackPatternParser.NAME);
      }
      return Optional.of(new LogbackPatternParser(pattern));
    }
    if (!hasFormat) {
      return Optional.empty();
    }
    if (format.equals(LogbackPatternParser.NAME)) {
      throw new IllegalArgumentException("format logback needs a pattern (a Logback layout)");
    }
    LogParser parser =
        Stream.concat(builtIns.stream(), Stream.of(raw))
            .filter(p -> p.name().equals(format))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Unknown format '" + format + "'; known: " + formatNames()));
    return Optional.of(parser);
  }

  public FormatDetector.Detection detect(List<String> sample) {
    return detector.detect(sample);
  }

  /**
   * @param keepUnparsed keep lines no parser understands as raw text instead of dropping them
   */
  public ParserChain chain(LogParser primary, boolean keepUnparsed) {
    List<LogParser> fallbacks =
        builtIns.stream().filter(p -> !p.name().equals(primary.name())).toList();
    return new ParserChain(primary, fallbacks, keepUnparsed ? raw : null);
  }
}
