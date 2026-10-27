package dev.dinesh.argus.parser;

import java.util.List;

/**
 * Picks a source's primary format from a sample of its lines: the candidate that parses the most
 * sample lines wins, ties going to the earlier (higher-priority) candidate. If none parses any
 * line, the source is raw text.
 */
public final class FormatDetector {

  /** Lines sampled from the start of a source. */
  public static final int SAMPLE_LINES = 100;

  /** Upper bound on bytes read for the sample. */
  public static final int SAMPLE_BYTES = 64 * 1024;

  /**
   * @param parsedLines sample lines the chosen parser understood
   */
  public record Detection(LogParser parser, int sampledLines, int parsedLines) {}

  private final List<LogParser> candidates;
  private final LogParser fallback;

  FormatDetector(List<LogParser> candidates, LogParser fallback) {
    this.candidates = List.copyOf(candidates);
    this.fallback = fallback;
  }

  public Detection detect(List<String> sample) {
    LogParser best = null;
    int bestCount = 0;
    for (LogParser candidate : candidates) {
      int count = 0;
      for (String line : sample) {
        if (candidate.canParse(line) && parsesQuietly(candidate, line)) {
          count++;
        }
      }
      if (count > bestCount) {
        best = candidate;
        bestCount = count;
      }
    }
    return best == null
        ? new Detection(fallback, sample.size(), sample.size())
        : new Detection(best, sample.size(), bestCount);
  }

  private static boolean parsesQuietly(LogParser parser, String line) {
    try {
      return parser.parse(line) != null;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
