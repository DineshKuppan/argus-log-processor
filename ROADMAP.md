# Roadmap

Where Argus is heading, phase by phase. The reasoning behind each phase (capacity maths, ADRs, guardrails) is in [`docs/architecture/SCALING_DESIGN.md`](docs/architecture/SCALING_DESIGN.md) §12. Every new stage, store or format gets a short STAR decision record before it is built.

**Target:** 10GB/day (~36M lines, ~4k lines/s peak) from files, streams and centralized-logging exports, on one node with 4GB RAM and a 100GB SSD. Hot tier for days 1–14, cold tier for years 1–7.

## Status

| Phase | Scope | Status |
|---|---|---|
| **P0** | Streaming file source, checkpoints, local sink, bounded queues | ✅ Done (#1) |
| **P1a** | Parser registry, format detection, `LogEnvelope` | ✅ Done (#2) |
| **P1b** | 10s suppression API, streaming aggregation | ✅ Done (#3) |
| **Hardening** | API-key tenancy, v1 path restriction | ✅ Done (#4) |
| **P2a** | Parquet + ZSTD archive to a PVC or S3; local segments become the WAL | ✅ Done (#5) |
| **P2b** | GCS, WAL watermarks, WAL compression | ✅ Done (#7) |
| **P3a** | HTTP push ingest, quotas, suppression on push | ✅ Done (#8) |
| **P3b** | Export sources (Elasticsearch, Coralogix), replay lane | ✅ Done (#9) |
| **P4** | Live tail, metrics, producers' canonical lines | ✅ Done (#10) |
| **P5** | OpenSearch / Elasticsearch hot tier, search API | ✅ Done (#11) |
| **P6a** | Kafka or Redpanda as the WAL; the archive and hot tier as consumer groups; lag watermarks | ✅ Done (#12) |
| **P6b** | Several instances: instance ids, shared consumer groups, shared tail, Redis suppression pipelined, autoscaling on lag | ✅ Done |

## Acceptance criteria

| # | Criterion | Target | Status |
|---|---|---|---|
| T1 | Sustained throughput | ≥4,150 lines/s on one 4GB node | ✅ ~380–440k lines/s file ingest (1GB test); 132k lines/s pushed over HTTP end to end at a 256MB heap; the same with Kafka or Redpanda as the WAL (130–132k, P6a) |
| T2 | Memory | Heap ≤2.4GB, no OOM on a 10GB file | ✅ 810MB peak (P0) |
| T3 | Backpressure | Sink stalled for 10 min → no OOM, sources paused or 429, WAL under its watermark, full recovery | ✅ Scaled tests: a 30s outage during a 500MB file ingest paused ingest within one segment of the watermark; a 20s outage during push load turned into 429s, and all 1M acknowledged lines were archived afterwards (both at a 256MB heap). With Kafka (P6a): a 60s broker outage was absorbed by the spools; an archive outage paused push at the lag watermark (429 `WAL_FULL`); every line archived exactly once afterwards |
| T4 | Durability | `kill -9` at a random point → no lost records, duplicates only with the same `eventId` | ✅ P0 (files); P3a (push with `Idempotency-Key`: no loss, one `eventId` per line); P6a (Kafka and Redpanda: 400,000 of 400,000 archived once); P6b (one of three instances killed: 300,000 of 300,000 in both tiers) |
| T5 | Freshness | p99 ingest-to-searchable ≤30s | ✅ 2 CPUs, 4,150 lines/s pushed: p50 11.8s, p99 20.1s from the 202 to the search API finding the line (OpenSearch 2.17; Elasticsearch 8.15 on the host: p99 17.8s); Kafka as the WAL, host: p99 22.3s |
| T6 | Suppression | Correct at the 10s boundary; p99 <1ms | ✅ 8.8µs |
| T7 | Live tail | Visible ≤2s after ingest; a slow client can't slow ingest | ✅ 2 CPUs: push-to-tail p99 39ms; 16 tail clients that never read cost push p99 no more than 16 that read (within 7%, 4 runs), throughput unchanged; 1,000 connections at a 256MB heap |
| T8 | Replay | Replay any range without starving live ingest | ✅ 2 CPUs: live push p99 unchanged within noise during a 1GB replay at 8MB/s (3–8x worse without the lane) |
| T9 | Cost | Cold tier ≤3TiB at 7 years (≥8x compression) | ✅ 14.6x on synthetic data; re-measure on real logs |

## Next
P6 is done: Kafka as the WAL (P6a, ADR-8) and several instances (P6b). What remains is under "Open items", below.

## Open items
- **Re-measure T9 on real logs.** The 14.6x comes from `DataSeeder`, whose vocabulary is small. Real logs will compress less.
- **Test against real AWS S3 and GCS.** Only S3Mock and fake-gcs-server (plus a protocol stub for GCS resumes) are tested today.
- **Run T3 at full length:** the 10-minute outage is tested scaled down to 30s (`-Dargus.load.outage-seconds`).
- **Multiline framing:** stack traces become one record per line. A `framing` stage would join continuation lines.
- **Records without an event time** land in `dt=unknown`, and v1's time filters skip them.
- **v1 `/parse`, `/parse/regexp` and `/filter`** still read whole files behind the 50MB cap.
- **API keys:** static only. There's no rotation API, expiry or identity provider yet.
- **One archiver per WAL volume.** Several instances sharing one volume isn't supported.
- **Push from real agents:** the push tests use an HTTP client; run Fluent Bit and Vector against Argus, including how they react to 429 and `Retry-After`.
- **Exactly-once for agents:** Fluent Bit's and Vector's HTTP outputs send only static headers, so they can't send a per-batch `Idempotency-Key`; their retries are at-least-once.
- **Push freshness:** pushed lines wait up to `argus.push.flush-interval` (1 minute) before a run; P5's hot tier (T5, ≤30s) uses a 10s flush while a hot store is configured (P5_PLAN D7). `observedTimestamp` is the run's time, not the receive time.
- **Exports against the real services:** Coralogix is tested against a protocol stub only; run an export against a Coralogix account, and against a production-sized Elasticsearch / OpenSearch index.
- **Exactly-once Elasticsearch exports across an expired point in time:** hits sharing the last exported timestamp can be exported twice. A stable unique sort field (when the index has one) would make it exact.
- **Byte-range replay:** a replay re-reads a whole file (`fromBeginning`) or an export's time range; there is no "re-ingest offsets X–Y of this file".
- **Tail warm-up:** a restart empties the tail rings; pre-fill them from the newest WAL segments so a reconnect resumes instead of getting `reset`.
- **Hot tier on a managed cluster:** tested against OpenSearch 2.17 and Elasticsearch 8.15 containers only; add AWS SigV4 for Amazon OpenSearch Service with IAM auth.
- **Search the cold tier:** ranges older than the hot window (14 days) are only in Parquet; add Athena / BigQuery queries (asynchronous) behind the search API.
- **Typed attributes in the hot tier:** attribute values are indexed as text; promote configured keys to numeric fields for ranges and aggregations.
- **Coalesce small segments in the archive:** with a hot store, streams run every 10s, so the archive gets 6x more (smaller) Parquet objects; merge a source's segments into one object per few minutes or 64MB.
- **Hot indexer fairness:** one thread, oldest segment first, so one tenant's backlog delays the others' freshness; and a hot-tier outage longer than the WAL's room pauses ingest (a lag limit past which the hot copy is skipped is an option).
- **Re-measure the mapping saving on real logs:** 65.6% (OpenSearch) / 37.4% (Elasticsearch) on synthetic lines.
- **Dead tail connections behind proxies:** a proxy that keeps a closed client's server side open holds its tail slot until `max-duration` (seen with Docker Desktop's port proxy); add an idle limit or reclaim the oldest slot at the tenant's limit.
- **Browser tail clients:** `EventSource` can't send the API key header; a fetch-based SSE client is needed (or short-lived tail tokens).
- **Virtual threads for tail connections** once the build is on Java 24+ (JEP 491; [`P4_PLAN.md`](docs/architecture/P4_PLAN.md) D3).
- **Dashboards and alerts:** P4 exports the meters; ship example Grafana dashboards and alert rules (WAL above the high watermark, archive lag, 429 rate).
- **Replay budget per node size:** 8MB/s is measured on 2 CPUs; derive it from the CPU count, or pace by lines rather than bytes.
