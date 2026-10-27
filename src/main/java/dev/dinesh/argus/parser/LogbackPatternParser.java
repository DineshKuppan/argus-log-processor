package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogRecord;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses lines written by a Logback/Log4j-style layout such as {@code %d{ISO8601} [%t] %-5p %c -
 * %m%n}. Logback can only format a layout, not parse one, so the layout is compiled to an anchored
 * regex with one group per conversion word.
 *
 * <p>Supported: {@code %d{pattern[, zone]}} (default ISO8601, UTC), {@code %p/%le/%level}, {@code
 * %t/%thread}, {@code %c/%lo/%logger}, {@code %m/%msg/%message}, {@code %X{key}}, {@code ${NAME}},
 * {@code %n}, format modifiers such as {@code %-5p}, and any other word (kept as an attribute).
 * Composite words such as {@code %replace(...)} are rejected.
 */
public class LogbackPatternParser implements LogParser {

  public static final String NAME = "logback";

  private static final String ISO8601 = "yyyy-MM-dd HH:mm:ss,SSS";

  private enum Kind {
    TIMESTAMP,
    LEVEL,
    THREAD,
    LOGGER,
    MESSAGE,
    ATTRIBUTE
  }

  private record Field(Kind kind, String attribute) {}

  private final String layout;
  private final Pattern regex;
  private final List<Field> fields = new ArrayList<>();
  private DateTimeFormatter dateFormat;
  private ZoneId zone = ZoneOffset.UTC;

  /**
   * @throws IllegalArgumentException if the layout uses an unsupported construct
   */
  public LogbackPatternParser(String layout) {
    if (layout == null || layout.isBlank()) {
      throw new IllegalArgumentException("Logback pattern is empty");
    }
    this.layout = layout;
    this.regex = Pattern.compile("^" + compile(layout) + "$");
  }

  public String layout() {
    return layout;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean canParse(String line) {
    return !line.isEmpty();
  }

  @Override
  public LogRecord parse(String line) {
    Matcher m = regex.matcher(line);
    if (!m.matches()) {
      return null;
    }
    LogRecord record = new LogRecord(null, null, null, new HashMap<>());
    Map<String, Object> meta = record.getMeta();
    for (int i = 0; i < fields.size(); i++) {
      String value = m.group(i + 1).strip();
      Field field = fields.get(i);
      switch (field.kind()) {
        case TIMESTAMP -> {
          Instant ts = parseTimestamp(value);
          if (ts == null) {
            return null;
          }
          record.setTimestamp(ts);
        }
        case LEVEL -> record.setLevel(value);
        case MESSAGE -> record.setMessage(value);
        case THREAD -> meta.put("thread.name", value);
        case LOGGER -> meta.put("logger", value);
        case ATTRIBUTE -> meta.put(field.attribute(), value);
      }
    }
    return record;
  }

  private Instant parseTimestamp(String text) {
    try {
      TemporalAccessor parsed =
          dateFormat.parseBest(
              text, OffsetDateTime::from, ZonedDateTime::from, LocalDateTime::from);
      if (parsed instanceof OffsetDateTime odt) {
        return odt.toInstant();
      }
      if (parsed instanceof ZonedDateTime zdt) {
        return zdt.toInstant();
      }
      return ((LocalDateTime) parsed).atZone(zone).toInstant();
    } catch (DateTimeException e) {
      return null;
    }
  }

  private String compile(String layout) {
    StringBuilder out = new StringBuilder();
    StringBuilder literal = new StringBuilder();
    int i = 0;
    while (i < layout.length()) {
      char c = layout.charAt(i);
      if (c == '%' && i + 1 < layout.length() && layout.charAt(i + 1) == '%') {
        literal.append('%');
        i += 2;
      } else if (c == '%') {
        appendLiteral(out, literal);
        i = conversion(layout, i + 1, out);
      } else if (c == '$' && i + 1 < layout.length() && layout.charAt(i + 1) == '{') {
        appendLiteral(out, literal);
        int end = layout.indexOf('}', i);
        if (end < 0) {
          throw new IllegalArgumentException("Unclosed ${ in Logback pattern: " + layout);
        }
        String name = layout.substring(i + 2, end).split(":-", 2)[0];
        addField(out, new Field(Kind.ATTRIBUTE, name.toLowerCase(Locale.ROOT)), "(\\S*)", false);
        i = end + 1;
      } else {
        literal.append(c);
        i++;
      }
    }
    appendLiteral(out, literal);
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("Logback pattern has no conversion words: " + layout);
    }
    return out.toString();
  }

