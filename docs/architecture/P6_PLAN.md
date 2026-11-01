# P6 Plan: Kafka as the Buffer (P6a) and Several Instances (P6b)

> Phase P6 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P2 (the WAL, segments and the archiver, [`P2_PLAN.md`](P2_PLAN.md)), P3 (push spools and stream runs, [`P3_PLAN.md`](P3_PLAN.md)), P4 (tail and metrics, [`P4_PLAN.md`](P4_PLAN.md)) and P5 (per-sink acknowledgements, ADR-7, and the hot indexer, [`P5_PLAN.md`](P5_PLAN.md)).
> Built and merged as two PRs, like P3a / P3b. Kafka stays **optional**: `argus.wal.store=local` (the P2–P5 WAL) remains the default, and a single instance needs nothing new.
> Exit criteria:
> - **P6a:** with `argus.wal.store=kafka`, T1, T3, T4 and T5 hold, on Apache Kafka 3.8 and Redpanda 24.2. Losing the node's disk loses at most what wasn't yet in Kafka: the push spools' last `flush-interval` and the open segment of a file job (which the source still has).
> - **P6b:** three instances share one topic. `kill -9` of any one loses and duplicates nothing in either tier. A line pushed to one instance reaches a tail on another within T7's 2s. Consumer lag drives an autoscaler.

## 1. Goal and scope

Since P2, the committed local segments are Argus's WAL: durable on one node's SSD, drained by the archive and, since P5, the hot tier. This has two limits.
- **Durability:** a lost disk loses every segment no sink has acknowledged yet. That is minutes of lines normally, and up to the 24GB high watermark during a sink outage (the "Archive gaps" in CLAUDE.md).
- **Scale:** every consumer runs on the node that ingested the line, so one node's CPU caps both ingest and the sinks, and a second instance shares nothing (streams, suppression and the tail are per instance).

P6a replaces the WAL with a Kafka topic, with the sinks as consumer groups (ADR-7 already has that shape). P6b lets several instances ingest into it and consume from it, shares suppression and the tail across them, and scales them on consumer lag.

| In scope (P6a) | In scope (P6b) | Out of scope |
|---|---|---|
| `argus.wal.store=kafka`: `KafkaSink` behind the existing `LogSink` port, segments delimited by commit markers (D1, D2) | `argus.instance.id`: each instance's sources namespaced in `eventId`, archive keys and documents (D8) | Kafka as an ingest *source* (reading producers' own topics), §8 |
| The archive as consumer group `argus-archive`: staged segments → the P2 archiver unchanged → offset commit (D3) | Consumer groups shared across instances; rebalances tested under `kill -9` (D9) | Exactly-once transactions (D2 explains why not) |
| The hot tier as consumer group `argus-hot`: records → `_bulk` → offset commit (D4) | Shared live tail through a tail topic; `Last-Event-ID` across instances (D10) | Suppression partitioned by Kafka key (D11 explains why not) |
| Backpressure: consumer lag watermarks; a broker outage blocks the writer (D5) | Suppression across instances: the Redis store, pipelined per request (D11) | SASL/IAM for Amazon MSK; Schema Registry / Avro |
| Topic checks at startup; draining a local WAL into Kafka when switching (D6) | Push affinity by stream, graceful drain on shutdown (D12) | A shared job registry (jobs stay on the instance that took them, D12) |
| Metrics, canonical lines, tests on Kafka and Redpanda (§5) | Kubernetes manifests: StatefulSet, KEDA lag scaler / HPA (D13) | Multi-region; cross-cluster replication |

## 2. Decisions (STAR, per guardrail 10)

