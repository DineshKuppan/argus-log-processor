# P3 Plan: Push Ingest, Quotas, Export Sources and the Replay Lane

> Phase P3 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P0–P2 ([`P0_PLAN.md`](P0_PLAN.md), [`P1_PLAN.md`](P1_PLAN.md), [`HARDENING_PLAN.md`](HARDENING_PLAN.md), [`P2_PLAN.md`](P2_PLAN.md)).
> Exit criteria: **T8** (replay any range without starving live ingest: live p99 degrades ≤20%) and **tenant isolation** (one tenant over its quota or flooding a stream never gets another tenant a 429 or a slower response). P3a also re-runs T1, T3 and T4 through the push path.
> Ships as two PRs, like P1 and P2:
> - **P3a** (§2–§7): HTTP push ingest (`POST /api/v2/ingest/streams/{id}`), per-tenant ingest quotas, and suppression on the push path.
> - **P3b** (§8): Elasticsearch and Coralogix export sources, and the replay lane. Measures T8.

## 1. Goal and scope

Today Argus only pulls: a caller names a file and a job reads it. The target workload also arrives **pushed** by agents (Fluent Bit, Vector, OpenTelemetry Collector) and as **exports** from a centralized logging system (ELK, Coralogix). P3 adds both without a second pipeline: everything that isn't a local file is first made durable in a per-stream **spool**, and the existing P0–P2 pipeline ingests the spool like a file, with the same parsers, checkpoints, WAL, watermarks and archive.

| In scope (P3) | Out of scope |
|---|---|
| P3a: `POST /api/v2/ingest/streams/{streamId}`: NDJSON or plain lines, optional gzip, acknowledged once durable | OTLP/gRPC and msgpack (Fluent Bit `forward`); agents use their HTTP / NDJSON outputs |
| P3a: `Idempotency-Key`: a retried request isn't written twice | Exactly-once across clients that don't send a key |
| P3a: per-tenant ingest quota (bytes/s token bucket), local or Redis | Storage-retention classes, tail-connection quotas (P4) |
| P3a: suppression on the push path, at receive time | Suppression of file ingest (bypassed, guardrail 8) |
| P3a: stream status API; one canonical line per push request | Metrics (P4) |
| P3b: `ElasticsearchExportSource` (PIT + `search_after`), `CoralogixExportSource` (DataPrime, time windows) | Kafka source (P5) |
| P3b: replay lane: separate slots, rate limit, lower priority, suppression bypassed (T8) | Replaying from the Parquet archive back into the hot tier (P5) |

## 2. Decisions (STAR, per guardrail 10)

### D1. Pushed data goes to a durable raw spool; the existing pipeline ingests the spool
- **Situation:** the P0 pipeline (read → parse → write, bounded queues, segment commit, checkpoint, WAL, watermarks, archive) is built around `FileSource`: byte offsets give deterministic `eventId`s and resumable checkpoints. A push request is a few hundred KB of lines that must be acknowledged quickly and never lost after the acknowledgement.
- **Task:** accept pushed batches durably, with backpressure (429), at ≥4,150 lines/s (T1), without a second implementation of parsing, segmenting, checkpointing and archiving.
- **Action:** the options were:

  | Option | For | Against |
  |---|---|---|
  | (a) Parse in the request thread and append envelopes to an open WAL segment, fsync, ack | One write per line | A WAL segment must then survive a crash while still open: `LocalVolumeSink` discards `.part` files today, and would need a recoverable "acked length". Segments either stay open for minutes or become tiny (one Parquet object per request) |
  | (b) Kafka / an embedded queue in front of the pipeline | The P5 shape | A broker to run on a 4GB node; P5 keeps it optional |
  | **(c) Append the raw request body to a per-stream spool file (fsync, then ack); a stream runner ingests the spool with the existing pipeline** | Reuses everything: `FileSource` offsets give `eventId`s, the pipeline batches minutes of pushes into one segment and one Parquet object | Data is written twice (spool, then WAL); the raw spool is ≈1x the input and is deleted once ingested |

  Chosen (c), as in Fluent Bit's filesystem buffer and Vector's disk buffer.
  - **Spool:** `spool-dir/tenant=T/stream=S/NNNNNN.log`, numbered. A request appends its lines under the stream's lock, always ending in `\n`, then `force()`s the file before answering.
  - **Visible length:** the stream runner reads only up to the stream's acknowledged length (`FileSource` gains an explicit limit), never into an append in progress. An append that fails part-way (disk full, client gone) is truncated back before the lock is released. At startup, a spool's visible length is its size up to the last `\n`.
  - **Stream runner:** a stream is a source (`sourceId` = stream id) whose runs are ordinary `IngestPipeline` runs over the spool. A run starts when the stream has `segment-bytes` waiting or its oldest waiting byte is `flush-interval` (1 minute) old. So a stream at 120KB/s writes one ~7MB segment a minute, not one per request.
  - **Rotation:** once a spool file is fully checkpointed and over `spool-rotate-bytes` (256MB), the runner deletes it and the next append starts `NNNNNN+1.log`. A spool's number is part of its identity: a run on a spool whose path differs from the checkpointed one starts the next generation at 0, so the rotation never depends on inode comparison (inodes can be reused).
