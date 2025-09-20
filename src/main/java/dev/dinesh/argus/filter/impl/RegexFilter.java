package dev.dinesh.argus.filter.impl;

import dev.dinesh.argus.filter.LogFilter;
import dev.dinesh.argus.model.LogRecord;
import java.util.regex.Pattern;

public class RegexFilter implements LogFilter {

  private final Pattern pattern;

  public RegexFilter(String regex) {
    this.pattern = Pattern.compile(regex);
  }

  @Override
  public boolean matches(LogRecord record) {
    if (pattern == null) {
      return true;
    }
    String msg = record.getMessage();
    return msg != null && pattern.matcher(msg).find();
  }
}
