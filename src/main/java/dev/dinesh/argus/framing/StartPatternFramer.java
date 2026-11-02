package dev.dinesh.argus.framing;

import java.util.regex.Pattern;

/**
 * {@code framing=start:<regex>} ({@code P7_PLAN.md} D2): a line in which the pattern is found
 * starts a record; every other line continues the open one. For layouts the built-in rules don't
 * cover. The pattern is searched in a line's first {@value JoiningFramer#WINDOW} characters only.
 *
 * <p>The pattern comes from the request, and Java's regex engine backtracks: {@code (a|aa)+$} takes
 * exponential time on a line of {@code a}s, and runs on threads other tenants share. So each search
 * may read at most {@value #MAX_STEPS} characters; a search that needs more is abandoned and the
 * line starts a record (as with {@code none}), counted in {@link #abandoned()}.
 */
public final class StartPatternFramer extends JoiningFramer {

  /** Character reads per search: about 1,000 per window character, microseconds at most. */
  static final int MAX_STEPS = 256_000;

  private final Pattern start;
  private long abandoned;

  public StartPatternFramer(Pattern start, Limits limits) {
    super(limits);
    this.start = start;
  }

  @Override
  boolean continues(String line, boolean structured) {
    try {
      return !start.matcher(new Budgeted(window(line))).find();
    } catch (OverBudget e) {
      abandoned++;
      return false;
    }
  }

  /** Searches abandoned over {@link #MAX_STEPS}. */
  public long abandoned() {
    return abandoned;
  }

  /** Counts the regex engine's reads and stops it past the budget. */
  private static final class Budgeted implements CharSequence {
    private final String text;
    private int steps;

    Budgeted(String text) {
      this.text = text;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      if (++steps > MAX_STEPS) {
        throw OverBudget.INSTANCE;
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    @Override
    public String toString() {
      return text;
    }
  }

  /** Thrown often under attack, so it has no stack trace. */
  private static final class OverBudget extends RuntimeException {
    static final OverBudget INSTANCE = new OverBudget();

    private OverBudget() {
      super(null, null, false, false);
    }
  }
}
