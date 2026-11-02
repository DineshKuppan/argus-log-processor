package dev.dinesh.argus.framing;

import dev.dinesh.argus.parser.ParserChain;
import dev.dinesh.argus.parser.ParserRegistry;
import java.util.ArrayList;
import java.util.List;

/** Runs lines through a parser chain and a framer, as the parse stage will. */
final class FramerFixture {

  static final RecordFramer.Limits LIMITS = new RecordFramer.Limits(500, 1024 * 1024);

  private static final ParserRegistry REGISTRY = new ParserRegistry();

  private FramerFixture() {}

  static ParserChain chain(String format, boolean keepUnparsed) {
    return REGISTRY.chain(REGISTRY.resolve(format, null).orElseThrow(), keepUnparsed);
  }

  /** Lines are given offsets as a file would: each line plus its {@code \n}. */
  static List<FramedRecord> frame(RecordFramer framer, ParserChain chain, String... lines) {
    List<FramedRecord> records = new ArrayList<>();
    long offset = 0;
    for (String line : lines) {
      FramedRecord done = framer.offer(line, offset, chain.parse(line));
      if (done != null) {
        records.add(done);
      }
      offset += line.length() + 1;
    }
    FramedRecord last = framer.flush();
    if (last != null) {
      records.add(last);
    }
    return records;
  }

  static List<String> bodies(List<FramedRecord> records) {
    return records.stream().map(r -> r.parsed().record().getMessage()).toList();
  }
}
