package dev.dinesh.argus.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.model.LogEnvelope;
import dev.dinesh.argus.sink.LogSink;
import dev.dinesh.argus.source.LogBatch;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * {@link LogSink} on the WAL topic ({@code P6_PLAN.md} D1, D2): each line one record keyed by
 * tenant and source, and on {@code commit} a marker after every line is acknowledged. {@code
 * commit} returns only once the marker is acknowledged ({@code acks=all}), so the source checkpoint
 * that follows advances only over lines Kafka holds. No transactions: lines are visible at once,
 * and an aborted segment's lines are dropped by the archive consumer when it sees the abort marker,
 * a replay of the segment or nothing for {@code orphan-timeout}.
 */
@Slf4j
public final class KafkaSink implements LogSink {

  private final KafkaWal wal;
  private final ObjectWriter writer;
  private final ArgusMetrics metrics;
  private final byte[] instance;

  public KafkaSink(KafkaWal wal, ObjectMapper mapper, ArgusMetrics metrics) {
    this(wal, mapper, metrics, null);
  }

  /**
   * @param instance {@code argus.instance.id}, sent as {@code argus-inst} on every record so the
   *     archive consumer keeps two instances' segments of one source id apart (P6 D8); or null
   */
  public KafkaSink(KafkaWal wal, ObjectMapper mapper, ArgusMetrics metrics, String instance) {
    this.wal = wal;
    this.writer = mapper.writerFor(LogEnvelope.class);
    this.metrics = metrics;
    this.instance = instance == null ? null : KafkaWal.utf8(instance);
  }

  /** Nothing to remove: an uncommitted segment's records are already in the topic (D3). */
  @Override
  public void discardUncommitted(String tenantId, String sourceId) {}

  @Override
  public SegmentWriter open(String tenantId, String sourceId, int generation, long startOffset)
      throws IOException {
    wal.ensureTopic();
    return new KafkaSegmentWriter(tenantId, sourceId, generation, startOffset);
  }

  private final class KafkaSegmentWriter implements SegmentWriter {

    private final byte[] key;
    private final byte[] segment;
    private final String describe;
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    // The first line's Kafka offset: one partition's callbacks run in send order.
    private final AtomicLong first = new AtomicLong(-1);
    private final Callback onSent =
        (RecordMetadata metadata, Exception e) -> {
          if (e != null) {
            failure.compareAndSet(null, e);
          } else {
            first.compareAndSet(-1, metadata.offset());
          }
        };
    private long records;

    KafkaSegmentWriter(String tenantId, String sourceId, int generation, long startOffset) {
      this.key = KafkaWal.utf8(tenantId + "/" + sourceId);
      this.segment = KafkaWal.utf8(generation + ":" + startOffset);
      this.describe =
          "tenant="
              + tenantId
              + " source="
              + sourceId
              + " segment="
              + generation
              + ":"
              + startOffset;
    }

    private RecordHeaders headers() {
      RecordHeaders headers = new RecordHeaders();
      headers.add(KafkaWal.VERSION, KafkaWal.utf8("1"));
      headers.add(KafkaWal.SEGMENT, segment);
      if (instance != null) {
        headers.add(KafkaWal.INSTANCE, instance);
      }
      return headers;
    }

    @Override
    public void write(LogBatch batch) throws IOException {
      check();
      for (LogEnvelope record : batch.records()) {
        RecordHeaders headers = headers();
        headers.add(KafkaWal.OFFSET, KafkaWal.utf8(Long.toString(record.source().offset())));
        send(
            new ProducerRecord<>(
                wal.topic(), null, key, writer.writeValueAsBytes(record), headers));
        records++;
      }
    }

    /** Blocks while the producer's buffer is full: backpressure on the writer stage (D5). */
    private void send(ProducerRecord<byte[], byte[]> record) throws IOException {
      long start = System.nanoTime();
      try {
        wal.producer().send(record, onSent);
      } catch (InterruptException e) {
        throw interrupted(e);
      } catch (KafkaException e) {
        throw new IOException("Kafka refused a record of " + describe + ": " + e.getMessage(), e);
      } finally {
        metrics.kafkaSendBlocked(System.nanoTime() - start);
      }
    }

    private void check() throws IOException {
      Exception e = failure.get();
      if (e != null) {
        throw new IOException("Kafka did not take a record of " + describe + ": " + e, e);
      }
    }

    @Override
    public String commit(long endOffset) throws IOException {
      if (records == 0) {
        return null;
      }
      try {
        wal.producer().flush();
      } catch (InterruptException e) {
        throw interrupted(e);
      }
      check();
      RecordHeaders headers = headers();
      headers.add(KafkaWal.TYPE, KafkaWal.utf8(KafkaWal.COMMIT));
      headers.add(KafkaWal.END, KafkaWal.utf8(Long.toString(endOffset)));
      headers.add(KafkaWal.LINES, KafkaWal.utf8(Long.toString(records)));
      headers.add(KafkaWal.FIRST, KafkaWal.utf8(Long.toString(first.get())));
      try {
        RecordMetadata marker =
            wal.producer()
                .send(new ProducerRecord<>(wal.topic(), null, key, null, headers))
                .get(wal.props().maxOutage().toMillis(), TimeUnit.MILLISECONDS);
        metrics.kafkaSegment("producer", "committed");
        return "kafka:" + marker.topic() + "/" + marker.partition() + "@" + marker.offset();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("Interrupted committing " + describe);
      } catch (ExecutionException | TimeoutException | KafkaException e) {
        throw new IOException("Kafka did not take the commit marker of " + describe, e);
      }
    }

    /** Best effort: without it, the archive consumer drops the segment on a replay or a timeout. */
    @Override
    public void abort() {
      if (records == 0) {
        return;
      }
      metrics.kafkaSegment("producer", "aborted");
      RecordHeaders headers = headers();
      headers.add(KafkaWal.TYPE, KafkaWal.utf8(KafkaWal.ABORT));
      try {
        wal.producer().send(new ProducerRecord<>(wal.topic(), null, key, null, headers));
      } catch (RuntimeException e) {
        log.debug("Abort marker of {} not sent", describe, e);
      }
    }

    private InterruptedIOException interrupted(InterruptException e) {
      Thread.currentThread().interrupt();
      InterruptedIOException io = new InterruptedIOException("Interrupted writing " + describe);
      io.initCause(e);
      return io;
    }
  }
}
