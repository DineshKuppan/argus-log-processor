package dev.dinesh.argus.framing;

import java.util.function.Consumer;

/**
 * Restores container lines the CRI runtime split ({@code P7_PLAN.md} D6): a line over the runtime's
 * limit (16KB) is written as {@code P} (partial) fragments and a final {@code F} fragment, {@code
 * <RFC3339Nano> <stdout|stderr> <P|F> <body>}. Consecutive fragments of one stream are joined into
 * one {@code F} line, which is then parsed (and framed) once, at the first fragment's offset.
 *
 * <p>Only consecutive fragments join: a line of the other stream, or one that isn't CRI, first
 * emits the pending fragments as they are (still tagged {@code P}). Lines therefore come out in
 * file order, so batch and segment ranges still tile the file. One instance per run; not
 * thread-safe.
 */
public final class CriPartialJoiner {

  /** Number of fragments joined into a line; absent on lines that weren't split. */
  public static final String FRAGMENTS_ATTRIBUTE = "argus.cri.fragments";

  /**
   * A line to parse.
   *
   * @param offset the first fragment's offset
   * @param fragments fragments joined into it (1 for a line that wasn't split)
   * @param droppedFragments fragments past {@code maxBytes}, not in {@code text}
   */
  public record Line(String text, long offset, int fragments, int droppedFragments) {}

  private final int maxBytes;

  private String prefix;
  private String stream;
  private StringBuilder body;
  private long offset = -1;
  private int fragments;
  private int dropped;
  private int bytes;

  /**
   * @param maxBytes UTF-8 bytes of a joined body; later fragments are dropped
   */
  public CriPartialJoiner(int maxBytes) {
    this.maxBytes = maxBytes;
  }

  /** Offers the next line; {@code out} receives the lines it completes, in order (up to two). */
  public void offer(String line, long lineOffset, Consumer<Line> out) {
    Fragment f = Fragment.parse(line);
    if (f == null) {
      flush(out);
      out.accept(new Line(line, lineOffset, 1, 0));
      return;
    }
    if (offset >= 0 && !stream.equals(f.stream)) {
      flush(out);
    }
    if (offset < 0) {
      if (!f.partial) {
        out.accept(new Line(line, lineOffset, 1, 0));
        return;
      }
      prefix = line.substring(0, f.bodyStart);
      stream = f.stream;
      body = new StringBuilder(line.length() - f.bodyStart);
      offset = lineOffset;
      fragments = 0;
      dropped = 0;
      bytes = 0;
    }
    append(line.substring(f.bodyStart));
    if (!f.partial) {
      out.accept(complete('F'));
    }
  }

  /** The pending first fragment's offset, or -1. */
  public long pendingOffset() {
    return offset;
  }

  /** Emits the pending fragments as they are (the source has no more lines for them). */
  public void flush(Consumer<Line> out) {
    if (offset >= 0) {
      out.accept(complete('P'));
    }
  }

  private void append(String fragment) {
    fragments++;
    int length = JoiningFramer.utf8Length(fragment);
    if (bytes + length > maxBytes && fragments > 1) {
      dropped++;
      return;
    }
    body.append(fragment);
    bytes += length;
  }

  private Line complete(char tag) {
    // The prefix ends with "<tag> " (or "<tag>" for an empty body): rewrite the tag only.
    int tagAt = prefix.indexOf(stream) + stream.length() + 1;
    String text = prefix.substring(0, tagAt) + tag + prefix.substring(tagAt + 1) + body;
    Line line = new Line(text, offset, fragments, dropped);
    offset = -1;
    prefix = null;
    stream = null;
    body = null;
    return line;
  }

  /** One CRI line's fields, found without a regex: this runs on every line of a CRI source. */
  private record Fragment(String stream, boolean partial, int bodyStart) {

    static Fragment parse(String line) {
      int s1 = line.indexOf(' ');
      if (s1 <= 0) {
        return null;
      }
      int s2 = line.indexOf(' ', s1 + 1);
      if (s2 < 0 || s2 + 1 >= line.length()) {
        return null;
      }
      String stream = line.substring(s1 + 1, s2);
      if (!stream.equals("stdout") && !stream.equals("stderr")) {
        return null;
      }
      char tag = line.charAt(s2 + 1);
      if (tag != 'P' && tag != 'F') {
        return null;
      }
      int end = s2 + 2;
      if (end < line.length() && line.charAt(end) != ' ') {
        return null;
      }
      return new Fragment(stream, tag == 'P', Math.min(end + 1, line.length()));
    }
  }
}
