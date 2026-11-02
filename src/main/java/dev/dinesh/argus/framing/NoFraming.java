package dev.dinesh.argus.framing;

import dev.dinesh.argus.parser.ParserChain;

/** Every line is its own record: {@code framing=none}, the behaviour before P7. Stateless. */
public final class NoFraming implements RecordFramer {

  public static final NoFraming INSTANCE = new NoFraming();

  private NoFraming() {}

  @Override
  public FramedRecord offer(String line, long offset, ParserChain.Parsed parsed) {
    return parsed == null ? null : new FramedRecord(parsed, offset, 1, 0);
  }

  @Override
  public long openOffset() {
    return -1;
  }

  @Override
  public FramedRecord flush() {
    return null;
  }
}
