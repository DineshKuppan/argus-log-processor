# Changelog

All notable changes to Argus. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

No versions have been tagged yet (the pom says `0.1.0`), so changes are grouped by merged pull request, newest first. Each phase's plan and measured results are in [`docs/architecture/`](docs/architecture/).

## [Unreleased]

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
