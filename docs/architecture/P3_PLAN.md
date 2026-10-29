# P3 Plan: Push Ingest, Quotas, Export Sources and the Replay Lane

> Phase P3 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P0–P2 ([`P0_PLAN.md`](P0_PLAN.md), [`P1_PLAN.md`](P1_PLAN.md), [`HARDENING_PLAN.md`](HARDENING_PLAN.md), [`P2_PLAN.md`](P2_PLAN.md)).
> Exit criteria: **T8** (replay any range without starving live ingest: live p99 degrades ≤20%) and **tenant isolation** (one tenant over its quota or flooding a stream never gets another tenant a 429 or a slower response). P3a also re-runs T1, T3 and T4 through the push path.
> Ships as two PRs, like P1 and P2:
> - **P3a** (§2–§7): HTTP push ingest (`POST /api/v2/ingest/streams/{id}`), per-tenant ingest quotas, and suppression on the push path.
> - **P3b** (§8, results in §10): Elasticsearch and Coralogix export sources, and the replay lane. Measures T8.

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
  - **Spool:** `spool-dir/tenant=T/stream=S/NNNNNN.log`, numbered, plus one `index.log` per stream (D2). A request appends its lines under the stream's lock, always ending in `\n`, then `force()`s the file, then its index entry, before answering.
  - **Visible length:** the stream runner reads only up to the stream's acknowledged length (`FileSource.startBetween`), never into an append in progress. An append that fails part-way (disk full, client gone) is truncated back before the lock is released. At startup, bytes of the newest spool file past its last index entry belonged to a request that was never acknowledged, and are truncated.
  - **Stream runner:** a stream is a source (`sourceId` = stream id) whose runs are ordinary `IngestPipeline` runs over one spool file each. A run starts when the stream has `segment-bytes` waiting, a sealed spool file waiting, or its oldest waiting byte is `flush-interval` (1 minute) old. So a stream at 120KB/s writes one ~7MB segment a minute, not one per request. Runs and file jobs share `SourceLeases`, so a source has at most one of either at a time.
  - **Rotation:** a spool file is sealed once it reaches `spool-rotate-bytes` (64MB), or once it is fully read and at least 1MB (so an idle stream frees its disk); the next append starts `NNNNNN+1.log`, and a sealed file is deleted once fully read. Numbers are never reused. A spool's number is part of its identity: a run on a spool whose path differs from the checkpointed one starts the next generation at 0 (`ResumePoint.Reason.PATH_CHANGED`), so rotation never depends on inode comparison (inodes can be reused).
- **Result:**
  - Guardrails 1–3 and 6 hold unchanged for push: bounded memory, a durable copy before the acknowledgement, deterministic `eventId = (tenant, stream, generation, spool offset)`, and the WAL watermarks from P2b.
  - The cost is one extra sequential write of the raw bytes, about 120KB/s at the 10GB/day target, plus up to `flush-interval` of extra latency before data reaches the WAL. P5's hot tier (T5, ≤30s) will need a shorter interval.

