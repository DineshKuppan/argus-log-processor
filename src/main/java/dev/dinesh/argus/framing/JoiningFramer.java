package dev.dinesh.argus.framing;

import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.ParserChain;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds the open record and appends continuation lines to it; subclasses decide what continues a
 * record. The body is built only once a second line arrives, so single-line records allocate
 * nothing extra.
 */
abstract class JoiningFramer implements RecordFramer {

  /** Rules look at no more than a line's first characters, so a 1MB line costs no more. */
  static final int WINDOW = 256;

  /** {@code java.lang.IllegalStateException: boom}, optionally after {@code Caused by: }. */
  private static final Pattern JAVA_EXCEPTION =
      Pattern.compile(
          "(?:Caused by: )?((?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:Exception|Error|Throwable))(?::|$)");

  /** {@code ValueError: bad value}. */
  private static final Pattern PYTHON_EXCEPTION =
      Pattern.compile("([A-Za-z_][\\w.]*(?:Error|Exception|Warning|Exit|Interrupt))(?::|$)");

  private final Limits limits;

  private ParserChain.Parsed head;
  private String headLine;
  private long headOffset = -1;
  private StringBuilder body;
  private int lines;
  private int bytes;
  private int dropped;
  private String exceptionType;
  private boolean lastJoined;

  JoiningFramer(Limits limits) {
    this.limits = limits;
  }

  /** Whether {@code line} continues the open record (or, with none open, would have). */
  abstract boolean continues(String line, ParserChain.Parsed parsed);

  /** Called for each line added to a record, head included, after {@link #reset()} on a head. */
  void accepted(String line) {}

  /** Called when a record opens. */
  void reset() {}

  @Override
  public final FramedRecord offer(String line, long offset, ParserChain.Parsed parsed) {
    boolean continuation = continues(line, parsed);
    lastJoined = continuation && head != null;
    if (lastJoined) {
      append(line);
      return null;
    }
    FramedRecord done = flush();
    if (parsed != null) {
      open(line, offset, parsed, continuation);
    }
    return done;
  }

  @Override
  public final long openOffset() {
    return head == null ? -1 : headOffset;
  }

  @Override
  public final int openLines() {
    return head == null ? 0 : lines;
  }

  @Override
  public final boolean lastJoined() {
    return lastJoined;
  }

  @Override
  public final FramedRecord flush() {
    if (head == null) {
      return null;
    }
    LogRecord record = head.record();
    Map<String, Object> meta = record.getMeta();
    if (body != null) {
      record.setMessage(body.toString());
      meta.put(LINES_ATTRIBUTE, lines);
      if (exceptionType != null) {
        meta.putIfAbsent(EXCEPTION_TYPE_ATTRIBUTE, exceptionType);
      }
    }
    if (dropped > 0) {
      meta.put(TRUNCATED_ATTRIBUTE, true);
      meta.put(DROPPED_ATTRIBUTE, dropped);
    }
    FramedRecord done = new FramedRecord(head, headOffset, lines, dropped);
    head = null;
    headLine = null;
    headOffset = -1;
    body = null;
    return done;
  }

  private void open(String line, long offset, ParserChain.Parsed parsed, boolean orphan) {
    head = parsed;
    headLine = line;
    headOffset = offset;
    lines = 1;
    dropped = 0;
    exceptionType = null;
    String message = parsed.record().getMessage();
    bytes = utf8Length(message != null ? message : line);
    if (orphan) {
      parsed.record().getMeta().put(FRAMING_ATTRIBUTE, ORPHAN);
    }
    reset();
    accepted(line);
  }

  private void append(String line) {
    String text = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    int length = utf8Length(text) + 1;
    if (lines >= limits.maxLines() || bytes + length > limits.maxBytes()) {
      dropped++;
      return;
    }
    if (body == null) {
      String message = head.record().getMessage();
      body = new StringBuilder(message != null ? message : headLine);
    }
    body.append('\n').append(text);
    lines++;
    bytes += length;
    if (exceptionType == null) {
      exceptionType = exceptionType(text);
    }
    accepted(text);
  }

  /** The class name on a Java or Python exception line, or null. */
  static String exceptionType(String line) {
    String window = window(line);
    Matcher java = JAVA_EXCEPTION.matcher(window);
    if (java.lookingAt()) {
      return java.group(1);
    }
    Matcher python = PYTHON_EXCEPTION.matcher(window);
    return python.lookingAt() ? python.group(1) : null;
  }

  static boolean isPythonException(String line) {
    return PYTHON_EXCEPTION.matcher(window(line)).lookingAt();
  }

  static boolean isJavaException(String line) {
    return JAVA_EXCEPTION.matcher(window(line)).lookingAt();
  }

  static String window(String line) {
    return line.length() <= WINDOW ? line : line.substring(0, WINDOW);
  }

  static int utf8Length(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        n++;
      } else if (c < 0x800) {
        n += 2;
      } else if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
        n += 4;
        i++;
      } else {
        n += 3;
      }
    }
    return n;
  }
}
