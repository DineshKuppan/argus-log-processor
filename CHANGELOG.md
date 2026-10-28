# Changelog

All notable changes to Argus. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

No versions have been tagged yet (the pom says `0.1.0`), so changes are grouped by merged pull request, newest first. Each phase's plan and measured results are in [`docs/architecture/`](docs/architecture/).

## [Unreleased]

### P3a: HTTP push ingest, quotas and suppression on push (unmerged)
Plan and results: [`P3_PLAN.md`](docs/architecture/P3_PLAN.md).

#### Added
- **HTTP push ingest:** `POST /api/v2/ingest/streams/{id}` takes NDJSON or plain lines, optionally gzipped, and answers 202 once they are durable in the stream's spool.
  - `StreamSpool` keeps numbered spool files plus an `index.log` of acknowledged requests. Bytes that were never acknowledged are truncated at startup.
  - `StreamRunner` ingests each spool with the ordinary pipeline once a line has waited `argus.push.flush-interval` (1 minute), a segment's worth is waiting, or a spool file was sealed. Each spool file is its own output generation.
  - `GET /api/v2/ingest/streams/{id}` and `GET /api/v2/ingest/streams` report spools, counters and the last run.
- **`Idempotency-Key`:** a repeated key is answered 200 with the original offsets and writes nothing, even across a restart.
- **Per-tenant ingest quotas** (`argus.quota.*`): a token bucket on decompressed pushed bytes (2MB/s, 2s burst, per-tenant overrides), in memory or in Redis (fails open). Over it, 429 with the exact `Retry-After`.
- **Every push limit has a status:** 429 + `Retry-After` for busy request slots, the quota, a full WAL or a full spool; 413 for the body (8MB decompressed, which stops gzip bombs) or 10,000 lines; 409 for more than 32 streams per tenant; 415 for an unknown `Content-Encoding`.
- **Suppression on push:** repeated messages are dropped at receive time; only allowed lines reach the spool, so a replay never suppresses again.
- One `Push request finished …` canonical line per request.
- Load tests (tag `load`), with the application in its own 256MB JVM: `PushIngestIT` (T1 and tenant isolation), `PushBackpressureIT` (T3 for push), `PushCrashIT` (T4 for push).

#### Changed
- File jobs and stream runs share `SourceLeases`, so a source has one run at a time whichever started it. A 409 for a busy source now names its owner.
- `IngestPipeline.Request` gains `endOffset` and `pathIsIdentity`; `ResumePoint` gains `PATH_CHANGED`.
- `JsonLogParser` reads Fluent Bit's `date` key, and fractional epoch seconds keep their fraction.
- ADR-6 now recommends Java 21 before P4 rather than P3.

#### Measured
- **T1 (push):** 2M lines from 8 clients: 132k lines/s end to end, request p99 93ms, peak heap 243MB of 256MB.
- **Tenant isolation:** a tenant flooding past its quota got 5,539 429s; another tenant was never refused, and its p99 went from 25ms to 40ms.
- **T3 (push):** a 20s archive outage turned into 222 429s; all 1,000,000 acknowledged lines were archived afterwards.
- **T4 (push):** `kill -9` at a random point, 5 runs: no acknowledged line lost, none ingested as two events.

### P2b: WAL watermarks, WAL compression and GCS: [#7](https://github.com/DineshKuppan/argus-log-processor/pull/7) (2026-10-28)
Plan and results: [`P2_PLAN.md`](docs/architecture/P2_PLAN.md) §8, §10.

#### Added
- **Backpressure from the archive (T3).**
  - With an archive store, `WatermarkWalCapacity` pauses ingest before its next segment while more than `argus.wal.high-watermark` × `max-bytes` (80% of 30GB) is waiting for upload, and resumes below the low watermark (60%).
  - The bounded queues then stop the reader too.
  - Job status gains `paused` (`WAL_FULL`) and `pausedMs`. A paused job can be cancelled.
  - New jobs get 429 + `Retry-After: 60` while the WAL is full.
