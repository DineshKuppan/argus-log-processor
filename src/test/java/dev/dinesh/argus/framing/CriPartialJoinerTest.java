package dev.dinesh.argus.framing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.dinesh.argus.model.LogRecord;
import dev.dinesh.argus.parser.CriLogParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CriPartialJoinerTest {

  private static final String TS = "2025-08-21T12:00:00.000000000Z";

  private final List<CriPartialJoiner.Line> out = new ArrayList<>();
  private long offset;

  private void offer(CriPartialJoiner joiner, String... lines) {
    for (String line : lines) {
      joiner.offer(line, offset, out::add);
      offset += line.length() + 1;
    }
  }

  private static String cri(String stream, char tag, String body) {
    return TS + " " + stream + " " + tag + " " + body;
  }

  @Test
  void joinsFragmentsIntoOneLineThatParsesAsJson() {
    CriPartialJoiner joiner = new CriPartialJoiner(1024);
    String p1 = cri("stdout", 'P', "{\"level\":\"ERROR\",");
    offer(joiner, p1, cri("stdout", 'P', "\"message\":\"too"), cri("stdout", 'F', " long\"}"));

    assertEquals(1, out.size());
    CriPartialJoiner.Line line = out.get(0);
    assertEquals(0, line.offset());
    assertEquals(3, line.fragments());
    assertEquals(cri("stdout", 'F', "{\"level\":\"ERROR\",\"message\":\"too long\"}"), line.text());
    LogRecord record = new CriLogParser().parse(line.text());
    assertEquals("ERROR", record.getLevel());
    assertEquals("too long", record.getMessage());
    assertEquals(-1, joiner.pendingOffset());
  }

  @Test
  void unsplitLinesPassThroughUnchanged() {
    CriPartialJoiner joiner = new CriPartialJoiner(1024);
    String f = cri("stderr", 'F', "fine");
    offer(joiner, f, "not a cri line");

    assertEquals(
        List.of(f, "not a cri line"), out.stream().map(CriPartialJoiner.Line::text).toList());
    assertEquals(1, out.get(1).fragments());
    assertEquals(f.length() + 1, out.get(1).offset());
  }

  @Test
  void theOtherStreamEmitsPendingFragmentsAsTheyAreInFileOrder() {
    CriPartialJoiner joiner = new CriPartialJoiner(1024);
    offer(
        joiner,
        cri("stdout", 'P', "out-1 "),
        cri("stderr", 'F', "err"),
        cri("stdout", 'F', "out-2"));

    assertEquals(
        List.of(
            cri("stdout", 'P', "out-1 "), cri("stderr", 'F', "err"), cri("stdout", 'F', "out-2")),
        out.stream().map(CriPartialJoiner.Line::text).toList());
    long[] offsets = out.stream().mapToLong(CriPartialJoiner.Line::offset).toArray();
    assertEquals(offsets[0] < offsets[1] && offsets[1] < offsets[2], true);
  }

  @Test
  void anUnclosedPartialIsPendingUntilFlushed() {
    CriPartialJoiner joiner = new CriPartialJoiner(1024);
    offer(joiner, cri("stdout", 'F', "a"), cri("stdout", 'P', "b"), cri("stdout", 'P', "c"));

    assertEquals(1, out.size());
    assertEquals(out.get(0).text().length() + 1, joiner.pendingOffset());
    joiner.flush(out::add);
    assertEquals(cri("stdout", 'P', "bc"), out.get(1).text());
    assertEquals(2, out.get(1).fragments());
    assertEquals(-1, joiner.pendingOffset());
  }

  @Test
  void fragmentsPastTheByteLimitAreDroppedButTheLineStillCompletes() {
    CriPartialJoiner joiner = new CriPartialJoiner(6);
    offer(
        joiner,
        cri("stdout", 'P', "abc"),
        cri("stdout", 'P', "def"),
        cri("stdout", 'P', "g"),
        cri("stdout", 'F', "h"));

    assertEquals(1, out.size());
    assertEquals(cri("stdout", 'F', "abcdef"), out.get(0).text());
    assertEquals(4, out.get(0).fragments());
    assertEquals(2, out.get(0).droppedFragments());
  }

  @Test
  void anEmptyFinalFragmentIsKeptAsCriSyntax() {
    CriPartialJoiner joiner = new CriPartialJoiner(1024);
    offer(joiner, cri("stdout", 'P', "x"), TS + " stdout F");

    assertEquals(cri("stdout", 'F', "x"), out.get(0).text());
  }
}