### D2. Acknowledge after fsync; `Idempotency-Key` makes retries safe
- **Situation:** agents retry when a response is lost. Without help, a retried batch gets new spool offsets, so new `eventId`s, so real duplicates in the archive, which T4 forbids.
- **Task:** make a retry of an acknowledged request a no-op, durably, without a database.
- **Action:** an optional `Idempotency-Key` header (the IETF httpapi draft's name). Each accepted request appends one line to the stream's **index** (`index.log`, spanning its spool files: spool number, start, end, key, received-at) and fsyncs it after the spool. A request whose key is among the stream's last `idempotency-window` (1,024) keys is answered `200` with the original offsets and writes nothing. The index is also the record of each spool file's acknowledged length. It is compacted (temp file + fsync + atomic rename) to the remembered keys plus the last entry of each spool file on disk once it holds about twice the window.
- **Result:** with a key, a retry is exact even across a restart (`PushCrashIT`). Without one, delivery stays at-least-once. Fluent Bit's and Vector's HTTP outputs send only static headers, so they can't send a per-batch key: for them a retried batch can be ingested twice. A client that can (an SDK, the P3b export jobs) gets exactly-once. Cost: a second fsync per request (≈1ms on SSD; ~10 requests/s at the target).

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
| Spool | `max-spool-bytes` (2GB) of acknowledged, not yet ingested bytes across all streams | 429, `Retry-After: 30` |
| Streams | `max-streams-per-tenant` (32) | 409 for a new stream id (checked after every other bound, so a rejected request doesn't create its stream) |
| One request per `Idempotency-Key` | a retry racing its original | 429, `Retry-After: 1`; then 200 from the original |
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
| `StreamRunner` | `ingest` | Schedules spool runs (size, sealed file or age), shares source exclusivity with `IngestJobService` through `SourceLeases`, deletes ingested spools |
| `StreamRegistry`, `PushStream` | `ingest` | Each tenant's streams and their open spools; reopens every spool under `spool-dir` at startup |
| `FileSource` | `source` | Gains an explicit read limit (`startBetween`: the spool's visible length) |
| `IngestPipeline.Request` | `ingest` | Gains `limit` and "path is part of the identity" (D1 rotation) |
| `QuotaStore`, `LocalQuotaStore`, `RedisQuotaStore`, `QuotaService` | `quota` (new) | D5 |
| `PushProperties`, `QuotaProperties`, `PushConfig`, `QuotaConfig` | `config` | Binding and wiring |
| `JsonLogParser` | `parser` | Fractional epoch seconds keep their fraction (Fluent Bit's `json_date_format double`; its `json_date_key` is set to `timestamp`, since a generic `date` key would turn business fields into event times) |

## 4. Configuration

```properties
argus.push.spool-dir=./data/spool
argus.push.max-request-bytes=8MB          # after gzip
argus.push.max-request-lines=10000
argus.push.max-concurrent-requests=16
argus.push.flush-interval=1m              # oldest spooled byte waits at most this long for a run
argus.push.max-spool-bytes=2GB            # acknowledged, not yet ingested
argus.push.spool-rotate-bytes=64MB
argus.push.max-streams-per-tenant=32
argus.push.max-concurrent-runs=2
argus.push.idempotency-window=1024        # keys remembered per stream
argus.push.suppress=true

argus.quota.store=local                   # local | redis
argus.quota.ingest-bytes-per-second=2MB
argus.quota.burst-seconds=2
# argus.quota.tenants.acme.ingest-bytes-per-second=10MB
```

Disk budget (§2 of the design): WAL 30GB + Parquet staging ≤2GB + spool ≤2GB not yet ingested (plus at most one already-ingested 64MB file per stream) + checkpoints, within the 100GB SSD.

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
2. `FileSource` limit, `Request` identity option, `JsonLogParser` fractional epoch seconds.
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
- **Situation:** ELK and Coralogix exports are pulled page by page. Their cursors (PIT + `search_after` sort values; a Coralogix time window) are the offsets of §5 of the design. A crash in the middle of a multi-hour export must neither lose nor duplicate a page.
- **Task:** export a time range `[from, to)` from either system into the ordinary pipeline, resumable after a crash or restart, without a second implementation of parsing, segments, checkpoints and archive.
- **Action:** an export job pages through the remote system and appends each page to a stream spool, as a push request would (D1). Its `Idempotency-Key` (D2) is `sha256(export, run, page)`, where *page* is the deterministic part of the cursor the page was fetched from (not the PIT id, which changes). The cursor is saved (temp file + fsync + rename, `checkpoint-dir/tenant=T/_export/<stream>.json`) only after the page is in the spool, so a crash re-fetches at most one page and its re-append is answered from the index. Re-submitting the same export (same source, query and range) on the same stream resumes from the cursor; `fromBeginning` starts a new run (a new run id, so new keys); a different export on that stream starts over. Each page is one canonical line, `Export page finished …`.
  - **`ElasticsearchExportSource`** (`java.net.http`, no client library): a point in time over the source's index, `range` on the timestamp field, sorted ascending, `size` = page size (5,000), `_source` (compact JSON) becomes the line. Elasticsearch and OpenSearch differ, so the flavour is read from `GET /` (probed on 8.15 and 2.17):

    | | Elasticsearch 8 | OpenSearch 2 |
    |---|---|---|
    | Open / close a PIT | `POST /<index>/_pit`, `DELETE /_pit` | `POST /<index>/_search/point_in_time`, `DELETE /_search/point_in_time` |
    | Tiebreaker | implicit `_shard_doc` (sorting on `_id` is disabled) | none; `_shard_doc` is unknown, so the sort adds `_id` |
    | Timestamp sort value | ISO text (sort `format`, keeps nanoseconds) | epoch millis (sort `format` is rejected) |

    A PIT expires after `keep-alive` (10m) without a request, e.g. while the job is paused. Both answer `search_context_missing_exception`; the job opens a new PIT and continues. On OpenSearch `(timestamp, _id)` is stable across PITs, so it continues exactly. On Elasticsearch `_shard_doc` means nothing in a new PIT, so it continues from the last timestamp inclusive: documents sharing that one timestamp can be exported twice (at-least-once), never missed.
  - **`CoralogixExportSource`**: `POST <url>/api/v1/dataprime/query` (`Authorization: Bearer`), one query per time window with `metadata.startDate` (inclusive), `endDate` (exclusive), `tier` and `limit` (≤12,000 on frequent search, ≤50,000 on archive). The answer is NDJSON (`queryId`, `result.results[]` with `metadata`, `labels`, `userData`, then `statistics`). A window that returns `limit` rows, has a `NumberOfResultsLimitWarning`, or ends `INCOMPLETE` / `TIMED_OUT` is halved and queried again (down to 1ms, then the job fails); a window under a quarter of the limit doubles the next one, up to `initial-window`. A window is a page; its key names `[start, end)`. A row becomes a JSON line: `userData` (an object, or `{"message": text}`), plus `timestamp` and `severity` from `metadata` when absent, and `coralogix.<label>` for each label. Tested against a protocol stub, like GCS, with the protocol checked against Coralogix's API reference (2026-10).
  - **Transient failures** (connection errors, 429, 5xx) are retried 3 times with backoff (1s, 2s, 4s) inside the job; then the job fails, and re-submitting it resumes from the cursor. 400 / 401 / 403 fail at once.
- **Result:** one path from "bytes arrived" to "archived" for files, pushes and exports. Exactly-once per page with Coralogix and OpenSearch, and with Elasticsearch while its PIT lives.

### D8. The replay lane
- **Situation:** T8: a backfill or replay must not starve live ingest. Today every file job shares `max-concurrent-jobs`, and a re-ingest runs as fast as the disk allows.
- **Action:** jobs carry a `lane` (`live` | `replay`). `fromBeginning` file jobs and every export job default to `replay`; `lane` overrides it for file jobs. The replay lane (`ReplayLane`) has:
  - its own slots (`argus.replay.max-concurrent-jobs`, 1; a full lane is 429), so live file jobs never wait for a replay and a replay never waits for live jobs;
  - one shared read budget (`argus.replay.bytes-per-second`, 16MB/s, ≈130x the 10GB/day average): the reader of a replay file job, and each export page, waits for its bytes. A 1GB replay takes about a minute;
  - minimum-priority threads (advisory: Linux ignores Java priorities without `-XX:ThreadPriorityPolicy=1`, so the read budget is what isolates);
  - for exports, room for live pushes in the spool: an export pauses (status `paused: SPOOL_FULL`) while its stream holds `argus.replay.max-pending-bytes` (256MB) not yet ingested, or the spool as a whole holds half of `max-spool-bytes`; and like every job it waits while the WAL is full.
  - Suppression is bypassed (guardrail 8): replay file jobs never had it, and export pages go to the spool directly, not through the push path. The push quota doesn't apply either; the read budget does.
  - Live pushes and stream runs use their own slots and threads (D4), so they never wait for a replay slot.
- **Result:** measured by `ReplayIsolationIT` (T8): push p99 with and without a 1GB replay running; target ≤20% worse.

### D9. Export sources are configured, not named in requests
- **Situation:** an export reads from a URL with credentials. Taking either from the request would let any API key make the server call any address (SSRF into the cluster network) and put secrets in request bodies and logs.
- **Action:** sources are configured (`argus.export.sources[i]`: `name`, `tenant`, `type`, `url`, `index`, credentials, page size); a request names a source, which must belong to the caller's tenant (another tenant's source is 404, like a job). Credentials come from configuration (environment variables in practice) and are never logged or returned. The request chooses only the query, the time range and the target stream.
- **Result:** tenants can export only what an operator wired for them, from where it was wired (guardrail 4).

### Components (P3b)

| Component | Package | Role |
|---|---|---|
| `Lane`, `ReplayLane`, `ByteRateLimiter` | `ingest` | D8: the replay lane's slots, read budget and thread priority |
| `IngestJobService`, `IngestPipeline.Request` | `ingest` | Jobs on two lanes; a run can carry a read limit and low-priority stage threads |
| `ExportSource`, `ElasticsearchExportSource`, `CoralogixExportSource` | `export` (new) | D7: one page per call, with the cursor to continue from |
| `ExportJobService`, `ExportJob`, `ExportCursorStore`, `ExportSources` | `export` | Admission, paging into the spool, cursors, the configured sources (D9) |
| `ExportController` | `controller` | `POST /api/v2/ingest/exports`, `GET` / `DELETE /exports/{id}` |
| `ReplayProperties`, `ExportProperties` | `config` | Binding |

### Configuration (P3b)

```properties
argus.replay.max-concurrent-jobs=1
argus.replay.bytes-per-second=16MB
argus.replay.max-pending-bytes=256MB       # an export pauses while its stream has this much to ingest

argus.export.sources[0].name=elk
argus.export.sources[0].tenant=acme
argus.export.sources[0].type=elasticsearch  # elasticsearch (or OpenSearch) | coralogix
argus.export.sources[0].url=https://es.internal:9200
argus.export.sources[0].index=logs-*
argus.export.sources[0].timestamp-field=@timestamp
argus.export.sources[0].api-key=${ELK_API_KEY}   # or username / password
argus.export.sources[0].page-size=5000
argus.export.sources[0].keep-alive=10m
argus.export.sources[1].name=cx
argus.export.sources[1].tenant=acme
argus.export.sources[1].type=coralogix
argus.export.sources[1].url=https://api.eu2.coralogix.com
argus.export.sources[1].api-key=${CX_API_KEY}
argus.export.sources[1].tier=frequent_search      # or archive
argus.export.sources[1].initial-window=1h
```

### Tests (P3b)

| Kind | Test |
|---|---|
| Unit | `ByteRateLimiterTest` (rate, no saved-up credit, stop while waiting); `ReplayLaneTest`; `IngestJobServiceTest`: lanes default from `fromBeginning`, a busy replay lane never blocks a live job, a replay run is rate limited |
| Unit | `ExportJobServiceTest` with an in-memory source: pages land in the spool in order, the cursor is saved after each page, a crash between append and cursor re-fetches one page and the key drops it, resume vs `fromBeginning` vs a different export, pause on spool, cancel, another tenant's source is 404, one export per stream |
| Protocol | `CoralogixExportSourceTest` against a stub: request shape and auth, window halving and growth, statistics status, retries, row → line |
| Integration | `ElasticsearchExportSourceTest` on Elasticsearch 8.15 and OpenSearch 2.17 (Testcontainers, skipped without Docker): ties across page boundaries, `[from, to)`, a PIT deleted mid-export (exact on OpenSearch, nothing missed on Elasticsearch) |
| API | `ExportControllerTest`, `IngestControllerTest` (`lane`) |
| Load (`load` tag) | `ReplayIsolationIT` (T8): push p99 alone vs with a 1GB replay job; ≤20% worse |

### Implementation order (P3b)
1. This section.
2. The replay lane: `ByteRateLimiter`, `ReplayLane`, lanes in `IngestJobService` and the pipeline, `lane` in the API + tests.
3. The export framework: `ExportSource` port, `ExportJobService`, cursors, `ExportController`, configuration + tests with an in-memory source.
4. `ElasticsearchExportSource` + Testcontainers tests.
5. `CoralogixExportSource` + stub tests.
6. `ReplayIsolationIT` (T8); results in §10.
7. Docs.

## 9. Results (P3a)
Measured on an Apple M1 Pro (10 cores, SSD), JDK 17, with the application in its own JVM at `-Xmx256m` and the clients in the test JVM, over HTTP on localhost. Lines are ~200-byte JSON records, pushed gzipped in batches.

| Test | Result |
|---|---|
| **T1** `PushIngestIT`: 8 clients, 2M lines in 1,000-line batches | 150k lines/s acknowledged; **132k lines/s end to end** (pushed, spooled, ingested, committed; 32x the 4,150 target). Request p50 43ms, p99 93ms, max 1.2s (warm-up). Peak heap 243MB of 256MB (in use, garbage included). No 429s; all 2M lines in the output |
| **Tenant isolation** `PushIngestIT`: one tenant floods past its 1MB/s quota on 6 threads while another pushes 200-line batches | The flooding tenant got 5,539 429s; the calm tenant was never refused, and its p99 went from 25ms to 40ms (the flood still uses the same CPU and disk) |
| **T3** `PushBackpressureIT`: archive store unwritable for 20s, 4 clients, 1M lines, WAL capped at 40MB, spool at 32MB | 222 429s (206 `WAL_FULL` with `Retry-After: 60`, 16 `SPOOL_FULL` with 30), honoured by the clients. No OOM (peak heap 217MB). After the outage the WAL drained in 19.5s and all 1,000,000 acknowledged lines were archived, exactly once |
| **T4** `PushCrashIT`: `kill -9` at a random 20–80% of a 400k-line load with an `Idempotency-Key` per batch, then a restart on the same disk; clients retry what wasn't acknowledged | 5 runs: 0 lines lost, 0 lines with two `eventId`s, 400,000 distinct lines. Each run had 1–3 requests that were spooled just before the kill and whose retry was answered 200 from the index |

Found while building:
- **The local quota's refill overflowed at high rates.** Elapsed nanoseconds × bytes/s was computed in a `long` before dividing, which overflows after about 9s at 1GB/s and then denied requests. `PushBackpressureIT` showed it as unexplained 429s; fixed, with a regression test.
- **`max-spool-bytes` must count unread bytes, not bytes on disk.** A stream's active spool file holds already-ingested bytes until it rotates, so a disk-size cap would have refused pushes while nothing was waiting.

Found in review (fixed, each with a test):
- **A failed spool write lost the retry to suppression.** The lines' suppression windows were already open, so the client's retry within 10s was suppressed and acknowledged with nothing spooled. The windows are now released when the write fails (`SuppressionStore.release`).
- **A retry racing its original** could miss the key, be suppressed as the original's copy, and store an empty append under the key. One request per key is in progress at a time; the other gets 429 and then 200 from the original.
- **Stream ids and file source ids shared checkpoints.** A file job named like a stream replaced its checkpoint, so the stream re-read its spool, and a file named like a spool file (`000050.log`) could have made the spool delete files not yet read. They are now kept apart (409 either way), and a checkpoint counts only if it names a spool file in the stream's own directory.
- **Index compaction ran inside an acknowledged append.** A failure answered 500 for durable lines, and a failed reopen left a closed channel behind. Compaction is now best effort, and the new index's channel is opened before the rename.
- **Rejected requests created their stream**, using up `max-streams-per-tenant` slots that can't be freed. The stream is now created after every other bound.
- **Spool numbers had six digits**, about two years of one-a-minute rotations; they now grow to nine.
- Also: `date` is no longer a JSON timestamp key (it turned business fields into event times; the Fluent Bit example sets `json_date_key timestamp`), suppressed lines aren't counted as truncated, a running stream run shows in status (`RUNNING`, with `paused: WAL_FULL`), and `spool-dir` is made absolute so another spelling of it doesn't re-ingest every spool.