- **WAL compression:** `argus.sink.local.compression=zstd` writes `.ndjson.zst` segments, 10.4x smaller than NDJSON, for 9% of ingest speed. The archiver reads both, and a corrupt zstd stream is set aside like invalid NDJSON.
- **`GcsObjectStore`** (`argus.archive.store=gcs`): the GCS JSON API with resumable 16MB chunks that resume after a failed chunk. Application Default Credentials, or `gcs.anonymous` for emulators.
- `WalBackpressureIT` (tag `load`): an archive outage during a 500MB ingest at a 256MB heap.

#### Changed
- The 429 for busy job slots and for a full WAL share `IngestBusyException`; their `Retry-After` values are 30s and 60s.
- The ingest canonical line gains `pausedMs`.
- The archive canonical line's `compression` is now source bytes / Parquet bytes (it was WAL bytes / Parquet bytes, which a zstd WAL would skew), and it gains `sourceBytes`.
- zstd WAL segments carry a content checksum, so a corrupt segment fails to decompress rather than decoding to wrong bytes.

#### Measured
- **T3, scaled to a 30s outage:** the job paused for 49s and then completed; the WAL peaked within one segment of its high watermark; all 5,381,072 lines were archived; heap peak 219MB.
- **zstd WAL:** 0.40x the input, against 4.14x for NDJSON.

### P2a: Parquet archive: [#5](https://github.com/DineshKuppan/argus-log-processor/pull/5) (2026-10-28)
Plan and results: [`P2_PLAN.md`](docs/architecture/P2_PLAN.md).

#### Added
- **Parquet + ZSTD archive**, opt-in with `argus.archive.store=filesystem|s3` (default `none`).
  - The `Archiver` converts each committed segment and uploads it under `<prefix>/tenant=T/dt=…/hour=…/source=…/gen=…/FROM-TO.parquet`. Only then does it delete the local copy, or mark it with `delete-after-upload=false`.
- **The local segments are now a write-ahead log.**
  - The source checkpoint advances when a segment is safe on local disk.
  - Uploads follow asynchronously, so an object-store outage only grows the WAL.
  - Failed uploads back off exponentially, from 1s up to 5 minutes.
- **One Parquet object per hour of data**, at most 24 per segment, so `dt`/`hour` partitions are exact.
- **`ArchiveIndex`**: a replayed segment deletes its earlier copy's objects, even in other partitions.
- **`ObjectStore` port** with `FileSystemObjectStore` (PVC) and `S3ObjectStore` (AWS SDK v2 multipart, 16MB parts). Both pass a shared contract test; S3 is tested against S3Mock.
- **`ZstdCodecFactory`**: Parquet runs with only the shaded `hadoop-client-api`, with no Hadoop runtime.
- **Bad input:**
  - A segment that isn't valid NDJSON is renamed `.unreadable` and kept, not retried.
  - Event times beyond ±292,000 years are archived as null and kept in `argus.timestamp`.
- One canonical `Archive segment finished …` log line per segment.
- `LargeFileIngestIT` also archives what it ingests and reports the compression.

#### Changed
- **Guardrail 3:** the source checkpoint follows the WAL, and a WAL segment is deleted only after every sink acknowledges it.
- **Guardrail 4:** allows one fixed deployment prefix, shared by all tenants, before `tenant=`.
- **Shared day rule:** the WAL's `dt` and the archive's `dt`/`hour` both come from `sink.EventTimePartition`.

#### Measured
- 1GB synthetic access log at a 256MB heap: all 11,020,561 rows archived, **14.6x smaller than the input** (60x smaller than the NDJSON). Archiving runs at 177–215k lines/s, with peak heap ≤202MB.
- Dropping the derivable `event_id` column took the archive from 3.6x to 14.6x.

### Hardening: API-key tenancy and v1 path restriction: [#4](https://github.com/DineshKuppan/argus-log-processor/pull/4) (2026-10-28)
Plan and results: [`HARDENING_PLAN.md`](docs/architecture/HARDENING_PLAN.md).

