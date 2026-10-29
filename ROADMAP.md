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
| **P3a** | HTTP push ingest, quotas, suppression on push | ✅ Done |
| **P3b** | Export sources (Elasticsearch, Coralogix), replay lane | ⏭ Next |
| **P4** | Live tail, metrics | Planned |
| **P5** | OpenSearch hot tier, optional Kafka | Planned |

## Acceptance criteria

| # | Criterion | Target | Status |
|---|---|---|---|
| T1 | Sustained throughput | ≥4,150 lines/s on one 4GB node | ✅ ~380–440k lines/s file ingest (1GB test); 132k lines/s pushed over HTTP end to end at a 256MB heap |
| T2 | Memory | Heap ≤2.4GB, no OOM on a 10GB file | ✅ 810MB peak (P0) |
| T3 | Backpressure | Sink stalled for 10 min → no OOM, sources paused or 429, WAL under its watermark, full recovery | ✅ Scaled tests: a 30s outage during a 500MB file ingest paused ingest within one segment of the watermark; a 20s outage during push load turned into 429s, and all 1M acknowledged lines were archived afterwards (both at a 256MB heap) |
| T4 | Durability | `kill -9` at a random point → no lost records, duplicates only with the same `eventId` | ✅ P0 (files); P3a (push with `Idempotency-Key`: no loss, one `eventId` per line) |
| T5 | Freshness | p99 ingest-to-searchable ≤30s | P5 |
| T6 | Suppression | Correct at the 10s boundary; p99 <1ms | ✅ 8.8µs |
| T7 | Live tail | Visible ≤2s after ingest; a slow client can't slow ingest | P4 |
| T8 | Replay | Replay any range without starving live ingest | P3 |
| T9 | Cost | Cold tier ≤3TiB at 7 years (≥8x compression) | ✅ 14.6x on synthetic data; re-measure on real logs |

## Next: P3b
Exports and replay. Plan: [`P3_PLAN.md`](docs/architecture/P3_PLAN.md) §8.
- **Export sources:** Elasticsearch (PIT + `search_after`) and Coralogix (DataPrime, time windows). Each page goes into a stream spool with `hash(query, cursor)` as its `Idempotency-Key`, so a crash re-fetches at most one page and never ingests it twice.
- **Replay lane** (T8): `fromBeginning` file jobs and exports run on their own slots, rate-limited, on lower-priority threads and without suppression, so live push p99 degrades ≤20%.

## Later
- **P4: visibility.**
  - Server-Sent Events live tail with per-connection bounded buffers that drop the oldest lines (T7).
  - Micrometer metrics.
  - Canonical lines for the applications Argus ingests.
- **P5: hot search tier.**
  - `OpenSearchSink` with ILM and tuned mappings, under a multi-sink router that acknowledges each WAL segment per sink (T5).
  - Optional Kafka buffer, with suppression partitioned by key.

## Open items
- **Re-measure T9 on real logs.** The 14.6x comes from `DataSeeder`, whose vocabulary is small. Real logs will compress less.
- **Test against real AWS S3 and GCS.** Only S3Mock and fake-gcs-server (plus a protocol stub for GCS resumes) are tested today.
- **Run T3 at full length:** the 10-minute outage is tested scaled down to 30s (`-Dargus.load.outage-seconds`).
- **Health without Redis:** `/actuator/health` reports DOWN when Redis is unreachable, even with the local suppression store. Disable the Redis indicator unless the Redis store is in use.
- **Multiline framing:** stack traces become one record per line. A `framing` stage would join continuation lines.
- **Records without an event time** land in `dt=unknown`, and v1's time filters skip them.
- **v1 `/parse`, `/parse/regexp` and `/filter`** still read whole files behind the 50MB cap.
- **API keys:** static only. There's no rotation API, expiry or identity provider yet.
- **One archiver per WAL volume.** Several instances sharing one volume isn't supported.
- **Push from real agents:** the push tests use an HTTP client; run Fluent Bit and Vector against Argus, including how they react to 429 and `Retry-After`.
- **Exactly-once for agents:** Fluent Bit's and Vector's HTTP outputs send only static headers, so they can't send a per-batch `Idempotency-Key`; their retries are at-least-once.
- **Push freshness:** pushed lines wait up to `argus.push.flush-interval` (1 minute) before a run; P5's hot tier (T5, ≤30s) needs a shorter interval. `observedTimestamp` is the run's time, not the receive time.
- **Java 21** before P4, for virtual threads and many concurrent tail connections (ADR-6; P3 stays on 17, [`P3_PLAN.md`](docs/architecture/P3_PLAN.md) D6).
