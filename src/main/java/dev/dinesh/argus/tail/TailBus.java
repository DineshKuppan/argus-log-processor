package dev.dinesh.argus.tail;

import java.util.List;

/**
 * Carries tail events between instances ({@code P6_PLAN.md} D10). Without one, {@link TailHub}
 * publishes straight into its own rings (one instance's view, P4). With one, every published line
 * goes through the bus, and the bus {@link TailHub#deliver delivers} every instance's lines to the
 * rings of this one. Implementations never wait: a full bus drops and counts.
 */
public interface TailBus {

  /** Sends {@code events} of {@code tenantId} to every instance; never blocks. */
  void publish(String tenantId, List<TailEvent> events);

  /** True while some instance tails the tenant: taps run only then. */
  boolean isWatched(String tenantId);

  /** Tells every instance that this one tails {@code tenantId} now (a new ring). */
  void watch(String tenantId);

  /**
   * The id of the tenant's ring, the same on every instance, so a {@code Last-Event-ID} from one
   * resumes on another.
   */
  String ringId(String tenantId);
}