  /**
   * Parses one {@code %[modifier]word[{options}]} starting after the '%'; returns the next index.
   */
  private int conversion(String layout, int i, StringBuilder out) {
    int start = i;
    while (i < layout.length() && "-.0123456789".indexOf(layout.charAt(i)) >= 0) {
      i++;
    }
    boolean padded = hasMinWidth(layout.substring(start, i));
    int wordStart = i;
    while (i < layout.length() && Character.isLetter(layout.charAt(i))) {
      i++;
    }
    String word = layout.substring(wordStart, i);
    if (word.isEmpty() || (i < layout.length() && layout.charAt(i) == '(')) {
      throw new IllegalArgumentException(
          "Unsupported construct at position " + (start - 1) + " of Logback pattern: " + layout);
    }
    String options = null;
    if (i < layout.length() && layout.charAt(i) == '{') {
      int end = layout.indexOf('}', i);
      if (end < 0) {
        throw new IllegalArgumentException("Unclosed { in Logback pattern: " + layout);
      }
      options = layout.substring(i + 1, end);
      i = end + 1;
    }
    switch (word) {
      case "d", "date" -> {
        configureDate(options);
        addField(out, new Field(Kind.TIMESTAMP, null), "(.+?)", padded);
      }
      case "p", "le", "level" -> addField(out, new Field(Kind.LEVEL, null), "(\\S+)", padded);
      case "t", "thread" -> addField(out, new Field(Kind.THREAD, null), "(.+?)", padded);
      case "c", "lo", "logger" -> addField(out, new Field(Kind.LOGGER, null), "(\\S+)", padded);
      case "m", "msg", "message" -> addField(out, new Field(Kind.MESSAGE, null), "(.*)", padded);
      case "X", "mdc" -> {
        if (options == null || options.isBlank()) {
          throw new IllegalArgumentException("%X needs a key, e.g. %X{traceId}: " + layout);
        }
        addField(out, new Field(Kind.ATTRIBUTE, options.split(":-", 2)[0]), "(.*?)", padded);
      }
      case "n" -> {
        // line separator: lines arrive without it
      }
      case "ex",
          "exception",
          "throwable",
          "xEx",
          "xException",
          "xThrowable",
          "rEx",
          "wEx",
          "nopex",
          "nopexception" -> {
        // stack traces span lines; multiline framing is not supported yet
      }
      default -> addField(out, new Field(Kind.ATTRIBUTE, word), "(.*?)", padded);
    }
    return i;
  }

  private static boolean hasMinWidth(String modifier) {
    String min = modifier.split("\\.", 2)[0].replace("-", "");
    return !min.isEmpty() && Integer.parseInt(min) > 0;
  }

  private void configureDate(String options) {
    String pattern = ISO8601;
    if (options != null && !options.isBlank()) {
      String opts = options.strip();
      if (opts.startsWith("\"")) {
        int close = opts.indexOf('"', 1);
        pattern = opts.substring(1, close);
        String rest = opts.substring(close + 1).strip();
        if (rest.startsWith(",")) {
          zone = ZoneId.of(rest.substring(1).strip());
        }
      } else {
        int comma = opts.lastIndexOf(',');
        if (comma > 0 && isZone(opts.substring(comma + 1).strip())) {
          zone = ZoneId.of(opts.substring(comma + 1).strip());
          opts = opts.substring(0, comma).strip();
        }
        pattern = opts.equals("ISO8601") ? ISO8601 : opts;
      }
    }
    try {
      dateFormat = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid %d date pattern '" + pattern + "'", e);
    }
  }

  private static boolean isZone(String text) {
    try {
      ZoneId.of(text);
      return true;
    } catch (DateTimeException e) {
      return false;
    }
  }

  private void addField(StringBuilder out, Field field, String group, boolean padded) {
    fields.add(field);
    out.append(padded ? "\\s*" + group + "\\s*" : group);
  }

  /** Literal text, with any run of spaces matching one or more spaces (padding is variable). */
  private static void appendLiteral(StringBuilder out, StringBuilder literal) {
    if (literal.isEmpty()) {
      return;
    }
    for (String part : literal.toString().split("((?<= )(?! ))|((?<! )(?= ))")) {
      out.append(part.isBlank() ? "\\s+" : Pattern.quote(part));
    }
    literal.setLength(0);
  }
}