- **Result:**
  - Guardrails 1–3 and 6 hold unchanged for push: bounded memory, a durable copy before the acknowledgement, deterministic `eventId = (tenant, stream, generation, spool offset)`, and the WAL watermarks from P2b.
  - The cost is one extra sequential write of the raw bytes, about 120KB/s at the 10GB/day target, plus up to `flush-interval` of extra latency before data reaches the WAL. P5's hot tier (T5, ≤30s) will need a shorter interval.

### D2. Acknowledge after fsync; `Idempotency-Key` makes retries safe
- **Situation:** agents retry when a response is lost. Without help, a retried batch gets new spool offsets, so new `eventId`s, so real duplicates in the archive, which T4 forbids.
- **Task:** make a retry of an acknowledged request a no-op, durably, without a database.
- **Action:** an optional `Idempotency-Key` header (the IETF httpapi draft's name). Each accepted request appends one line to the stream's **spool index** (`NNNNNN.idx`: key, start, end, received-at) and fsyncs it after the spool. A request whose key is among the stream's last `idempotency-window` (1,024) keys is answered `200` with the original offsets and writes nothing. The index is also the record of each stream's acknowledged length.
- **Result:** with a key, a retry is exact even across a restart; without one, delivery stays at-least-once. Fluent Bit and Vector don't send a key by default; Argus documents a header for each. Cost: a second fsync per request (≈1ms on SSD; ~10 requests/s at the target).

### D3. Suppression happens at the edge, on receive time
- **Situation:** P1b's `Suppressor` answers allow/deny for one message per tenant per 10s window, using the time of the check (guardrail 8). The stream runner processes a minute of spool in well under a second, so checking there would treat copies 50s apart as being inside one 10s window.
- **Task:** suppress repeated messages on the push path with the right clock, without slowing the acknowledgement much.
- **Action:** the request handler parses each line with the stream's parser chain (format requested with `format`, or detected from the stream's first request and kept), checks its message with the `Suppressor`, and appends only allowed lines to the spool. The pipeline later parses the spool again; parsing is ~2.5µs a line, so the cost at 4k lines/s is about 1% of a core.
- **Result:**
  - Suppression uses receive time and answers immediately. The response reports `suppressed`.
  - The spool holds only allowed lines, so a crash replay, a re-run or a replay-lane run never suppresses again (guardrail 8).
  - `argus.push.suppress=false` turns it off; with the Redis store it fails open, as today.

### D4. Every push boundary is bounded and says what it does when full
| Boundary | Limit | When full |
|---|---|---|
| Concurrent push requests (heap) | `max-concurrent-requests` (16) × `max-request-bytes` (8MB decompressed) ≈ 128MB | 429, `Retry-After: 1` |
| Request body | 8MB after gzip (guards against gzip bombs); 10,000 lines (guardrail 1) | 413 |
| One line | `max-line-bytes` (1MB) | Truncated and counted, as for files |
| Tenant quota | token bucket (D5) | 429, `Retry-After` = seconds until the request fits |
| WAL (P2b) | high watermark | 429, `Retry-After: 60` |
| Spool | `max-spool-bytes` (2GB) across all streams | 429, `Retry-After: 30` |
| Streams | `max-streams-per-tenant` (32) | 409 for a new stream id |
| Stream runs | `max-concurrent-runs` (2) | Runs wait; their spool absorbs it |

The checks run cheapest first and before the body is read, except the ones that need its size, so a rejected request costs almost nothing.

