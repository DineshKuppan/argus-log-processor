package dev.dinesh.argus.framing;

import static dev.dinesh.argus.framing.FramerFixture.LIMITS;
import static dev.dinesh.argus.framing.FramerFixture.bodies;
import static dev.dinesh.argus.framing.FramerFixture.chain;
import static dev.dinesh.argus.framing.FramerFixture.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dinesh.argus.parser.ParserChain;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StackTraceFramerTest {

  private static final String HEAD_1 =
      "timestamp=2025-08-21T12:10:00Z level=ERROR msg=\"Request failed\"";
  private static final String HEAD_2 = "timestamp=2025-08-21T12:10:01Z level=INFO msg=\"Next\"";

  private final ParserChain kv = chain("kv", true);

  private List<FramedRecord> frameKv(String... lines) {
    return frame(new StackTraceFramer(LIMITS), kv, lines);
  }

  @Test
  void joinsAJavaTraceWithCausesSuppressedAndElidedFrames() {
    List<FramedRecord> records =
        frameKv(
            HEAD_1,
            "java.lang.IllegalStateException: boom",
            "\tat com.example.Orders.place(Orders.java:42)",
            "\tat com.example.Api.post(Api.java:17)",
            "\tSuppressed: java.io.IOException: close failed",
            "\t\tat com.example.Io.close(Io.java:9)",
            "Caused by: java.lang.IllegalArgumentException: bad id",
            "\tat com.example.Ids.parse(Ids.java:3)",
            "\t... 2 more",
            HEAD_2);

    assertEquals(2, records.size());
    FramedRecord trace = records.get(0);
    assertEquals(0, trace.offset());
    assertEquals(9, trace.lines());
    Map<String, Object> meta = trace.parsed().record().getMeta();
    assertEquals(9, meta.get(RecordFramer.LINES_ATTRIBUTE));
    assertEquals(
        "java.lang.IllegalStateException", meta.get(RecordFramer.EXCEPTION_TYPE_ATTRIBUTE));
    assertEquals(
        "Request failed\njava.lang.IllegalStateException: boom\n"
            + "\tat com.example.Orders.place(Orders.java:42)",
        trace.parsed().record().getMessage().lines().limit(3).reduce((a, b) -> a + "\n" + b).get());
    assertEquals("ERROR", trace.parsed().record().getLevel());
    assertEquals(ParserChain.Outcome.PRIMARY, trace.parsed().outcome());
    assertEquals("Next", records.get(1).parsed().record().getMessage());
  }

  @Test
  void singleLineRecordsAreUnchanged() {
    List<FramedRecord> records = frameKv(HEAD_1, HEAD_2);

    assertEquals(List.of("Request failed", "Next"), bodies(records));
    assertEquals(HEAD_1.length() + 1, records.get(1).offset());
    for (FramedRecord r : records) {
      assertEquals(1, r.lines());
      assertFalse(r.parsed().record().getMeta().containsKey(RecordFramer.LINES_ATTRIBUTE));
    }
  }

  @Test
  void aLineThePrimaryParserAcceptsIsNeverAbsorbedEvenWhenIndented() {
    List<FramedRecord> records = frameKv(HEAD_1, "  level=WARN msg=indented");

    assertEquals(List.of("Request failed", "indented"), bodies(records));
  }

  @Test
  void anUnparsedLineThatIsNotTraceShapedStartsItsOwnRecord() {
    List<FramedRecord> records = frameKv(HEAD_1, "plain text banner", "\tat x.Y.z(Y.java:1)");

    assertEquals(2, records.size());
    assertEquals("plain text banner\n\tat x.Y.z(Y.java:1)", bodies(records).get(1));
    assertNull(records.get(1).parsed().record().getMeta().get(RecordFramer.FRAMING_ATTRIBUTE));
  }

  @Test
  void joinsAPythonTracebackIncludingChainedExceptions() {
    List<FramedRecord> records =
        frameKv(
            HEAD_1,
            "Traceback (most recent call last):",
            "  File \"/app/main.py\", line 3, in <module>",
            "    run()",
            "ValueError: bad value",
            "During handling of the above exception, another exception occurred:",
            "Traceback (most recent call last):",
            "  File \"/app/main.py\", line 5, in <module>",
            "KeyError: 'id'",
            "ValueError: not after a frame");

    assertEquals(2, records.size());
    assertEquals(9, records.get(0).lines());
    assertEquals("ValueError", records.get(0).parsed().record().getMeta().get("exception.type"));
    assertEquals("ValueError: not after a frame", bodies(records).get(1));
  }

  @Test
  void joinsAGoPanic() {
    List<FramedRecord> records =
        frame(
            new StackTraceFramer(LIMITS),
            chain("raw", true),
            "panic: runtime error: index out of range [3] with length 3",
            "goroutine 1 [running]:",
            "main.main()",
            "\t/app/main.go:8 +0x1d",
            "net/http.(*conn).serve(0xc000146000, {0x7f, 0x8})",
            "\t/usr/local/go/src/net/http/server.go:3086 +0x5cb",
            "created by net/http.(*Server).Serve in goroutine 1",
            "exit status 2");

    assertEquals(2, records.size());
    assertEquals(7, records.get(0).lines());
    assertEquals("exit status 2", bodies(records).get(1));
  }

  @Test
  void goFunctionLinesContinueOnlyAfterAGoroutineHeader() {
    List<FramedRecord> records =
        frame(new StackTraceFramer(LIMITS), chain("raw", true), "start", "main.main()");

    assertEquals(2, records.size());
  }

  @Test
  void aLeadingContinuationIsAnOrphanRecordThatLaterFramesJoin() {
    List<FramedRecord> records = frameKv("\tat a.B.c(B.java:1)", "\tat a.B.d(B.java:2)", HEAD_1);

    assertEquals(2, records.size());
    assertEquals(2, records.get(0).lines());
    assertEquals(
        RecordFramer.ORPHAN,
        records.get(0).parsed().record().getMeta().get(RecordFramer.FRAMING_ATTRIBUTE));
  }

  @Test
  void continuationLinesJoinEvenWhenUnparsedLinesAreDropped() {
    List<FramedRecord> records =
        frame(
            new StackTraceFramer(LIMITS),
            chain("kv", false),
            HEAD_1,
            "\tat a.B.c(B.java:1)",
            "garbage");

    assertEquals(List.of("Request failed\n\tat a.B.c(B.java:1)"), bodies(records));
  }

  @Test
  void aRecordPastTheLineLimitIsCutAndTheRestFollowsAsSplitRecords() {
    StackTraceFramer framer = new StackTraceFramer(new RecordFramer.Limits(3, 1024));
    List<FramedRecord> records =
        frame(
            framer,
            kv,
            HEAD_1,
            "\tat a(A.java:1)",
            "\tat b(A.java:2)",
            "\tat c(A.java:3)",
            "\tat d(A.java:4)",
            HEAD_2);

    assertEquals(
        List.of(
            "Request failed\n\tat a(A.java:1)\n\tat b(A.java:2)",
            "\tat c(A.java:3)\n\tat d(A.java:4)",
            "Next"),
        bodies(records));
    FramedRecord trace = records.get(0);
    assertEquals(3, trace.lines());
    assertTrue(trace.truncated());
    assertEquals(true, trace.parsed().record().getMeta().get(RecordFramer.TRUNCATED_ATTRIBUTE));
    FramedRecord rest = records.get(1);
    assertFalse(rest.truncated());
    assertEquals(
        RecordFramer.SPLIT, rest.parsed().record().getMeta().get(RecordFramer.FRAMING_ATTRIBUTE));
    assertEquals(HEAD_1.length() + 1 + 2 * ("\tat a(A.java:1)".length() + 1), rest.offset());
  }

  @Test
  void aLinePastTheByteLimitStartsTheNextRecordSoNothingIsSkipped() {
    // "Request failed" is 14 bytes; "\tat é" adds 6 (é is 2) plus the newline.
    StackTraceFramer framer = new StackTraceFramer(new RecordFramer.Limits(500, 14 + 7));
    List<FramedRecord> records = frame(framer, kv, HEAD_1, "\tat é", "\tat b");

    assertEquals(List.of("Request failed\n\tat é", "\tat b"), bodies(records));
    assertTrue(records.get(0).truncated());
  }

  @Test
  void aCarriageReturnIsStrippedFromContinuationLines() {
    assertEquals(
        List.of("Request failed\n\tat a(A.java:1)"), bodies(frameKv(HEAD_1, "\tat a(A.java:1)\r")));
  }

  @Test
  void openOffsetIsTheHeadLinesOffset() {
    StackTraceFramer framer = new StackTraceFramer(LIMITS);
    assertEquals(-1, framer.openOffset());

    assertNull(framer.offer(HEAD_1, 100, kv.parse(HEAD_1)));
    assertNull(framer.offer("\tat a(A.java:1)", 170, kv.parse("\tat a(A.java:1)")));
    assertEquals(100, framer.openOffset());

    FramedRecord done = framer.offer(HEAD_2, 186, kv.parse(HEAD_2));
    assertEquals(100, done.offset());
    assertEquals(186, framer.openOffset());
    assertEquals(186, framer.flush().offset());
    assertEquals(-1, framer.openOffset());
    assertNull(framer.flush());
  }

  @Test
  void rulesLookOnlyAtALinesFirstCharacters() {
    String longName = "a.".repeat(200) + "BoomException: x";

    assertEquals(2, frameKv(HEAD_1, longName).size());
  }

  @Test
  void anExceptionTypeTheHeadAlreadyHasIsKept() {
    String head = "timestamp=2025-08-21T12:10:00Z level=ERROR msg=failed exception.type=app.Custom";
    FramedRecord trace = frameKv(head, "java.lang.IllegalStateException: boom").get(0);

    assertEquals("app.Custom", trace.parsed().record().getMeta().get("exception.type"));
  }

  private static String cri(String stream, String body) {
    return "2025-08-21T12:00:00.000000000Z " + stream + " F " + body;
  }

  @Test
  void containerLinesAreFramedByTheirBodyWithinOneStream() {
    ParserChain criChain = chain("cri", true);
    List<FramedRecord> records =
        frame(
            new StackTraceFramer(LIMITS),
            criChain,
            cri("stderr", "ERROR request failed"),
            cri("stderr", "java.lang.IllegalStateException: boom"),
            cri("stderr", "\tat com.example.Orders.place(Orders.java:42)"),
            cri("stdout", "\tat looks.Like(AFrame.java:1)"),
            cri("stderr", "{\"level\":\"INFO\",\"message\":\"  indented json\"}"));

    assertEquals(
        List.of(
            "ERROR request failed\njava.lang.IllegalStateException: boom\n"
                + "\tat com.example.Orders.place(Orders.java:42)",
            "\tat looks.Like(AFrame.java:1)",
            "  indented json"),
        bodies(records));
    assertEquals(
        "java.lang.IllegalStateException",
        records.get(0).parsed().record().getMeta().get(RecordFramer.EXCEPTION_TYPE_ATTRIBUTE));
  }

  @Test
  void aStartPatternMatchesAContainerLinesBody() {
    StartPatternFramer framer =
        new StartPatternFramer(java.util.regex.Pattern.compile("^\\["), LIMITS);
    List<FramedRecord> records =
        frame(framer, chain("cri", true), cri("stdout", "[main] boom"), cri("stdout", "detail"));

    assertEquals(List.of("[main] boom\ndetail"), bodies(records));
  }
}