### D1. The WAL becomes a topic: one record per line, keyed by source, segments delimited by markers (ADR-8)
- **Situation:**
  - The pipeline writes through `LogSink.open → write(batch) → commit(endOffset)`. The source checkpoint advances only after `commit` returns (P0's commit protocol, guardrail 3).
  - Segments are the unit of the checkpoint (≤500MB of source) and of the archive's object names (`…/source=S/gen=G/FROM-TO.parquet`, deterministic, so a replay overwrites).
  - ADR-7 made each sink an independent consumer with its own acknowledgement.
- **Task:** put the WAL in Kafka without changing the checkpoint protocol, the archive layout or the sinks' idempotency, and without holding a 500MB segment in memory anywhere.
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | (a) A Kafka sink as a third consumer of the local WAL | Smallest change | The disk is still the WAL: nothing gained for durability or scale |
  | (b) One record per segment (or per batch) | Segment boundaries are explicit | Records up to 8MB+ (broker limits, memory); the hot tier waits for whole batches; segments don't fit in a record |
  | **(c) One record per line, key `tenant/source`, plus a commit marker record per segment** | Kafka batches and compresses as designed; per-line freshness for the hot tier; a segment's lines and marker share a partition, in order | The archive must reassemble segments (D3) |
  | (d) (c) with Kafka transactions instead of markers | Uncommitted segments invisible to `read_committed` consumers | D2 |

  Chosen (c).
  - **Topic** `argus.kafka.topic` (`argus-wal`), shared by every tenant, like the shared hot index (ADR-4). Guardrail 4 holds through the record: key and value start with the tenant, and no consumer ever acts on a record without its `tenantId`. Topics per tenant were rejected: partitions per tenant multiply, and tenants are configuration.
  - **Record:**
    - key `<tenant>/<source>` (UTF-8);
    - value = the `LogEnvelope` JSON, exactly the WAL's NDJSON line, so every consumer reuses its parsing;
    - headers `argus-seg = <generation>:<startOffset>` (the segment it belongs to) and `argus-v = 1`.
  - **Marker:** on `commit(endOffset)`, after `producer.flush()` has acknowledged every line of the segment:
    - a record with the same key, null value and headers `argus-type = commit`, `argus-seg`, `argus-end = <endOffset>`, `argus-lines = <n>`, `argus-first = <Kafka offset of the segment's first line>` (from the first send callback: one partition's callbacks run in send order);
    - `commit` returns only once the marker is acknowledged (`acks=all`).
    - `abort()` sends `argus-type = abort`, best effort.
  - **Producer:** `enable.idempotence=true` (ordering per partition with retries), `acks=all`, `compression.type=zstd`, `linger.ms=20`, `batch.size=1MB`, `max.request.size=4MB` (a 1MB line becomes an envelope of up to ~2MB of escaped JSON).
- **Result:** the checkpoint still advances only once the segment is durable, now in Kafka (replicated) instead of on one SSD. A replayed segment (a crash before the checkpoint) is re-sent with the same `argus-seg` and the same `eventId`s.

### D2. Markers, not transactions
- **Situation:** Kafka transactions map neatly onto `open` / `commit` / `abort`: `read_committed` consumers never see an aborted or open segment.
- **Task:** decide whether that is worth its costs here.
- **Action:**
  - **Against transactions:**
    - An open transaction holds the partition's *last stable offset*: `read_committed` consumers of that partition see nothing newer, from any source, until it commits.
    - A 500MB file segment takes ~12s at full speed (40MB/s) and over 60s on the replay lane (8MB/s), past the producer's default `transaction.timeout.ms`. Every stream sharing the partition would be stalled for that long, breaking T5 (30s).
    - Transactions also need one `transactional.id` per concurrent writer (jobs and stream runs run in parallel), so a pool of producers.
  - **For markers:**
    - Lines are visible at once (the hot tier indexes them as they come, D4).
    - Only the archive needs segment boundaries, and it reassembles them itself (D3).
    - An open or aborted segment costs the archive a staging file, not every consumer a stall.
  - **What markers give up:** the hot tier can index lines of a segment that is later aborted. Those are real lines read from the source, and a replay re-sends them with the same `eventId`s (same tenant, source, generation and offset). If the source was replaced in between (a new generation), the lines stay indexed once: at-least-once, as guardrail 3 allows.
- **Result:** markers, with an idempotent (non-transactional) producer.

### D3. The archive consumer reassembles segments and reuses the P2 archiver
- **Situation:** the `Archiver` turns one committed segment file into one Parquet object per UTC hour, keyed by source offsets, and supersedes an earlier copy through `ArchiveIndex`. A partition interleaves the segments of many sources.
- **Task:** produce exactly the P2 objects, keys and supersede behaviour from the topic, with bounded memory, and commit Kafka offsets only for archived data.
- **Action:** one consumer thread in group `argus-archive` (`SmartLifecycle`, like the archiver).
  - **Staging:**
    - Each line goes to a local staging file per open segment: `staging-dir/tenant=T/dt=kafka/source=S/gen=G/<start>.staging`, zstd level 1 as in P2. A key and source are in one partition, so no partition directory is needed.
    - A line whose source offset isn't past the staged segment's last one starts a new attempt (a replay): the staged file is discarded and restarted.
    - Disk use is bounded by open segments × segment size: a few concurrent runs × ≤500MB of source, about 200MB each in zstd (NDJSON envelopes are ~4x the source, zstd ~10x smaller).
  - **On a commit marker:**
    - the staged file is renamed `<start>-<end>.ndjson.zst`, laid out like the local WAL, and becomes a `WalSegments.Segment`;
    - `Archiver.archive(segment)` runs unchanged (Parquet per hour → `ObjectStore.put` → `ArchiveIndex` supersede, the index kept in the staging root);
    - the staged file is then deleted.
    - A copy staged from a Kafka offset past the marker's `argus-first` is **partial**: reading resumed at a committed offset inside a segment whose first lines were read before (the commit stops at another source's open segment, which can start in the middle of this one). That segment was archived, or dropped, back then, so the partial copy is deleted, never archived: archiving it would supersede the complete copy with its tail (found in review; `ArchiveConsumerTest` covers it on both brokers).
    - Otherwise, a line count that doesn't match the marker's `argus-lines` (a bug) logs an ERROR, and the segment is **archived anyway**: the lines are real, and skipping it would lose them from the cold tier.
  - **Abort marker:** the staged file is discarded.
  - **Orphans:** a staged segment with no record for `orphan-timeout` (15m) is discarded with a WARN. Its producer died and will replay it in full from its checkpoint, or the source is gone.
    - The clock runs only while the consumer reads: while it holds its partitions (archive failing, or `MAX_READY` segments waiting), no expiry, and resuming restarts every staged segment's clock. Otherwise an archive outage longer than the timeout would expire open segments and move the commit past their first lines (found in review).
    - `orphan-timeout` must be longer than `max-outage` (checked at startup): a writer blocked on the cluster is silent, not gone.
  - **Offset commits:**
    - Per partition, the committable offset is the smallest first-offset of any open staged segment, or the next offset to read if none is open.
    - It is committed (`commitSync`) after each archived segment and every `commit-interval` (5s).
    - So a restart or a rebalance re-reads every segment that isn't archived, and never skips one.
  - **Rebalance:** on revocation, commit the committable offsets and drop that partition's staged files; the new owner re-stages from the committed offset. Staged files left by a crash are deleted at startup.
  - **Failures:** an object-store failure backs off as in P2 (`retry-initial` → `retry-max`) and pauses the consumer (`consumer.pause`, still polling, so the group doesn't evict it).
- **Result:** the cold-tier layout is identical whichever WAL is configured: switching stores changes no object key and needs no migration of the archive.

### D4. The hot consumer indexes records as they arrive
- **Situation:** the `HotIndexer` streams a segment into `_bulk` bodies (`_id = eventId`, routing), with per-item retries, rejections and backoff.
- **Task:** the same guarantees from the topic, at least as fresh as T5.
- **Action:**
  - Group `argus-hot`, one thread.
  - Records (markers skipped) fill a bulk of ≤ `bulk-max-docs` / `bulk-max-bytes`, sent when full or after `linger` (1s).
  - Offsets are committed after the bulk's items are all indexed, rejected or outside retention.
  - A failed request pauses the partitions, backs off, and resends the same bulk (still in memory); offsets don't move.
  - `HotIndexer`'s bulk sending (per-item results, retries, the canonical line) moves into `HotBulkWriter`, shared by the WAL indexer and the consumer.
  - Canonical line: one `Hot bulk finished …` per bulk (the unit is a bulk now, not a segment).
- **Result:** documents stored exactly once per `eventId`; freshness no longer waits for a segment to close (a stream run's 10s still applies, D7 of P5).

### D5. Backpressure: lag watermarks replace WAL bytes; a broker outage blocks the writer
- **Situation:**
  - `WatermarkWalCapacity` pauses ingest when unacknowledged WAL bytes pass the high watermark.
  - Kafka deletes by retention (time or size), not by acknowledgement: a group that falls behind retention loses data silently. That would break guardrails 3 and 6.
- **Task:** keep T3 (a stalled sink pauses sources, no loss) with Kafka.
- **Action:**
  - **`KafkaWalCapacity`** implements the `WalCapacity` port.
    - Every 5s (`AdminClient`, on its own thread, so a push asking whether the WAL is full never waits on Kafka), it computes each routed group's lag in records: end offsets − committed offsets, summed over partitions. If Kafka can't be asked, the last measurement stands.
    - Full above `high-watermark` × `max-lag-records`; free again below the low watermark. Same hysteresis, same `paused: WAL_FULL` and 429s as today.
    - `max-lag-records` defaults to 100M, ~2.8 days at the target's 36M lines a day.
    - At startup, `retention.ms` below twice that time, a finite `retention.bytes`, or `cleanup.policy=compact` fail startup (or only warn, with `argus.kafka.check-topic=warn`).
  - **Broker outage:**
    - `send` blocks when the producer's buffer (`buffer.memory`, 32MB) is full, so the writer stage blocks, the bounded queues fill and the reader stops (guardrail 2). Pushes keep landing in their spools until `max-spool-bytes`, then 429.
    - `delivery.timeout.ms` and `max.block.ms` are set from `argus.kafka.max-outage` (10m, T3's length). Past it, the segment is aborted and the run fails: stream runs retry on their own, and file jobs are resumable from their checkpoint.
  - **Lag gauges:** `argus_kafka_lag_records{group}` and `argus_kafka_lag_seconds{group}` (the consumer's own: now − timestamp of the last record it handled, while it has lag). D13 scales on them.
- **Result:** a stalled sink pauses ingest before its group's data can expire, and a broker outage is backpressure, not failure.

### D6. Topic checks and switching from a local WAL
- **Situation:** a deployment moving from `local` to `kafka` may have unacknowledged local segments.
- **Task:** switch without losing them or writing them twice.
- **Action:**
  - **Topic checks:** with `argus.kafka.create-topics=true` (dev default), Argus creates the topic: `partitions` (12), `replication-factor` (3), `min.insync.replicas=2`, `retention.ms=7d`, `max.message.bytes=4MB`. Otherwise it checks an existing topic (D5's rules, and `max.message.bytes` ≥ `max.request.size`).
  - **Draining a local WAL (`WalForwarder`):** at startup with `store=kafka`, every committed local segment some routed sink hasn't acknowledged is sent as a segment (lines + marker, the same `argus-seg`), then deleted. A crash midway re-sends it whole; the sinks' idempotency (D3, D4) absorbs that.
- **Result:** one restart switches stores; there is no runbook step "wait for the WAL to drain".

### D7. Memory budget (P6a)
| Item | Bound |
|---|---|
| Producer buffer | `buffer.memory` 32MB (shared by every writer) |
| Archive consumer | `fetch.max.bytes` 16MB + the existing 64MB row group |
| Hot consumer | `fetch.max.bytes` 16MB + one bulk (5MB) |
| Admin client, metadata | a few MB |
| **Added** | **≈ 75MB of the 2.4GB heap** (load tests run in a 256MB JVM, so this is checked, not assumed) |

### D8. Several instances: an instance id in every source's identity (P6b)
- **Situation:** `eventId = UUIDv3(tenant, source, generation, offset)`, and archive keys use the same inputs. Source ids are chosen by clients (stream ids, file `sourceId`s), and checkpoints are per instance.
  - Two instances each ingesting stream `app` (a load balancer spreading one agent's pushes) or `/var/log/app.log` would produce the same `eventId`s and object keys for different lines.
  - In the hot tier that overwrites documents; in the archive it overwrites objects. Either way lines are silently lost.
- **Task:** make identities unique across instances, without changing them for single-instance deployments.
- **Action:**
  - `argus.instance.id` (empty by default: identities unchanged). With several instances it is required; a consumer won't join while its group has a member with its client id (§9 corrections). In Kubernetes it is the StatefulSet pod name (`argus-0`, …), stable across restarts, with its own PVC for spools and checkpoints.
  - When set:
    - the instance joins the identity: `eventId = UUIDv3(tenant, source, instance, generation, offset)`;
    - envelopes carry `source.instance`;
    - archive keys gain `instance=I/` after `source=S/`;
    - Parquet gains a `source_instance` column (columns are added, never renamed);
    - hot documents gain `source_instance` (template `VERSION` 2: fields are added, never retyped).
  - Search, tail and status still show the client's source id; `source.instance` says where it was ingested.
- **Result:** no two instances can produce the same `eventId`. A single instance keeps P0–P5's identities byte for byte.

### D9. Consumer groups across instances
- **Situation:** D3 and D4 consumers already commit only processed offsets, and drop staged state on revocation.
- **Task:** spread consumption over instances and survive `kill -9` of any of them.
- **Action:**
  - Every instance runs both consumers in the same groups. Kafka assigns partitions with the cooperative-sticky assignor, so a rebalance moves only the partitions that change owner.
  - The partition count (12) caps each group's parallelism, so the autoscaler's maximum (D13) is ≤ partitions.
  - `session.timeout.ms=45s`. A killed instance's partitions move after it; the new owner re-reads from the committed offsets (the archive re-stages open segments, the hot tier re-sends one bulk at most).
- **Result:** tested by `MultiInstanceIT` (§5): exact counts in both tiers after `kill -9` of one of three instances under load.

### D10. A shared live tail through a tail topic
- **Situation:**
  - P4's taps publish to the local `TailHub`: a pushed line once durable in the spool, a file line at parse. So a tail on instance B never sees lines pushed to A.
  - T7 needs ≤2s. The WAL topic is too late for pushes: their lines reach it only at the stream run, up to `flush-interval` after the 202.
- **Task:** every instance's tails see every instance's lines, within T7, without the tail ever backpressuring ingest (guardrail 7).
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | Tail from the WAL topic | No new topic | Pushes are up to 10s late (T7 fails) |
  | Redis pub/sub or Streams | Already optional | A second shared dependency for the tail alone; pub/sub drops on slow subscribers without counting |
  | **A tail topic (`argus-tail`, 1h retention, keyed by tenant)** | Same client and operations as the WAL; per-tenant order (a tenant's records share one partition); offsets give resumable event ids | Every instance reads every partition |

  Chosen: the tail topic.
  - **Publishing:**
    - Taps go to a `TailBus` port: `LocalTailBus` (today's direct publish) or `KafkaTailBus`.
    - `KafkaTailBus` uses its own producer: `acks=1`, `buffer.memory` 8MB, `max.block.ms=0`. A full buffer **drops and counts** (`argus_tail_bus_dropped_total`) and never waits (guardrail 7).
  - **Which tenants are watched:**
    - Taps run only for tenants someone tails, on any instance. Each instance with an open tail for tenant T sends a `watch` heartbeat record for T every 5s.
    - Every instance reads every partition (`assign`, no group, from the end) and treats a tenant as watched for 15s after its last heartbeat.
    - So `isWatched` stays a map lookup, and an unwatched tenant still costs nothing at ingest.
  - **Delivery:** each instance's tail consumer appends records to its local `TailRing`s, as taps do now. The rings, filters, rate caps and drop markers of P4 are unchanged.
  - **Event ids** become `p<partition>-<offset>`, the same on every instance. A reconnect to another instance resumes if its ring still holds that offset (a tenant's records are in one partition, in order), else `reset`, as now.
- **Result:** T7 across instances. A tail outage (tail topic unavailable) drops tail lines and counts them; ingest never notices.

### D11. Suppression across instances: the Redis store, pipelined; not partitioned by key
- **Situation:**
  - The design (§6, ADR-2) planned "partition by key: Kafka key = suppression key, so each key has one owning instance and a local cache stays correct".
  - Since P3a, suppression decides **at receive time** (P3a D3), inside the push request, before the 202. The `POST /api/v2/suppression/check` API answers ALLOW / DENY synchronously.
  - `RedisSuppressionStore` exists (one Lua call per line, fails open) but runs **sequentially**: a 10,000-line request makes 10,000 round trips (≈2–5s at 0.2–0.5ms each).
- **Task:** correct suppression when one tenant's duplicates arrive at several instances, without slowing pushes.
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | Partition by key through Kafka | Local state stays correct | The decision would happen after Kafka, not in the request: pushes would be suppressed after being acknowledged, the check API couldn't answer, and the tail would show duplicates. It moves suppression out of receive time, the guarantee P3a chose |
  | Forward each line to the key's owner instance | Local state | An RPC hop per line, and a membership protocol Argus doesn't have |
  | **The Redis store, pipelined per request** | Exists, atomic, fails open; one round trip per request instead of per line | Redis is required for exact cross-instance suppression |
  | The local store on each instance | Nothing new | Duplicates across instances (each allows once per window); acceptable only with stream affinity (D12) |

  Chosen: Redis, pipelined.
  - `SuppressionStore.acquireAll(keys)` makes one pipelined `EVALSHA` batch per push request. `PushIngestService` collects a request's keys first (it already de-duplicates within the request).
  - With `argus.instance.id` set and `argus.suppression.store=local`, startup warns that suppression is per instance.
  - **This deviates from the design's ADR-2.** ADR-2 is updated: partition-by-key fits suppression after a buffer, which P3a deliberately didn't choose.
- **Result:** exact cross-instance suppression with Redis, and a measured p99 per request (§5); local stays correct per instance.

### D12. Push affinity, graceful drain, jobs stay where they started
- **Situation:**
  - A stream's spool, `Idempotency-Key` index and checkpoint live on one instance.
  - Pushes for one stream spread over instances become several streams (D8 keeps their lines distinct). A retry that lands elsewhere isn't recognised as a repeat, so it is stored twice, with different `eventId`s.
- **Task:** keep exactly-once with keys in the normal case, lose nothing on scale-down.
- **Action:**
  - **Affinity:** the documented ingress routes `/api/v2/ingest/streams/{streamId}` by consistent hash of the path, e.g. `nginx.ingress.kubernetes.io/upstream-hash-by: "$uri"`. Retries reach the same instance except while instances are added or removed, when a retry can be stored twice (documented, counted by `argus_push_requests_total{instance}`).
  - **Drain on shutdown:**
    - On SIGTERM: readiness goes down; pushes get 503 + `Retry-After: 1`, so agents retry and the ingress sends them elsewhere.
    - Stream runs ingest every spool to the end; the producer flushes.
    - Then the consumers commit and leave the groups.
    - `terminationGracePeriodSeconds` 120s. A spool not drained in time stays on the pod's PVC and is ingested when the pod returns.
  - **Jobs** (file ingest, exports) run on the instance that accepted them. Their ids carry the instance (`argus-1:…`), and `GET` / `DELETE` of another instance's job answer 404 with that instance in the error. A shared job registry is §8.
- **Result:** no line lost on scale-down. Duplicates are possible only for un-keyed pushes, or keyed retries during a reshuffle.

### D13. Scaling on consumer lag
- **Situation:** Argus has no deployment files; HPA on lag is the design's Deployment item.
- **Task:** scale instances when the sinks fall behind, within the partition count.
- **Action:**
  - `deploy/k8s/`:
    - a StatefulSet (instance id = pod name, a PVC per pod, the D12 probes and grace period);
    - a headless Service plus a ClusterIP Service;
    - an Ingress with the D12 hash;
    - a KEDA `ScaledObject` on the `argus-archive` and `argus-hot` groups' lag (Kafka scaler, `lagThreshold` 500k records, `maxReplicaCount` ≤ partitions);
    - a plain HPA alternative on `argus_kafka_lag_records` through the Prometheus Adapter.
  - A `deploy/k8s/README.md` with the sizing and the limits (D9's cap, D12's affinity).
  - Verified in `kind` with a Kafka pod: manifests apply, a lag spike scales 1 → 3, scale-down drains. Recorded in §9; not part of `mvn test`.
- **Result:** the autoscaler sees the same lag the watermarks use (D5).

## 3. Components

| Class | Package | Part | Role |
|---|---|---|---|
| `KafkaSink` | `kafka` | a | `LogSink` on the WAL topic: lines, commit / abort markers (D1, D2) |
| `KafkaWal` | `kafka` | a | Producer, admin client, topic creation and checks (D6) |
| `KafkaWalCapacity` | `kafka` | a | `WalCapacity` from group lag (D5) |
| `ArchiveConsumer`, `SegmentStager` | `kafka` | a | Group `argus-archive`: staging, markers, `Archiver.archive`, offset commits, rebalances (D3) |
| `HotConsumer` | `kafka` | a | Group `argus-hot` (D4) |
| `HotBulkWriter` | `hot` | a | `_bulk` sending extracted from `HotIndexer`, shared (D4) |
| `WalForwarder` | `kafka` | a | Local WAL → topic at startup (D6) |
| `KafkaProperties`, `KafkaConfig` | `config` | a | `argus.kafka.*`, wiring (only with `argus.wal.store=kafka`) |
| `Archiver`, `WalConfig`, `MetricsConfig` | existing | a | `archive(segment)` callable for a staged segment; WAL store choice; lag gauges |
| `EnvelopeFactory`, `EnvelopeSchema`, `HotDocument`, `HotTemplates` | existing | b | Instance in the identity, the column and the field (D8) |
| `TailBus`, `LocalTailBus`, `KafkaTailBus`, `TailWatchers` | `tail` | b | Shared tail and watched tenants (D10) |
| `RedisSuppressionStore`, `PushIngestService` | existing | b | `acquireAll`, pipelined (D11) |
| `InstanceDrain` | `ingest` | b | Shutdown order (D12) |
| `deploy/k8s/*` | — | b | D13 |

## 4. Configuration and meters

```properties
argus.wal.store=local                      # local | kafka
argus.kafka.bootstrap-servers=
argus.kafka.topic=argus-wal
argus.kafka.tail-topic=argus-tail          # P6b
argus.kafka.create-topics=false            # true in the dev profile
argus.kafka.check-topic=fail               # fail | warn (D5, D6)
argus.kafka.partitions=12
argus.kafka.replication-factor=3
argus.kafka.max-outage=10m                 # a broker outage blocks this long before a segment is aborted
argus.kafka.max-lag-records=100000000      # with argus.wal.high-watermark / low-watermark
argus.kafka.orphan-timeout=15m
argus.kafka.commit-interval=5s
argus.kafka.properties.*=                  # passed to every client: security.protocol, sasl.*, ssl.* (from the environment)
argus.instance.id=                         # P6b; the pod name in a StatefulSet
```

| Meter | Type | Tags |
|---|---|---|
| `argus_kafka_lag_records` | gauge | `group` |
| `argus_kafka_lag_seconds` | gauge | `group` |
| `argus_kafka_segments_total` | counter | `outcome` (`committed`, `aborted`, `orphaned`, `partial`), producer and archive consumer |
| `argus_kafka_send_blocked_seconds` | timer | Time writers spent blocked on a full producer buffer |
| `argus_tail_bus_dropped_total` | counter | `tenant` (P6b) |
| kafka-clients' own metrics | via Micrometer's `KafkaClientMetrics` | `client.id` |

## 5. Tests

| Kind | Test |
|---|---|
| Unit | `SegmentStagerTest`: interleaved segments, a replay restarting a staged segment, marker checks, abort, orphan timeout, the committable offset |
| Broker (Testcontainers) | `KafkaSinkTest`, parameterised over **Apache Kafka 3.8 (KRaft)** and **Redpanda 24.2**: lines and markers in one partition, in order; `commit` returns only after the marker is acknowledged; a broker paused mid-segment blocks and then completes |
| Broker | `ArchiveConsumerTest`: the same objects and keys as the local archiver for the same input (compared key by key); a crash before the offset commit re-archives into the same keys; a rebalance mid-segment |
| Broker | `HotConsumerTest`: exact documents after a failed bulk and a restart; markers skipped |
| Broker | `KafkaWalCapacityTest`: lag hysteresis; topic checks (compact, short retention, small `max.message.bytes`) fail startup |
| Broker | `WalForwarderTest`: pending local segments forwarded once; a crash midway re-sends without duplicates downstream |
| Unit / wiring | `ArgusWebApplicationTest` with `store=local` (no Kafka beans); `EnvelopeFactoryTest`: identities unchanged without an instance id, distinct with one (P6b) |
| Load, P6a (`load` tag) | `KafkaWalIT` on both brokers: **T1** (file ingest lines/s to the topic, compared with the local WAL's 133k), **T3** (broker stopped 60s under push: spools absorb, 429 only past `max-spool-bytes`, then everything archived and indexed exactly once; archive store down: ingest pauses at the lag watermark), **T4** (`kill -9` mid-segment: archive objects and documents exact), **T5** (p99 push-to-searchable ≤30s) |
| Load, P6b | `MultiInstanceIT`: three server JVMs on one broker, pushes spread over them; `kill -9` of one under load: exact counts in both tiers; a tail on instance B sees lines pushed to A (p99 ≤2s, T7); 16 stalled tail clients don't slow pushes |
| Load, P6b | `SuppressionAcrossInstancesIT`: the same duplicates to two instances allowed once per window with Redis; p99 of a 10,000-line request's suppression |
| Deployment, P6b | `kind`: manifests apply, KEDA scales 1 → 3 on lag, scale-down drains (§9; manual) |

## 6. Implementation order (one commit per step)

**P6a (first PR)**
1. The plan (this file); ADR-8 and the ADR-2 / ADR-5 / ADR-7 updates in the design doc; ROADMAP.
2. `kafka-clients` (managed by Spring Boot) and Testcontainers `kafka` + `redpanda`; `KafkaProperties`, `KafkaWal` (producer, admin, topic checks); `argus.wal.store`.
3. `KafkaSink` with markers + broker tests.
4. `SegmentStager`, `ArchiveConsumer`; `Archiver.archive` for a staged segment + tests.
5. `HotBulkWriter` extracted; `HotConsumer` + tests.
6. `KafkaWalCapacity`, lag gauges, `max-outage` blocking; `WalForwarder` + tests.
7. `KafkaWalIT` (T1, T3, T4, T5 on both brokers); results in §9.
8. Docs: CLAUDE.md, README, FEATURES, ROADMAP, CHANGELOG, design doc §4 / §5 / §8 / §11.

**P6b (second PR)**
9. Instance identity (D8): `eventId`, keys, column, field, template v2 + tests.
10. `TailBus`, `KafkaTailBus`, heartbeats, offset event ids + tests.
11. Pipelined Redis suppression + tests.
12. Shutdown drain, job ids with the instance.
13. `MultiInstanceIT`, `SuppressionAcrossInstancesIT`; results in §9.
14. `deploy/k8s/` + the `kind` check; docs.

## 7. Risks

| Risk | Mitigation |
|---|---|
| A group falls behind retention and Kafka deletes unarchived lines | Lag watermark pauses ingest first (D5); startup refuses short retention, finite `retention.bytes`, compaction; `argus_kafka_lag_seconds` to alert on |
| Archive staging fills the disk (many concurrent open segments) | Bounded by concurrent runs × segment size (D3); `min-free-bytes` applies to the staging dir; orphans expire |
| A segment's lines and its marker in different partitions | Same key → same partition with the default partitioner; the key is fixed per source; a test asserts it on both brokers |
| Redpanda differs from Kafka (admin API, configs) | Every broker test runs on both; differences recorded in CLAUDE.md |
| The archive index (`ArchiveIndex`, the keys of a source's latest archived segment) is on the consumer's local staging disk | Enough with one instance. In P6b a partition can move to another instance, which wouldn't see it: a replayed segment with a different end could then leave its earlier copy's objects (duplicate rows). P6b moves the index where every instance reads it (the object store) |
| Rebalances during long Parquet uploads evict the archive consumer | Uploads run while the consumer is `pause`d and keeps polling; `max.poll.interval.ms` 10m |
| Cross-instance duplicates for un-keyed pushes or reshuffles | D12's affinity; documented; at-least-once is guardrail 3's contract |
| Template v2 on a cluster with v1 indices | Fields only added; today's and later indices get v2; old days keep v1 and lack `source_instance` (searchable as before) |
| Test time and memory with two brokers and engines | Containers one at a time; Redpanda in `--smp 1 --memory 512M` dev mode |

## 8. Later (not P6)
- **Kafka as an ingest source:** read producers' own topics (offsets committed in Kafka, ADR-5's `KafkaCommittedOffsets`), like the export sources.
- **Push straight to Kafka**, removing the spool's `flush-interval` of local-only durability.
- **A shared job registry** (file jobs and exports visible from any instance).
- **Tail warm-up:** pre-fill a new ring from the tail topic's last minute (offsets for times), so a reconnect after a restart resumes instead of `reset`.
- **End-of-window summaries for the Redis suppression store.**
- **SASL / IAM for Amazon MSK; Schema Registry** if other teams consume `argus-wal`.

## 9. Results

### P6a
`KafkaWalIT` and `HotTierIT -Dargus.load.wal=kafka` (tag `load`): the application in its own 256MB JVM on the host (a 10-core laptop), pushed to over HTTP, writing to one Apache Kafka 3.8 (KRaft) or Redpanda 24.2 container (one broker, replication 1, 6 partitions) and archiving to a filesystem store. Host only: the broker advertises a host port that a containerised server couldn't reach, so the 2-CPU container runs of earlier phases weren't repeated here.

| Check | Apache Kafka 3.8 | Redpanda 24.2 |
|---|---|---|
| **T1**: 1M lines pushed by 8 clients as fast as accepted, to fully ingested; the local WAL under the same load in the same run | **131.9k lines/s** (local WAL 125.6k); peak heap 248MB | **130.1k lines/s** (local WAL 131.7k); peak heap 252MB |
| **T3, broker paused 60s** under 2,000 lines/s of keyed pushes | 120,400 lines accepted into the spools during the outage (no 429s); all 180,800 archived exactly once, caught up 16.8s after the broker returned | 120,000 accepted during the outage; all 180,800 archived exactly once, 17.0s after |
| **T3, archive store unwritable 30s** under 4,000 lines/s, `max-lag-records` 50,000 (high watermark 40,000) | Lag watermark reached: 156 pushes got 429 `WAL_FULL`, 60,800 lines accepted during the outage (of 120,000 offered); all 180,400 archived exactly once, 16.4s after | 160 429 `WAL_FULL`, 61,200 accepted; all 180,400 archived once, 17.2s after |
| **T4**: `kill -9` mid-push (2MB segments), 400,000 keyed lines, restart | Killed with 117,000 archived; afterwards exactly 400,000 rows, none twice | Killed with 100,000 archived; exactly 400,000 rows |

Hot tier with Kafka as the WAL (Apache Kafka, OpenSearch 2.17, host):

| Check | Result |
|---|---|
| **T5**: 4,150 lines/s pushed for 2 minutes, a probe line every second, from its 202 to `GET /api/v2/search` finding it | **p50 9.9s, p99 22.3s, max 23.3s** (117 of 117 probes); local WAL on the host in P5: p99 17.7s. Peak heap 206MB |
| OpenSearch paused 60s under 2,000 lines/s, with a filesystem archive | Push went on (120,300 lines acknowledged during the outage) and so did archiving (24 segments); the index held exactly the 200,800 acknowledged lines 29.9s after the engine returned |
| `kill -9` while indexing, 160,000 keyed lines | Killed with 40,900 indexed; afterwards exactly 160,000 documents |

Notes:
- Throughput is the same as the local WAL's within run-to-run noise: the producer batches and compresses, and the cost moves from fsyncing segment files to Kafka's acknowledgements.
- The T1 Kafka runs reached 248–252MB of the 256MB test heap (the local WAL 222–237MB): the 32MB producer buffer (D7) fills under an unthrottled push. The production heap (2.4GB) leaves ample room; D7's ≈75MB holds.
- During the broker outage nothing was archived (the topic was unreachable) but nothing was refused either: the spools (2GB) absorbed 60s at 2,000 lines/s.

### P6b
`MultiInstanceIT` and `SuppressionAcrossInstancesIT` (tag `load`), on the host (a 10-core laptop):
- each instance is its own 256MB server JVM, with its own volume and `argus.instance.id`;
- all of them share one broker (6 partitions, replication 1), one tail topic, one filesystem archive store and one OpenSearch 2.17 cluster;
- clients push each stream to one instance, as the ingress's hash would.

| Check | Apache Kafka 3.8 | Redpanda 24.2 |
|---|---|---|
| **D9**: three instances, 6 keyed pushers (300,000 lines, 2MB segments); `kill -9` of `argus-1` once a quarter was archived, restart 10s later with its id and volume | Killed with 83,000 archived and 22,343 indexed; afterwards **exactly 300,000** rows in the archive and **300,000** documents in the hot tier | Killed with 100,000 archived and 23,579 indexed; exactly 300,000 and 300,000 |
| **D10 / T7 across instances**: a tail open on instance 1, 100 lines pushed to instance 0, each timed from its 202 to its SSE frame | **p50 24.8ms, p99 54.3ms**, max 167.6ms | p50 27.7ms, p99 45.6ms, max 170.8ms |

| Check (Redis 7.2, two instances) | Result |
|---|---|
| **D11**: the same 1,000 messages pushed to instance 0, then to instance 1, within the window | 1,000 accepted and 1,000 suppressed in all: each message allowed once across both |
| **D11**: p99 of a 10,000-line push of unique messages (50 requests after 5 warm-ups) | **Redis 680.8ms** (10 pipelined round trips); the local store 53.8ms. The plan estimated 2–5s for 10,000 sequential calls |

**D13, `kind`** (`deploy/k8s/kind`):
- **Setup:** kind v0.33 (Kubernetes 1.37), KEDA 2.20.2, one-broker Kafka, S3Mock and Redis in the cluster. The pods are 768Mi each, with 6 partitions and a lag threshold of 20,000 records.
- **Scale-up:** S3Mock was scaled to 0 with 2,000 lines/s pushed to `argus-0`. The `argus-archive` lag reached 43,014, and **KEDA scaled the StatefulSet 1 → 3 within 31s**. `argus-1` and `argus-2` each took two of the six partitions.
- **Scale-down:** after S3Mock came back, the lag drained to 0 and **KEDA scaled back to 1 within 108s**, one pod at a time. Each removed pod logged `Instance drain finished pushesFinished=true spoolsDrained=true` once.
- **Exactness:** the 290,000 lines the spools accepted were all archived (96 segments: 49 by `argus-0`, 24 by `argus-1`, 23 by `argus-2`), with no segment archived twice.
- **Why 290,000, not the pusher's 300,000:** 10 batches reused the keys of a smoke test run against an earlier topic. The spool answered them as duplicates (200), so they weren't stored again.

Notes:
- **Redis suppression cost.** Pipelining makes Redis suppression affordable, but still about 10x the local store for a 10,000-line request. The cost is Redis running 10,000 Lua calls, plus the round trips through Docker Desktop's port forwarding.
- **Kill under load.** A killed instance's pushes failed (connection refused) until its restart, then were retried with their keys and accepted once. Its partitions had moved to the other two after the session timeout. Nothing was duplicated in either tier, because of offset-derived keys and `_id = eventId`.

Corrections to the decisions above, found while building and testing:
- **D8: the archive index moved to the object store.** With Kafka, `ArchiveIndex` entries are objects under the archive prefix (`ObjectStore.get` was added to every store). With the local WAL they stay next to it. This resolves the §7 risk row.
- **D8 and D9: duplicate instance ids wait instead of failing startup.** Client ids end in the instance id, and a consumer refuses to join while its group has a member with that client id. It logs an error and retries with backoff, so a pod restarted within the session timeout waits for its previous run to leave, rather than failing.
  - Static membership (`group.instance.id`) was rejected. It would have detected duplicates for free, but a static member doesn't leave its group on a clean shutdown, so a scale-down would hold its partitions for the 45s session timeout.
- **D10: ring ids are `p<partition>`, not `<partition>-<offset>`.** Event ids carry the offset (`p3-1200`). A tenant's partition is computed with the default partitioner's hash, so a ring's id is known before any line arrives.
  - **Resume rule.** A resume succeeds only when the ring holds an event at or before the client's last position. Otherwise the client gets `reset`, because positions between could belong to other tenants or to lines the ring never saw.
  - **Metadata warm-up.** The tail producer must fetch the topic's metadata before the first line: with `max.block.ms=0`, every send fails until it has the metadata.
- **D12: the drain runs on `ContextClosedEvent`, not as a lifecycle bean.** Lifecycle beans at the default phase stop before the web server does, so a bean couldn't stop pushes first.
  - It runs once, for the application context: the management server's child context publishes its own `ContextClosedEvent`.
  - The spool drain stops when a round makes no progress and no run is still in progress, so failing runs (for example, deleted spool files) end it early instead of at the timeout.
- **D13: two `kind` findings, both recorded in the manifests.**
  - **KEDA needs a fully qualified broker name.** KEDA runs in its own namespace, so the scaler (and the broker's advertised listener) need `kafka.<namespace>.svc.cluster.local`.
  - **Brokers need `auto.create.topics.enable=false`.** A producer's metadata request creates a missing topic with the broker's defaults: here `max.message.bytes` 1MB after the broker restarted while Argus was producing. Argus's topic check then refuses that topic (D6), as it should. Argus's consumers now also set `allow.auto.create.topics=false`.
  - S3Mock 4.x reads `initialBuckets`, not the `COM_ADOBE_…` names.