### D5. Quotas: a token bucket per tenant; local by default, Redis for several instances
- **Situation:** one tenant's flood must not fill the spool or the WAL for everyone. The design (§7) names a Redis token bucket. The sizing target is one node.
- **Action:** `QuotaStore` port, wired like `SuppressionStore`:
  - **Local (default):** a bucket per tenant (`ingest-bytes-per-second`, burst `burst-seconds` × rate), refilled lazily on each check. Bounded by the number of configured tenants.
  - **Redis:** one Lua call per request (refill + take, `PX` expiry), key `<tenant>:quota:ingest`. **Fails open** on Redis errors, with one warning per window, like suppression.
  - Limits are in decompressed bytes. `argus.quota.ingest-bytes-per-second` (default 2MB/s, ≈16x the 10GB/day average) and per-tenant overrides `argus.quota.tenants.<id>.ingest-bytes-per-second`.
- **Result:** a tenant over quota gets 429 with an exact `Retry-After`. Quotas apply to push only; file and export jobs are limited by job slots (and in P3b by the replay lane).

### D6. Stay on Java 17 for P3; revisit at P4
- **Situation:** ADR-6 recommended Java 21 "before P3" for virtual threads under HTTP ingest and many tail connections. JDK 21 is available.
- **Action:** push ingest is ~10 requests/s at the target, each spending ~1–2ms in fsync: Tomcat's 200 platform threads and the 16-request bound (D4) are far from the limit. On 21, virtual threads still pin their carrier inside `synchronized` (fixed in 24), and the pipeline and stores use `synchronized`. Live tail (P4) is where thousands of mostly idle connections make virtual threads matter.
- **Result:** no build change in P3. ADR-6 is updated to "before P4".

## 3. Components (P3a)

