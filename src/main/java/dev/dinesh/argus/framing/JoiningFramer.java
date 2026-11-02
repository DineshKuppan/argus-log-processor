package dev.dinesh.argus.framing;

import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.CriLogParser;
import dev.dinesh.argus.parser.ParserChain;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds the open record and appends continuation lines to it; subclasses decide what continues a
 * record. The body is built only once a second line arrives, so single-line records allocate
 * nothing extra.
 *
 * <p>A record reaching its limits is cut: the line that would pass them starts a new record ({@link
 * #SPLIT}), so no line is lost and no record grows without bound, whatever the rules decide.
 *
 * <p>A container (CRI) line is judged and joined by its body, not the runtime's {@code <time>
 * <stream> <tag>} prefix, and only within one stream: the parser accepts every CRI line, so its
 * acceptance means a head only when the body itself was structured (JSON).
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
  private String headStream;
  private long headOffset = -1;
  private StringBuilder body;
  private int lines;
  private int bytes;

  /** The open record reached its limits: the line that would pass them starts the next one. */
  private boolean cut;

  private String exceptionType;
  private boolean lastJoined;

  JoiningFramer(Limits limits) {
    this.limits = limits;
  }

  /**
   * Whether a line continues the open record (or, with none open, would have).
   *
   * @param text the line, or a container line's body
   * @param structured the source's structured primary format parsed it (a head, for rules that
   *     respect it)
   */
  abstract boolean continues(String text, boolean structured);

  /** Called for each line added to a record, head included, after {@link #reset()} on a head. */
  void accepted(String line) {}

  /** Called when a record opens. */
  void reset() {}

  @Override
  public final FramedRecord offer(String line, long offset, ParserChain.Parsed parsed) {
    String text = line;
    String stream = null;
    boolean structured = parsed != null && parsed.outcome() == ParserChain.Outcome.PRIMARY;
    if (parsed != null && CriLogParser.NAME.equals(parsed.format())) {
      String body = CriLogParser.body(line);
      if (body != null) {
        text = body;
        // A plain body is the record's message; a JSON body was parsed into fields.
        structured = !body.equals(parsed.record().getMessage());
        stream = Objects.toString(parsed.record().getMeta().get("log.iostream"), null);
      }
    }
    boolean continuation =
        continues(text, structured) && (head == null || Objects.equals(headStream, stream));
    String joined = continuation && head != null ? withoutCr(text) : null;
    lastJoined = joined != null && fits(joined);
    if (lastJoined) {
      append(joined);
      return null;
    }
    boolean cut = joined != null;
    FramedRecord done = flush();
    if (parsed != null) {
      open(text, offset, parsed, stream, cut ? SPLIT : continuation ? ORPHAN : null);
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
    if (cut) {
      meta.put(TRUNCATED_ATTRIBUTE, true);
    }
    FramedRecord done = new FramedRecord(head, headOffset, lines, cut);
    head = null;
    headLine = null;
    headStream = null;
    headOffset = -1;
    body = null;
    cut = false;
    return done;
  }

  /**
   * @param framing {@link #ORPHAN}, {@link #SPLIT} or null
   */
  private void open(
      String text, long offset, ParserChain.Parsed parsed, String stream, String framing) {
    head = parsed;
    headLine = text;
    headStream = stream;
    headOffset = offset;
    lines = 1;
    exceptionType = null;
    String message = parsed.record().getMessage();
    bytes = utf8Length(message != null ? message : text);
    if (framing != null) {
      parsed.record().getMeta().put(FRAMING_ATTRIBUTE, framing);
    }
    reset();
    accepted(text);
  }

  /** Whether {@code text} can join the open record within its limits; else the record is cut. */
  private boolean fits(String text) {
    if (lines < limits.maxLines() && bytes + utf8Length(text) + 1 <= limits.maxBytes()) {
      return true;
    }
    cut = true;
    return false;
  }

  private static String withoutCr(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  private void append(String text) {
    int length = utf8Length(text) + 1;
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
