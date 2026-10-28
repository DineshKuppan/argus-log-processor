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
| **P2b** | GCS, WAL watermarks, WAL compression | ⏭ Next |
| **P3** | HTTP push ingest, quotas, export sources, replay lane | Planned |
| **P4** | Live tail, metrics | Planned |
| **P5** | OpenSearch hot tier, optional Kafka | Planned |

## Acceptance criteria

| # | Criterion | Target | Status |
|---|---|---|---|
| T1 | Sustained throughput | ≥4,150 lines/s on one 4GB node | ✅ ~380–440k lines/s ingest (1GB test) |
| T2 | Memory | Heap ≤2.4GB, no OOM on a 10GB file | ✅ 810MB peak (P0) |
| T3 | Backpressure | Sink stalled for 10 min → no OOM, sources paused or 429, WAL under its watermark, full recovery | ⏭ P2b (watermarks) |
| T4 | Durability | `kill -9` at a random point → no lost records, duplicates only with the same `eventId` | ✅ P0 |
| T5 | Freshness | p99 ingest-to-searchable ≤30s | P5 |
| T6 | Suppression | Correct at the 10s boundary; p99 <1ms | ✅ 8.8µs |
| T7 | Live tail | Visible ≤2s after ingest; a slow client can't slow ingest | P4 |
| T8 | Replay | Replay any range without starving live ingest | P3 |
| T9 | Cost | Cold tier ≤3TiB at 7 years (≥8x compression) | ✅ 14.6x on synthetic data; re-measure on real logs |

## Next: P2b
Details: [`P2_PLAN.md`](docs/architecture/P2_PLAN.md) §8.
- **Native GCS store:** `google-cloud-storage` with resumable uploads and Workload Identity, tested against `fake-gcs-server`. Until then, GCS works only through its S3 interop API.
- **WAL watermarks (T3):**
  - The WAL is capped (30GB).
  - Above 80%, ingest **pauses** instead of failing, and job status shows `PAUSED_WAL_FULL`.
  - Below 60%, ingest resumes.
- **WAL compression:** `.ndjson.zst` segments, about 10x smaller, so the WAL holds days of backlog instead of hours.
- **T3 test:** a store that fails for minutes while a large file ingests.

## Later
- **P3: push ingest and tenancy at scale.**
  - `HttpIngestSource` (NDJSON / gzip batch POST, 429 backpressure), with suppression on that path.
  - Per-tenant quotas and rate limits.
  - Elasticsearch / Coralogix export sources.
  - A lower-priority replay lane (T8).
- **P4: visibility.**
  - Server-Sent Events live tail with per-connection bounded buffers that drop the oldest lines (T7).
  - Micrometer metrics.
  - Canonical lines for the applications Argus ingests.
- **P5: hot search tier.**
  - `OpenSearchSink` with ILM and tuned mappings, under a multi-sink router that acknowledges each WAL segment per sink (T5).
  - Optional Kafka buffer, with suppression partitioned by key.

## Open items
- **Re-measure T9 on real logs.** The 14.6x comes from `DataSeeder`, whose vocabulary is small. Real logs will compress less.
- **Test against AWS S3** and GCS's S3 interop. Only S3Mock is tested today.
- **Health without Redis:** `/actuator/health` reports DOWN when Redis is unreachable, even with the local suppression store. Disable the Redis indicator unless the Redis store is in use.
- **Multiline framing:** stack traces become one record per line. A `framing` stage would join continuation lines.
- **Records without an event time** land in `dt=unknown`, and v1's time filters skip them.
- **v1 `/parse`, `/parse/regexp` and `/filter`** still read whole files behind the 50MB cap.
- **API keys:** static only. There's no rotation API, expiry or identity provider yet.
- **One archiver per WAL volume.** Several instances sharing one volume isn't supported.
- **Java 21** before P3, for virtual threads and many concurrent tail connections (ADR-6).