| Component | Package | Role |
|---|---|---|
| `StreamController` | `controller` | `POST /api/v2/ingest/streams/{streamId}` (lines, `Content-Encoding: gzip`, `Idempotency-Key`, `format`, `pattern`), `GET /streams/{id}`, `GET /streams`. 202 `{accepted, suppressed, truncated, startOffset, endOffset}`; 200 for a duplicate key |
| `PushIngestService` | `ingest` | The admission checks of D4 in order, decode and line splitting, suppression (D3), append, canonical line `Push request finished …` |
| `StreamSpool` | `source` | One stream's numbered spool files and index: locked append + fsync, visible length, idempotency window, rotation, startup recovery |
| `StreamRunner` | `ingest` | Schedules spool runs (size or age), shares source exclusivity with `IngestJobService`, deletes ingested spools |
| `FileSource` | `source` | Gains an explicit read limit (the spool's visible length) |
| `IngestPipeline.Request` | `ingest` | Gains `limit` and "path is part of the identity" (D1 rotation) |
| `QuotaStore`, `LocalQuotaStore`, `RedisQuotaStore`, `QuotaService` | `quota` (new) | D5 |
| `PushProperties`, `QuotaProperties`, `PushConfig`, `QuotaConfig` | `config` | Binding and wiring |
| `JsonLogParser` | `parser` | Adds `date` (Fluent Bit's `json_date_key`, epoch seconds) to the timestamp keys |

## 4. Configuration

```properties
argus.push.spool-dir=./data/spool
argus.push.max-request-bytes=8MB          # after gzip
argus.push.max-concurrent-requests=16
argus.push.flush-interval=1m              # oldest spooled byte waits at most this long for a run
argus.push.max-spool-bytes=2GB
argus.push.spool-rotate-bytes=256MB
argus.push.max-streams-per-tenant=32
argus.push.max-concurrent-runs=2
argus.push.idempotency-window=1024        # keys remembered per stream
argus.push.suppress=true

argus.quota.store=local                   # local | redis
argus.quota.ingest-bytes-per-second=2MB
argus.quota.burst-seconds=2
# argus.quota.tenants.acme.ingest-bytes-per-second=10MB
```

Disk budget (§2 of the design): WAL 30GB + Parquet staging ≤2GB + spool ≤2GB + checkpoints, within the 100GB SSD.

## 5. Tests (P3a)

| Kind | Test |
|---|---|
| Unit | `StreamSpoolTest`: append + visible length, a failed append truncated back, recovery at startup (a torn tail without `\n`), rotation and numbering, the idempotency window across a restart |
| Unit | `PushIngestServiceTest`: every D4 check and its status code, gzip, a gzip bomb (413), lines over the cap, missing final `\n`, suppression counts |
| Unit | `LocalQuotaStoreTest` (refill, burst, exact `Retry-After`, fake clock); `RedisQuotaStoreTest` (Testcontainers) and fail-open |
| Unit | `FileSourceTest` (explicit limit), `JsonLogParser` with Fluent Bit records |
| Pipeline | `StreamRunnerTest`: a size-triggered and an age-triggered run, a rotated spool starts the next generation, no run while a file job holds the source |
| API | `StreamControllerTest`: 401 without a key, another tenant's stream is 404, a duplicate `Idempotency-Key` is 200 with the original offsets |
| Isolation | Tenant A over quota and filling its streams; tenant B's requests stay 202 at an unchanged p99 |
| Load (`load` tag) | `PushIngestIT`: N concurrent clients posting gzip NDJSON at a 256MB heap: lines/s (T1), request p99, heap peak |
| Load | `PushBackpressureIT`: the archive store down during a push load → 429s, no OOM, everything accepted is archived after recovery (T3 for push) |
| Chaos | `PushCrashIT`: `kill -9` the app during a push load; every acknowledged line is in the output exactly once with keys, at least once without (T4 for push) |

## 6. Implementation order (P3a, one commit per step)
1. The plan (this file) and an ADR-6 update.
2. `FileSource` limit, `Request` identity option, `JsonLogParser` `date`.
3. `StreamSpool` + tests.
4. `quota` package (local + Redis) + tests.
5. `PushIngestService`, `StreamRunner`, `StreamController`, configuration + tests.
6. Load, backpressure and crash tests; measurements in §10.
7. Docs: CLAUDE.md, README, FEATURES, ROADMAP, CHANGELOG, design doc §4/§5/§7.

## 7. Risks
| Risk | Mitigation |
|---|---|
| Lines from one request split across two runs | A run reads only to the visible length, which always ends on a request's final `\n` |
| A stream whose format changes mid-way | Per-line fallback (P1) still parses it; `format` can be fixed per request |
| Many tiny streams → many tiny Parquet objects | `flush-interval` and `segment-bytes` batch them; `max-streams-per-tenant` caps the count |
| Spool and WAL on one SSD double the write load | ≈120KB/s extra at the target; measured in `PushIngestIT` |
| Receive time vs `observedTimestamp` | `observedTimestamp` stays the pipeline's time (up to `flush-interval` later); the index records receive time for a later fix |

## 8. P3b: export sources and the replay lane (second PR)

### D7. Export sources write to a spool too
- **Situation:** ELK and Coralogix exports are pulled page by page. Their cursors (PIT + `search_after` sort values; a Coralogix time window) are the offsets of §5 of the design.
- **Action:** an export job pages through the remote system and appends each page to a stream spool exactly as a push request would, using `hash(query, cursor)` as its `Idempotency-Key` (D2), and saves the cursor only after the page is in the spool. A crash re-fetches at most one page, and the key makes the re-append a no-op.
  - `ElasticsearchExportSource`: point-in-time + `search_after`, `_doc`-tiebreaker sort, page size 5,000; `_source` becomes the line. Tested with Testcontainers (OpenSearch and Elasticsearch images).
  - `CoralogixExportSource`: the DataPrime query API over `java.net.http`, one query per time window, halving a window that hits the result limit. Tested against a protocol stub, like GCS; the API is re-checked against Coralogix's docs when built.
- **Result:** one path from "bytes arrived" to "archived" for files, pushes and exports.

### D8. The replay lane
- **Situation:** T8: a backfill or replay must not starve live ingest. Today every job shares `max-concurrent-jobs`.
- **Action:** jobs carry a `lane` (`live` | `replay`). `fromBeginning` file jobs and export jobs default to `replay`. The replay lane has its own slots (`argus.replay.max-concurrent-jobs`, 1), a read rate limit (`argus.replay.bytes-per-second`), and lower-priority threads, and it bypasses suppression (guardrail 8). Live pushes and stream runs never wait for a replay slot.
- **Result:** measured by `ReplayIsolationIT`: push p99 with and without a 1GB replay running; target ≤20% worse.

## 9. Results (P3a)
To be filled in after the build.
