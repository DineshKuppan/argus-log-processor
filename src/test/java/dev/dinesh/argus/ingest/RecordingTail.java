package dev.dinesh.argus.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dinesh.argus.config.TailProperties;
import dev.dinesh.argus.metrics.ArgusMetrics;
import dev.dinesh.argus.tail.TailEvent;
import dev.dinesh.argus.tail.TailHub;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A tail hub that records what ingest publishes, as if someone were tailing every tenant. */
final class RecordingTail extends TailHub {

  volatile boolean watched = true;
  final List<TailEvent> published = new CopyOnWriteArrayList<>();
  final List<String> tenants = new CopyOnWriteArrayList<>();

  RecordingTail() {
    super(TailProperties.defaults(), new ObjectMapper(), ArgusMetrics.noop());
  }

  @Override
  public boolean isWatched(String tenantId) {
    return watched;
  }

  @Override
  public void publish(String tenantId, List<TailEvent> events) {
    tenants.add(tenantId);
    published.addAll(events);
  }

  List<String> bodies() {
    return published.stream().map(TailEvent::body).toList();
  }
}