#### Added
- **API keys:** `X-Argus-Api-Key` or `Authorization: Bearer`. Configuration holds only each key's SHA-256 (`argus.tenancy.api-keys[i].tenant` / `.key-sha256`).
- **Per-tenant input directories:** `argus.tenancy.api-keys[i].allowed-roots`.
- **Fails closed:** `TenantInterceptor` resolves the tenant before dispatch on every `/api/**` request, so a new endpoint is authenticated even if it doesn't ask for the tenant.
- `argus.tenancy.mode=header` for local development (a caller-chosen `X-Argus-Tenant`).
- The OpenAPI docs declare the `apiKey` scheme.

#### Changed
- **Breaking:** every API call, v1 included, needs an API key. With no keys configured the API answers 401.
- **Breaking:** v1 reads only files under the caller's allowed roots. `log.enforce-allowed-roots=false` only warns, for a migration window.
- **Per tenant:** v2 checkpoints, output, `eventId` and jobs. Checkpoints written before tenancy still load for tenant `default`.
- Job history keeps up to 200 jobs per tenant.

### P1b: 10-second suppression and streaming aggregation: [#3](https://github.com/DineshKuppan/argus-log-processor/pull/3) (2026-10-27)
Plan and results: [`P1_PLAN.md`](docs/architecture/P1_PLAN.md) §8, §11.

#### Added
- **`POST /api/v2/suppression/check`:** a message is allowed once per tenant per window (10s), and its copies are denied and counted.
  - Local store: Caffeine, the default.
  - Redis store: one Lua call; fails open.
  - Each window that denied copies ends with one summary line.
- **`StreamingAggregator`:** v1 aggregations stream files of any size.
  - Top-N uses Misra–Gries with 100k counters.
  - At most 64 distinct levels are counted.
- `log.max-concurrent-aggregations` (2); more requests get 429 + `Retry-After`.

#### Changed
- v1 now answers 400 / 404 instead of 500 for bad input and missing files.
- Testcontainers is pinned to 1.21.4, for Docker Engine 29+.

#### Measured
- Suppression: p99 8.8µs (target <1ms); exactly one of 32 concurrent identical calls is allowed.

### P1a: parser registry, format detection and `LogEnvelope`: [#2](https://github.com/DineshKuppan/argus-log-processor/pull/2) (2026-10-27)

#### Added
- **Parsers:** `nginx_access`, `json`, `cri` (Kubernetes), `kv`, `raw` and `logback` (a Logback layout compiled to a regex), behind one `ParserRegistry`.
  - Each source's format is detected from its first 100 lines.
  - Each line falls back to the other parsers, then to `raw`.
- **`LogEnvelope`**, following the OpenTelemetry log data model, with a deterministic `eventId = UUIDv3(tenant, source, generation, offset)`.
- Lines no parser understands are kept as `raw` with `argus.unparsed`.

#### Changed
- **Breaking:** v2 output lines are `LogEnvelope`s instead of `LogRecord`s.
- v1 uses the same registry, so it now understands JSON, CRI and key-value files.
- Access-log dates are parsed with `Locale.ROOT`.

#### Removed
- `LineParser`, and `parseLine(line, pattern)` from the `LogParser` interface.

### P0: streaming file ingest: [#1](https://github.com/DineshKuppan/argus-log-processor/pull/1) (2026-10-27)
Plan and results: [`P0_PLAN.md`](docs/architecture/P0_PLAN.md).

#### Added
- **`POST /api/v2/ingest/file`** and the job API: asynchronous, any file size.
- **Bounded pipeline:** read → parse → write stages connected by bounded queues. Batches hold ≤10,000 lines or ≤8MB; segments are ~500MB.
- **Crash-safe checkpoints:** inode, head hash and generation, written with fsync + atomic rename. A segment is durable before its checkpoint advances.
- **`LocalVolumeSink`:** idempotent, offset-named NDJSON segments.
- `DataSeeder` streams test logs of any size.

#### Measured
- 10GB in a 4GB container: peak heap 810MB, every line committed.
- `kill -9` three times mid-run: nothing lost or duplicated.

### Earlier
- 2026-10-26: regex-filtered parse endpoint (`/api/v1/logs/parse/regexp`) and Redis cache configuration.
- 2025-09-20: initial log service: parse, filter, aggregate and export (JSON, CSV), with Nginx, JSON and key-value logs; parser and aggregator tests; `doc.md` design notes.
