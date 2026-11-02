package dev.dinesh.argus.framing;

import dev.dinesh.argus.parser.ParserChain;
import java.util.regex.Pattern;

/**
 * {@code framing=start:<regex>} ({@code P7_PLAN.md} D2): a line in which the pattern is found
 * starts a record; every other line continues the open one. For layouts the built-in rules don't
 * cover. The pattern is searched in a line's first {@value JoiningFramer#WINDOW} characters only.
 */
public final class StartPatternFramer extends JoiningFramer {

  private final Pattern start;

  public StartPatternFramer(Pattern start, Limits limits) {
    super(limits);
    this.start = start;
  }

  @Override
  boolean continues(String line, ParserChain.Parsed parsed) {
    return !start.matcher(window(line)).find();
  }
}
