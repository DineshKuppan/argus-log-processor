package dev.dinesh.argus.framing;

import dev.dinesh.argus.parser.ParserChain;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code framing=auto} ({@code P7_PLAN.md} D2): joins Java, Python and Go stack traces to the line
 * that logged them. A line continues the open record when the source's primary parser does not
 * parse it and it has the shape of a trace line. A line the primary parser accepts always starts a
 * record, so a head line is never absorbed; for a source without a structured format ({@code raw})
 * the shapes alone decide.
 */
public final class StackTraceFramer extends JoiningFramer {

  private static final List<String> PREFIXES =
      List.of(
          "Caused by: ",
          "Suppressed: ",
          "Traceback (most recent call last):",
          "During handling of the above exception",
          "The above exception was the direct cause");

  private static final Pattern GOROUTINE = Pattern.compile("goroutine \\d+ \\[");

  /** Python's exception line ends a traceback: it continues only after a traceback's frames. */
  private boolean inTraceback;

  private boolean previousIndented;

  /** Go's function lines ({@code main.main()}) aren't indented: they continue after a header. */
  private boolean inGoroutine;

  public StackTraceFramer(Limits limits) {
    super(limits);
  }

  @Override
  boolean continues(String line, ParserChain.Parsed parsed) {
    if (parsed != null && parsed.outcome() == ParserChain.Outcome.PRIMARY) {
      return false;
    }
    if (isIndented(line) || isJavaException(line)) {
      return true;
    }
    for (String prefix : PREFIXES) {
      if (line.startsWith(prefix)) {
        return true;
      }
    }
    String window = window(line);
    if (GOROUTINE.matcher(window).lookingAt()) {
      return true;
    }
    if (inGoroutine && (line.startsWith("created by ") || isGoFunction(window))) {
      return true;
    }
    return inTraceback && previousIndented && isPythonException(line);
  }

  @Override
  void reset() {
    inTraceback = false;
    previousIndented = false;
    inGoroutine = false;
  }

  @Override
  void accepted(String line) {
    inTraceback |= line.startsWith("Traceback (most recent call last):");
    inGoroutine |= GOROUTINE.matcher(window(line)).lookingAt();
    previousIndented = isIndented(line);
  }

  private static boolean isIndented(String line) {
    return !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
  }

  /** {@code main.main()}, {@code net/http.(*conn).serve(0xc000146000, {0x1, 0x2})}. */
  private static boolean isGoFunction(String window) {
    int paren = window.indexOf('(');
    return paren > 0
        && window.endsWith(")")
        && window.lastIndexOf(' ', paren) < 0
        && window.indexOf('.') > 0;
  }
}
