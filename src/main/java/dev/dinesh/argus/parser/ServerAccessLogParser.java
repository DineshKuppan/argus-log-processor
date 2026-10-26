package dev.dinesh.argus.parser;

import dev.dinesh.argus.model.LogParser;
import dev.dinesh.argus.model.LogRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/** Parser for server access logs (e.g., Nginx/Apache). */
@Slf4j
public class ServerAccessLogParser implements LogParser {

  private static final String LOG_PATTERN =
      // IP - - [date] "request" status bytes "referrer" "user-agent" fruit
      // 1  2 3   4        5         6     7      8          9          10
      "^(\\S+) (\\S+) (\\S+) \\[([^\\]]+)] \"([^\"]*)\" (\\d{3}) (\\d+|-) \"([^\"]*)\" \"([^\"]*)\" (\\S+)$";
  private static final Pattern P =
      Pattern.compile(
          "^(\\S+) (\\S+) (\\S+) \\[([^\\]]+)] \"([^\"]*)\" (\\d{3}) (\\d+|-) \"([^\"]*)\" \"([^\"]*)\"$");

  private static final DateTimeFormatter DTF =
      DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");

  @Override
  public boolean canParse(String s) {
    return P.matcher(s).matches();
  }

  @Override
  public LogRecord parseLine(String line) throws Exception {
    return parseLine(line, LOG_PATTERN);
  }

  @Override
  public LogRecord parseLine(String line, String pattern) throws Exception {
    Pattern matchPattern = Pattern.compile(pattern);
    Matcher m = matchPattern.matcher(line);
    if (!m.matches()) {
      throw new IllegalArgumentException("Bad access line");
    }
    String ip = m.group(1);
    Instant ts = OffsetDateTime.parse(m.group(4), DTF).toInstant();
    String req = m.group(5);
    int status = Integer.parseInt(m.group(6));
    String bytes = m.group(7);
    String ref = m.group(8);
    String ua = m.group(9);
    String customName = "";
    if ((m.groupCount() >= 10) && (m.group(10) != null)) {
      customName = m.group(10);
      log.info("Custom Log Parser Name - {}", customName);
    }

    return new LogRecord(
        ts,
        null,
        req,
        Map.of(
            "ip",
            ip,
            "status",
            status,
            "bytes",
            bytes,
            "ref",
            ref,
            "ua",
            ua,
            "category",
            customName));
  }

  @Override
  public String name() {
    return "nginx_access";
  }
}
