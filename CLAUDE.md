# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Argus Log Processing Service** is a Spring Boot application that provides efficient log processing, parsing, filtering, and aggregation capabilities. The service runs on port 9000 and supports multiple log formats (JSON, Nginx access logs, key-value logs).

## Prerequisites

- Java 17 (the build target). **Build with JDK 17 or 21, not 24+**: the Lombok version managed by Spring Boot 3.3.3 (1.18.34) crashes on JDK 24, and every Lombok-generated getter then fails to compile. If your default JDK is newer, prefix Maven commands with e.g. `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-tem`.
- Maven 3.8+
- Redis (optional: only with `argus.suppression.store=redis`; the default suppression store is in memory)
- Docker (optional: `RedisSuppressionStoreTest`, `RedisQuotaStoreTest`, `S3ObjectStoreTest`, `GcsObjectStoreTest` and `ElasticsearchExportSourceTest` use Testcontainers (Redis, S3Mock, fake-gcs-server, Elasticsearch 8.15 and OpenSearch 2.17), as do the P6 broker tests in `dev.dinesh.argus.kafka` (Apache Kafka 3.8 and Redpanda 24.2), and are skipped without it; also the 4GB-container load check under Testing)

## Build and Run Commands

### Build the project
```bash
# Clean build with formatting (uses Google style)
mvn clean package

# Skip tests during build
mvn clean package -DskipTests

# Run with the local profile (includes code formatting)
mvn clean package -P local
```

### Run the application
```bash
# Start via Maven (includes live reload via DevTools)
mvn clean spring-boot:run

# Start via JAR
java -jar target/argus-log-processor-0.1.0.jar

# Production-like JVM flags for a 4GB RAM node (heap ≈2.4GB, bounded off-heap)
java -XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError \
  -jar target/argus-log-processor-0.1.0.jar
```

The application starts on **http://localhost:9000**. Swagger UI is available at **http://localhost:9000/swagger-ui.html**.

### Testing
```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=AggregatorTest

# Run a specific test method
mvn test -Dtest=AggregatorTest#testCountByLevel

# Load tests (tag "load", excluded by default): LargeFileIngestIT ingests a generated file in a JVM capped
# at 256MB, then archives it to Parquet and reports the compression (-Dargus.load.wal-compression=zstd);
# WalBackpressureIT is T3: the archive store is down while 500MB ingests, so ingest must pause at the
# WAL watermark, then everything is archived; SuppressionLatencyIT times 1M suppression decisions (p99 < 1ms).
# Push (P3a; the app runs in its own 256MB JVM, clients push gzip NDJSON over HTTP): PushIngestIT is T1 (2M lines,
# -Dargus.load.push-lines) and tenant isolation; PushBackpressureIT is T3 for push (archive unwritable for
# -Dargus.load.outage-seconds → 429s, then all archived); PushCrashIT is T4 for push (kill -9, restart, no loss)
# ReplayIsolationIT is T8 (P3b): push p99 before, during and after a 1GB replay-lane re-read (-Dargus.load.replay-size,
# -Dargus.load.window-seconds), plus the same re-read on the live lane for contrast
# LiveTailIT is T7 (P4): push-to-tail and file-to-tail latency, push p99 / rate with 16 tail clients that never read,
# and 1,000 open tail connections (-Dargus.load.tail-connections). -Dargus.load.docker-cpus=2 runs the server in a container
# HotTierIT is P5: T5 (p99 from a push's 202 to the search API finding it, -Dargus.load.hot-rate / hot-seconds), an engine
# outage under push load (-Dargus.load.outage-seconds), kill -9 while indexing (host only), the mapping's disk saving;
# OpenSearch 2.17 by default, -Dargus.load.hot-engine=elasticsearch for Elasticsearch 8.15; -Dargus.load.wal=kafka runs it with Kafka as the WAL
# KafkaWalIT is P6a (host only): T1 push throughput to Kafka beside the local WAL (-Dargus.load.kafka-lines), T3 broker outage
# (-Dargus.load.outage-seconds) and sink outage (lag watermark → 429 WAL_FULL), T4 kill -9; -Dargus.load.kafka-broker=redpanda for Redpanda
mvn test -Dgroups=load -DexcludedGroups=none                              # 1GB input
mvn test -Dgroups=load -DexcludedGroups=none -Dargus.load.input-size=4GB  # needs ~4x input in free disk

# Generate a large Nginx log for manual runs (streams; never builds the file in memory)
java -Dloader.main=dev.dinesh.argus.seeder.DataSeeder -cp target/argus-log-processor-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher data/in/big.log 10GB
```

## Architecture

### Core Components

**Models** (`dev.dinesh.argus.model`)
- `LogRecord`: what a parser returns (timestamp, level, message, metadata map); also the v1 API's record type
- `LogEnvelope`: the canonical v2 record, following the OpenTelemetry log data model (`eventId`, `tenantId`, `timestamp`, `observedTimestamp`, `severityText`/`severityNumber`, `body`, `traceId`/`spanId`, `resource`, `attributes`, `source`). `Severity` maps level spellings to OTel numbers

**Parsers** (`dev.dinesh.argus.parser`)
- `LogParser` port: `name()`, a cheap `canParse()` probe (no false negatives) and `parse()` returning null, never throwing. Must pass `ParserContractTest`
- Built-ins, in priority order: `CanonicalLineParser` (`canonical`, P4: `canonical-log-line k=v` lines with typed values), `ServerAccessLogParser` (`nginx_access`), `JsonLogParser` (`json`), `CriLogParser` (`cri`, Kubernetes), `KeyValueLogParser` (`kv`); plus `RawTextParser` (`raw`, last fallback) and `LogbackPatternParser` (`logback`, a layout compiled to a regex; only when requested)
- `ParserRegistry`: resolves a requested format, `detect()`s one from a sample (`FormatDetector`: most parsed lines wins), and builds a `ParserChain` (primary → other parsers → raw). When detection finds no format (`raw`), every line still tries the structured parsers first

**Service Layer** (`dev.dinesh.argus.service`)
- `LogService`: Central service orchestrating parsing, filtering, aggregation, and export operations
  - Aggregations (`summarize`, `aggregate`, `errorRateOverTime`, `errorSummary`) stream the file through `FileSource` and the parser registry in one pass, with no size limit. At most `log.max-concurrent-aggregations` (2) run at once; more get `AggregationSlotsBusyException` (429), never a queue. Lines up to 16MB (not ingest's 1MB). One `v1 scan finished …` canonical line per scan
  - Methods that return every record (`parseFile`) keep the 50MB limit (`log.max-file-size`)
  - Filter application using composite pattern
  - Multiple aggregation strategies
  - Export to JSON and CSV formats

**Controller** (`dev.dinesh.argus.controller`)
- `LogController` (`/api/v1/logs`): parse, filter, aggregate, export. Synchronous. `/aggregate`, `/aggregateV2`, `/export/*` and `/cli-summary` stream files of any size; `/parse`, `/parse/regexp` and `/filter` return every record and keep the 50MB cap. Needs an API key like v2, and reads only the caller tenant's roots (`log.enforce-allowed-roots=false` only warns, for a migration window). v1 persists nothing. Errors: 400 bad input (`IllegalArgumentException`, e.g. outside the roots, over the size cap or a negative `topN`), 404 missing file, 429 + `Retry-After` when the aggregation slots are busy
- `SuppressionController` (`/api/v2/suppression`): `POST /check` `{"message"}` → `{"decision":"ALLOW"|"DENY","windowEndsAt","suppressedCount"}`; tenant from the caller's API key (see Tenancy); 400 for a missing or too long message, 401 without a valid key
- `StreamController` (`/api/v2/ingest/streams`): HTTP push ingest (P3a). `POST /{streamId}` with lines (NDJSON or text, `Content-Encoding: gzip`), optional `Idempotency-Key`, `format`, `pattern` → 202 `{accepted, suppressed, truncated, spool, startOffset, endOffset}` once durable in the spool, 200 with the original offsets for a repeated key; 429 + `Retry-After` (request slots 1s, the same key still in progress 1s, quota exact, WAL full 60s, spool full 30s), 413 (body or lines), 409 (streams per tenant, or a stream id that is a file job's source id), 415 (encoding), 400. `GET /{streamId}` and `GET` (the tenant's streams; another tenant's is 404)
- `IngestController` (`/api/v2/ingest`): streaming ingest of files of any size, as async jobs. `POST /file` with `path`, optional `sourceId`, `fromBeginning`, `format`, `pattern`, `lane` (`live` | `replay`; default `replay` with `fromBeginning`) (202, or 400 / 404 / 409 source busy / 429 + `Retry-After` when the lane's job slots are busy (30s) or the WAL is full (60s)), `GET` and `DELETE /jobs/{id}` (401 without a valid key; another tenant's job is 404). Sources, checkpoints, output and jobs are per tenant
- `TailController` (`/api/v2/tail`, P4): `GET` with optional `level` (minimum severity), `source`, `q` (body substring, case-insensitive) and `Last-Event-ID` → Server-Sent Events of the caller tenant's lines as they are accepted (after suppression): `data:` one line as JSON (`TailEvent`), `event: dropped` `{"dropped":N}` for lines this client missed, `event: reset` for a `Last-Event-ID` that can't be resumed, a `:` heartbeat every 15s. 400 bad filter, 429 + `Retry-After: 30` at a connection limit. Error bodies set their content type, so `Accept: text/event-stream` clients get them (the 401 too)
- `SearchController` (`/api/v2/search`, P5): `GET` with `from` / `to` (ISO 8601, required, `[from, to)` ≤ `max-range` 14d and within the hot window, else 400 naming the archive), `q` (body only, `simple_query_string` without regexes / fuzzy / field names, ≤1,024 chars), `level`, `source`, `service`, repeatable `attr=key:value`, `size` (≤500), `cursor` → `{hits, next, partial, tookMs}`, newest first. 404 without a hot store; 429 + `Retry-After: 1` at `max-concurrent-searches`; 503 + `Retry-After: 5` when the engine is busy or down; 502 when its answer passes `max-response-bytes`
- `ExportController` (`/api/v2/ingest/exports`, P3b): `POST` `{source, streamId, query, from, to, fromBeginning}` exports `[from, to)` from a configured source into a stream, as a replay-lane job (202; 400 bad range or query, 404 unknown source (or another tenant's), 409 an export already running on the stream / stream limits, 429 + `Retry-After` replay slots busy or WAL full). `GET` and `DELETE /exports/{id}`

### Tenancy

`dev.dinesh.argus.tenant`: every `/api/**` request (v1 and v2) acts for one tenant, resolved from credentials, never from the body (guardrail 4). `TenantInterceptor` resolves it **before dispatch**, so an endpoint without `@CurrentTenant` is still authenticated (fails closed); controllers read it as a `@CurrentTenant String tenantId` parameter. Wired by `config.TenancyConfig`. SpringDoc hides the parameter and documents the `X-Argus-Api-Key` scheme.
- `TenantRoots`: the directories each tenant may read files from. A key's `allowed-roots` isolate its tenant; keys without them share `argus.ingest.allowed-roots` (startup warns when several tenants share them, as they can read each other's files there)
- `ApiKeyTenantResolver` (default, `argus.tenancy.mode=api-key`): `X-Argus-Api-Key: <key>` or `Authorization: Bearer <key>`. Configuration holds only each key's SHA-256: `argus.tenancy.api-keys[i].tenant` / `.key-sha256` (`printf %s "$KEY" | shasum -a 256`). Missing or unknown key → 401 + `WWW-Authenticate` (`TenantExceptionHandler`). With no keys configured the app starts, warns, and the API answers 401
- `HeaderTenantResolver` (`mode=header`): trusts a caller-chosen `X-Argus-Tenant` (default `default`). Local development only; warns at startup
- `TenantIds.requireValid`: `[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}`, safe in keys (no `:`) and paths (no `/`, no leading `.`). `TenantIds.pathSegment` (`tenant=T`) is the one definition of the tenant prefix for checkpoints and sinks

### Streaming Ingest (P0)

Read → parse → write stages in separate threads, connected by bounded queues (raw 2, parsed 4 batches). A full queue blocks the stage before it, so heap use is set by queue capacity, not file size.
- **Batch** (≤10,000 lines / ≤8MB) is the in-memory unit; **segment** (~500MB of source) is the durability unit: one output file and one checkpoint.
- **Commit protocol:** a segment is fsynced and renamed into place (and its directory fsynced) *before* its end offset is checkpointed. A crash or power loss replays at most the open segment, and its output is replaced, not duplicated. Use `utils.DurableFiles` for any rename that must survive power loss.
- `source.FileSource`: `FileChannel` reader with exact byte offsets; batches end only on line boundaries. A run stops at the last `\n`: a trailing line without one may still be being written, so it waits for the next run (as Filebeat / Fluent Bit do).
- `checkpoint.FileCheckpointStore`: one JSON file per tenant and source, `checkpoint-dir/tenant=T/<source>.json` (tenant `default` also reads a pre-tenancy `checkpoint-dir/<source>.json`, so an upgrade keeps offsets and generations) (temp file + fsync + atomic rename + directory fsync). The checkpoint stores the inode, a SHA-256 of the first 1KB and a **generation**. `ResumePoint` compares them with the *opened* file and restarts from 0 in a new generation if it was replaced (inode), truncated (size) or truncated-and-regrown (head hash, e.g. `copytruncate`).
- `sink.LocalVolumeSink`: NDJSON at `tenant=T/dt=YYYY-MM-DD/source=ID/gen=G/START-END.ndjson`. `dt` comes from the first event time in the segment (`unknown` if none) and the name from offsets, so replays land on the same path; `gen` keeps a rotated file's output from replacing the previous file's.
- `ingest.IngestPipeline` runs one job; `ingest.IngestJobService` runs jobs on `max-concurrent-jobs` slots with no queue (rejects instead of buffering), and only one job per source at a time.
- **Formats (P1):** the primary parser is detected from the file's first 100 lines (or requested with `format` / `pattern`). Each line tries the primary, then the other parsers, then `raw`; job status reports `format`, `formatSource`, `fallbackParsed`, `unparsed` and `rejected`. Lines no parser understands are kept as `raw` records with `attributes["argus.unparsed"]` (`keep-unparsed=false` drops them instead).
- `pipeline.EnvelopeFactory` turns parsed lines into `LogEnvelope`s: `eventId = UUIDv3(tenant, source, generation, line offset)`, so replays reproduce the same ids; trace/span ids and `service.name` are lifted out of the attributes.
- Source ids become directory names, so `SourceOffset.requireValidSourceId` limits them to `[A-Za-z0-9._-]`. Ingest paths (v1 and v2, `source.AllowedPaths` via `TenantRoots`) must resolve (after symlinks) under the tenant's roots; the roots are checked before existence, so a 404 never reveals a path outside them.

### Parquet Archive (P2)

`dev.dinesh.argus.archive`, opt-in with `argus.archive.store=filesystem|s3|gcs` (default `none`: the NDJSON segments stay the output). With a store, the local segments become the **WAL** ([`P2_PLAN.md`](docs/architecture/P2_PLAN.md) D1): the source checkpoint still advances when a segment is committed locally, and the `Archiver` uploads it afterwards, so an object-store outage never stalls ingest; it only grows the WAL.
- `Archiver` (`SmartLifecycle`, one thread): every `scan-interval`, oldest segment first (`WalSegments`): NDJSON → Parquet in `staging-dir` → `ObjectStore.put` → delete the objects of the copy a replay superseded → delete the WAL segment (or write a `.archived` marker with `delete-after-upload=false`, which keeps NDJSON forever: give the volume its own retention). A store, disk or code failure stops the pass and backs off (`retry-initial` doubling to `retry-max`, then 50–100% of that); the WAL is the retry queue, no Resilience4j. The loop catches `Throwable`, so even an `Error` only backs off. A segment that isn't valid NDJSON is renamed `.unreadable` (kept, not retried). One canonical `Archive segment finished …` line per segment
- Keys: `<prefix>/tenant=T/dt=YYYY-MM-DD/hour=HH/source=ID/gen=G/FROM-TO.parquet`. A segment becomes one object per run of lines in the same UTC hour (`sink.EventTimePartition`, shared with the WAL's `dt`; lines without an event time stay in the current object; at most 24 objects per segment for out-of-order data), and the objects' byte ranges tile the segment's: deterministic, so a re-upload replaces them
- `ArchiveIndex` (`root/tenant=T/_archive/source=S/gen=G.json`): the keys of each source's latest archived segment, written before the upload. A replay can change a segment's end and so its partitions; the index names the earlier copy's objects so they're deleted wherever they landed. Only the latest segment of a source can still be replayed
- `EnvelopeParquetWriter` + `EnvelopeSchema`: ZSTD, snake_case columns, maps as `map<string,string>` (non-string attribute values as JSON text), timestamps in µs (an event time µs can't hold, beyond ±292,000 years, is archived as null and kept in the `argus.timestamp` attribute). **No `event_id` column**: it is a hash of `tenant_id`, `source_id`, `source_generation`, `source_offset`, re-derived with `EnvelopeFactory.eventId(…)`; storing it would triple the archive. Add columns, never rename them
- `ZstdCodecFactory`: Parquet pages compressed with zstd-jni directly. Parquet's own codec factory builds a Hadoop `Configuration` and needs `hadoop-client-runtime`; with this, only the shaded `hadoop-client-api` is on the class path. Read archives with the same factory (`ParquetReadOptions.withCodecFactory`)
- `ObjectStore` port (`put` replaces, `list(prefix)`, idempotent `delete`, keys checked by `ObjectKeys`): `FileSystemObjectStore` (PVC: temp file + fsync + rename), `S3ObjectStore` (AWS SDK v2 async multipart client, 16MB parts; credentials only from the SDK's default chain) and `GcsObjectStore` (the GCS JSON API over `java.net.http`, not `google-cloud-storage`: resumable 16MB chunks; a failed chunk asks the session where it is and resumes, up to 3 times; Application Default Credentials, or `anonymous` for emulators). Every store must pass `ObjectStoreContractTest`
- **WAL watermarks (P2b, T3):** `wal.WatermarkWalCapacity` (the `sink.WalCapacity` port; only with an archive or hot store) measures pending WAL bytes (segments some routed sink hasn't acknowledged, P5). From `argus.wal.high-watermark` × `max-bytes` (80% of 30GB), the pipeline's writer waits before its next segment, so the bounded queues stop the reader (guardrail 2); below the low watermark (60%) it resumes. It measures afresh at each segment boundary, so a job passes the high watermark by at most one segment. Job status shows `paused: WAL_FULL` and `pausedMs`; a paused job can be cancelled; new jobs get 429. Without an archive or hot store, `WalCapacity.UNBOUNDED`: nothing drains the WAL, so `min-free-bytes` stays the only limit
- **WAL compression:** `argus.sink.local.compression=zstd` writes `START-END.ndjson.zst` (level 1; the frame is closed before the fsync), 10x smaller than NDJSON for 9% of ingest speed; the archiver reads both. A corrupt zstd stream is set aside like invalid NDJSON
- One archiver per WAL volume; memory is one row group (64MB), disk one segment's staged Parquet files

### Push Ingest (P3a)

[`P3_PLAN.md`](docs/architecture/P3_PLAN.md). Pushed lines are made durable in a per-stream **spool**, and the existing pipeline ingests the spool like a file: no second implementation of parsing, segments, checkpoints, WAL or archive.
- `ingest.PushIngestService`: the push bounds in order, cheapest first and before the body is read where possible: request slots (`max-concurrent-requests`, 429), WAL full (429), spool full (`max-spool-bytes` not yet ingested, 429), the same `Idempotency-Key` in progress (429) or already answered (200, before the body or quota), `Content-Length` and the decompressed body (`max-request-bytes`, read at most one byte past it, so gzip bombs stop there; 413), line count (413), the tenant's quota (429), then streams per tenant (409). The stream is created only then, so a rejected request never takes a stream slot. Lines are truncated to `max-line-bytes`, blank lines dropped and a missing final `\n` added, compacting the body in place. One `Push request finished …` canonical line per request
- **Suppression at receive time (D3):** each line is parsed with the stream's format (requested, or detected from its first request) to find its message, checked with the `Suppressor`, and only allowed lines are spooled, so a replay never suppresses again (guardrail 8). If the spool write then fails, the request's windows are released (`SuppressionStore.release`), so the client's retry isn't suppressed as a copy of lines that were lost. `argus.push.suppress=false` turns it off
- `source.StreamSpool`: `spool-dir/tenant=T/stream=S/NNNNNN.log` + `index.log`. An append fsyncs the lines, then the index entry, then moves the visible length; a failure truncates both back. At startup, bytes of the newest file past its last index entry were never acknowledged and are truncated. The index holds the last `idempotency-window` keys (compacted to about twice that; best effort, after the append is acknowledged). A file is sealed at `spool-rotate-bytes` (or fully read and ≥1MB) and deleted once read; numbers are never reused (six digits, up to nine). `spool-dir` is made absolute, since spool paths are part of the checkpoint identity, and a checkpoint counts only if it names a spool file of that stream's directory
- `ingest.StreamRunner` (`SmartLifecycle`): every second, a run for each stream with `segment-bytes`, a sealed file, or a line older than `flush-interval` waiting, on `max-concurrent-runs` threads. A run is `IngestPipeline.Request(…, endOffset = visible length, pathIsIdentity = true)`: each spool file is its own generation (`ResumePoint.Reason.PATH_CHANGED`), without trusting inodes. It shares `ingest.SourceLeases` with `IngestJobService` (one run or job per source; stream ids and file source ids share checkpoints, so a file job can't use a stream's id and a stream can't be created over a file source's checkpoint or running job), and after any run reads the checkpoint back into the spool (`markRead`), which deletes ingested files
- `ingest.StreamRegistry`: the tenant's streams and open spools; reopens every spool under `spool-dir` at startup, so acknowledged lines are ingested after a restart
- **Quotas (D5):** `quota.QuotaStore` port, a token bucket per key (`<tenant>:quota:ingest`, decompressed bytes): `LocalQuotaStore` (default; Caffeine, lazy refill) or `RedisQuotaStore` (one Lua call on Redis's clock; fails open). A request fits when the bucket holds `min(amount, capacity)`, so one larger than the burst still passes a full bucket and leaves it in debt; `Retry-After` is exact. Wired by `config.QuotaConfig`
- **Delivery:** with an `Idempotency-Key` a retry is exactly-once, across restarts; without one, at-least-once. Fluent Bit's and Vector's HTTP outputs only send static headers, so for them a retried batch can be ingested twice. `observedTimestamp` is the run's time, up to `flush-interval` after receipt

### Replay Lane and Exports (P3b)

[`P3_PLAN.md`](docs/architecture/P3_PLAN.md) §8 (D7–D9).
- **Replay lane (D8):** `ingest.Lane` (`LIVE` | `REPLAY`) on every file job; `fromBeginning` jobs default to `REPLAY`, and every export job is one. `ingest.ReplayLane` has its own slots (`argus.replay.max-concurrent-jobs`, 1; full is 429, never a queue), one shared `ByteRateLimiter` read budget (`argus.replay.bytes-per-second`, 8MB/s; the reader of a replay run waits for each batch's bytes, an export for each page's) and minimum-priority threads (advisory; Linux ignores Java priorities by default, so the budget is what isolates). `IngestPipeline.Request.onReplayLane(limit)`. Live jobs, pushes and stream runs never wait for a replay slot. Suppression is bypassed (guardrail 8), and the push quota doesn't apply
- **Exports (D7):** `export.ExportJobService` pages through an `ExportSource` and appends each page to a stream spool directly (not through `PushIngestService`), under `Idempotency-Key` `export-sha256(export identity, run id, page id)`, then saves the cursor (`ExportCursorStore`: `checkpoint-dir/tenant=T/_export/<stream>.json`, temp + fsync + rename). A crash re-fetches at most one page, which the spool answers as a duplicate. Re-submitting the same export (source, its URL/index, query text, range) resumes; `fromBeginning` starts a new run (new keys); a different export on the stream starts over; a finished one completes at once. The stream's format is set to `json`, and the stream runner ingests it like any push stream. An export pauses (`paused: SPOOL_FULL`) while its stream holds `argus.replay.max-pending-bytes` (256MB) not yet ingested or the spool half of `max-spool-bytes`, and while the WAL is full. Transient failures (`IOException`: connection, 429, 5xx) retry 1s/2s/4s, then the job fails (re-submit resumes); `PermanentExportException` (4xx, bad query) fails at once. Canonical lines: `Export page finished …` per page, `Export finished …` per job
- `ElasticsearchExportSource` (`java.net.http`): Elasticsearch 7.12+ or OpenSearch 2.x, told apart by `GET /`. Point in time + `search_after`, `range` on `timestamp-field` `[from, to)`, `_source` as the line. Elasticsearch sorts on the timestamp (ISO text, nanoseconds) plus its implicit `_shard_doc`; OpenSearch has no `_shard_doc` and rejects sort `format`, so it sorts on the timestamp (epoch ms) plus `_id`. An expired point in time (`search_context_missing_exception`) is replaced: exact on OpenSearch, from the last timestamp inclusive on Elasticsearch (ties at that timestamp can repeat, never be missed)
- `CoralogixExportSource`: `POST /api/v1/dataprime/query` (`Bearer`), NDJSON answer. One query per time window, `limit` = `page-size`; a full window (row count = limit, `NumberOfResultsLimitWarning`, `INCOMPLETE` / `TIMED_OUT`) halves and is re-queried, down to 1ms (then fails); a window under a quarter full doubles the next, up to `initial-window`. A row becomes `userData` (object, or `{"message": text}`) plus `timestamp` / `severity` from metadata when absent and `coralogix.<key>` for labels and other metadata
- **Sources are configured, never requested (D9):** `argus.export.sources[i]` (`name`, `tenant`, `type`, `url`, `index`, credentials, …); a request names a source of its own tenant. No URL or credential comes from a request (SSRF); `ExportProperties.Source.toString` never prints credentials, and `describe()` (part of an export's identity) never includes them

### Live Tail and Metrics (P4)

[`P4_PLAN.md`](docs/architecture/P4_PLAN.md).
- **Taps (D1):** `PushIngestService` publishes a request's allowed lines once its spool append is durable (not a duplicate key, not a failed append); it parses lines for the tail even with suppression off, but only while the tenant is watched. `IngestPipeline`'s parse stage publishes each batch of a live file job (`Request.tailed()`: not a stream run, whose lines were tailed at receive, and not the replay lane). Pushed events have no `eventId`/`offset` (the generation is assigned when the spool is ingested); file events carry the archive's `eventId`
- `tail.TailHub` (`SmartLifecycle`): one `TailRing` per tenant with a tail open (or closed less than `ring-idle` ago); `isWatched` is a map lookup, so ingest builds nothing for an unwatched tenant. `publish` appends under the ring's lock and never waits. Node and per-tenant connection limits and `max-tenants` rings (a ring nobody reads is released early to make room) (`TailLimitException`). `stop()` closes every connection
- `tail.TailRing`: sequence-numbered, bounded by `ring-lines` and `ring-bytes` (each event's estimated heap: `TailEvent.weight` counts the body and every attribute, two bytes a character plus 96 per map entry), drop oldest; `read(cursor, max, wait)` reports what an overtaken cursor missed. Event ids are `<ring id>-<sequence>`, so a `Last-Event-ID` from another ring (a restart) is recognised
- `tail.TailConnection`: one platform thread per client (256KB stack; P4 D3: virtual threads would pin in blocking writes on 17/21), reading its cursor, filtering (`TailFilter`) and writing batched SSE frames through `TailWriter` (`SseEmitter` in `TailController`). Per second at most `max-lines-per-second` (200) lines sent and 50x that examined; beyond, it waits out the second and skips to the newest line, reporting the rest as dropped (lines it never examined count whether or not they match the filter, so for a filtered tail `dropped` is an upper bound). One `Tail connection finished …` canonical line per connection
- `metrics.ArgusMetrics`: counters and timers recorded where the canonical lines are emitted, per batch / request / segment / page (`argus.ingest.*`, `argus.push.*`, `argus.suppression.decisions`, `argus.archive.*`, `argus.export.*`, `argus.replay.wait`, `argus.tail.*`). `ArgusMetrics.noop()` outside Spring; injected through `ObjectProvider`, so sliced test contexts need no registry. `config.MetricsConfig`: gauges over existing state (spool and WAL pending bytes, WAL full, active jobs per lane, oldest pending archive segment, tail connections, Redis fail-open counts) and a `MeterFilter` capping the `tenant` tag at `argus.metrics.max-tenants`
- **Management port:** Prometheus at `:9001/actuator/prometheus` (`management.server.port`); the scrape names tenants and volumes, so keep the port internal. `/livez` and `/readyz` also on the API port. `config.RedisHealthConfig` reports Redis health only with a Redis suppression or quota store
- `parser.CanonicalLineParser` (`canonical`, first in `ParserRegistry.builtIns`): `[timestamp] canonical-log-line k=v …` (or `canonical_log_line`, an ISO prefix, or none); bare integers, decimals and booleans typed only when the number keeps the value (a leading zero, more than a long or more than 15 significant digits stays text); a `[bracket]` that isn't a time is kept as `prefix`; logfmt pairs with `KeyValueLogParser.KV` (linear time: lookbehind + possessive key); no message unless the line has one (suppression then compares whole lines)

### Hot Tier and Search (P5)

[`P5_PLAN.md`](docs/architecture/P5_PLAN.md).
- **Several sinks on one WAL (D1, ADR-7):** `wal.WalSegments` (one bean per WAL volume, `config.WalConfig`) lists `pending(sink)` per `wal.WalSink` (`ARCHIVE`, `HOT`) and `pending()` (some routed sink hasn't acknowledged: what the watermarks measure). `acknowledge(segment, sink)` writes the sink's marker (`.archived`, as in P2, or `.indexed`); under one lock, the last routed sink's acknowledgement deletes the segment, then its markers. Segments are kept (only marked) with `delete-after-upload=false` or without an archive store (they are then the cold output). Each sink fails, backs off and catches up on its own; a hot-tier outage pauses ingest only at the high watermark
- `engine.SearchEngineClient` (D8): `java.net.http`, API-key or basic auth, `engine()` (flavour and version from `GET /`, cached), statuses returned not thrown, an optional response cap (`ResponseTooLargeException`). Shared by `ElasticsearchExportSource`, the hot tier and search
- `hot.HotIndices` (D3, D4): `<prefix>-YYYY.MM.DD-shared` (routing = tenant) or `<prefix>-YYYY.MM.DD-t-<tenant>` for `argus.hot.dedicated-tenants` (lowercase), by the UTC day of the event time (the observed time when there is none, it is more than a day ahead, or outside years 0–9999). A line always maps to the same index, so re-indexing overwrites. `searchIndices` reads a dedicated tenant's own and the shared indices (its days before it was dedicated)
- `hot.HotDocument` (D5): snake_case fields like the Parquet columns (`@timestamp` in ms, `event_time_missing`, `tenant_id`, `event_id`, `severity_*`, `body`, `trace_id`, `span_id`, `service_name`, `source_*`), `attributes` / `resource` flattened; string leaves cut to 8,000 characters (Lucene rejects terms over 32,766 bytes). `hot.HotTemplates`: composable template with `dynamic: strict`, `best_compression`, `refresh_interval`, `flattened` (Elasticsearch 7.17+) or `flat_object` (OpenSearch 2.7+); versioned in `_meta`; `requireSupported` checks the configured store against the cluster. Add fields, never retype them
- `hot.HotIndexer` (D2, `SmartLifecycle`, one thread): template install and engine check on the first pass, `HotRetention` hourly (deletes only names `HotIndices` parses, older than `retention`; no ILM / ISM), then every pending segment oldest first, streamed into `_bulk` `index` actions (`_id = eventId`, routing) of ≤ `bulk-max-docs` / `bulk-max-bytes`, `filter_path`'d. 429 / 5xx items retried 1s/2s/4s; other item errors are **rejected** (counted, not retried: the cold tier keeps the line); a failed request backs off (`retry-initial` → `retry-max`) without acknowledging, and the retry resumes at the last good bulk (in memory). Lines older than the retention are skipped; unreadable segments are set aside. One `Hot segment finished …` canonical line per segment
- `search.SearchService` (D6): `SearchQuery.parse` validates every bound; the tenant `term` is in `bool.filter` beside the user's `q` clause, and only the caller's indices are read (`ignore_unavailable`; routing for shared ones). Sort `@timestamp` desc, `event_id` desc; `search_after` through `SearchCursor` (base64 JSON with a hash of the query). Slots are a `Semaphore` without a queue. One `Search finished …` canonical line per request, with `q`'s length, never its text
- **Freshness (D7):** with a hot store, `StreamRunner` flushes at least every `argus.hot.flush-interval` (10s), so pushed lines are searchable within T5's 30s
- `config.HotConfig` (only with `argus.hot.store` ≠ `none`): a cluster of the wrong engine or version fails startup; an unreachable one only warns (the indexer retries). `config.StoreCondition`: blank or `none` means no store, for both the archive and the hot tier

### Kafka as the WAL (P6a)

[`P6_PLAN.md`](docs/architecture/P6_PLAN.md), ADR-8. Opt-in with `argus.wal.store=kafka` (default `local`: everything above). Ingest, checkpoints, the archive's keys and the hot tier's ids are unchanged; only where committed segments live changes.
- `kafka.KafkaSink` (the `LogSink` port, D1, D2): one record per line, key `<tenant>/<source>`, value the envelope JSON (the local WAL's NDJSON line), headers `argus-seg` (`<generation>:<start>`) and `argus-off` (the line's source offset). `commit` flushes the producer, then sends a **commit marker** (same key, so same partition, after the lines; `argus-type=commit`, `argus-end`, `argus-lines`) and returns only once it is acknowledged, so the checkpoint advances only over lines Kafka holds. `abort` sends an abort marker, best effort. No transactions: an open 500MB segment would hold the partition's last stable offset and stall every `read_committed` consumer. Idempotent producer, `acks=all`, zstd, 4MB records
- `kafka.KafkaWal`: the shared producer and admin client; `ensureTopic` creates the topic (`create-topics`) and refuses settings that could delete lines the sinks don't have (`cleanup.policy=compact`, `retention.ms` < `min-retention`, a finite `retention.bytes`, `max.message.bytes` < 4MB; `check-topic=warn` only warns). A cluster that doesn't answer at startup only warns
- `kafka.ArchiveConsumer` (group `argus-archive`, D3): `SegmentStager` writes each line to its segment's staging file (`staging-dir/tenant=T/dt=kafka/source=S/gen=G/START.staging`, zstd); a line whose source offset isn't past the staged one is a replay and restarts the file; the commit marker renames it `START-END.ndjson.zst` and the **P2 `Archiver` archives it unchanged** (same keys, same `ArchiveIndex` supersede, kept in the staging root). Offsets are committed only up to each partition's oldest segment not yet archived, so a restart or rebalance re-reads it. Abort markers and segments silent for `orphan-timeout` (15m) are dropped. An archive failure pauses the partitions (still polling) and backs off; any other failure restarts from the committed offsets. Keys and headers are validated like ingest's (they become paths; the topic may hold records Argus didn't write)
- `kafka.HotConsumer` (group `argus-hot`, D4): records (markers skipped) fill a `hot.HotBulkWriter` bulk (the `_bulk` code shared with `HotIndexer`), sent when full or after `hot-linger` (1s); offsets committed after the bulk. A failed bulk pauses, backs off and is resent whole. One `Hot bulk finished …` canonical line per bulk
- `kafka.KafkaWalCapacity` (the `WalCapacity` port, D5): Kafka deletes by retention, not by acknowledgement, so the watermarks measure each routed group's lag in records (admin client, every 5s, on its own thread: a push never waits on Kafka) against `argus.wal.*-watermark` × `max-lag-records` (100M). Same `paused: WAL_FULL` and 429s as the local WAL. A broker outage is not "full": writers block in `send`/`flush` (32MB producer buffer) for up to `max-outage` (10m), so the bounded queues stop the readers and pushes land in their spools until `max-spool-bytes`
- `kafka.WalForwarder` (D6): at startup, local segments some routed sink hasn't acknowledged are sent to the topic as segments, then acknowledged locally
- `config.KafkaConfig` (only with `argus.wal.store=kafka`); `config.SinkConfig` makes `LocalVolumeSink` the `LogSink` otherwise; `WalConfig`, the `Archiver` bean and the `HotIndexer` bean exist only with `local`

### Filter Architecture

**Interfaces** (`dev.dinesh.argus.filter`)
- `LogFilter`: Single responsibility interface for filtering log records
- `FilterSpec`: Combines multiple filter criteria (time range, levels, regex patterns)

**Implementations** (`dev.dinesh.argus.filter.impl`)
- `TimeRangeFilter`: Filters logs by timestamp range
- `LevelFilter`: Filters by log level (INFO, ERROR, DEBUG, etc.)
- `RegexFilter`: Pattern matching against log messages

### Suppression (P1b)

`dev.dinesh.argus.suppression`: `Suppressor.check(tenant, message)` allows a message once per tenant per window (10s) and denies and counts copies until the window ends. Messages over 64K characters are rejected (400). Key = `<tenant>:supp:` + SHA-256/128 of the trimmed message (`template-fingerprint` also replaces UUIDs, hex ids and numbers). Uses ingest time; file ingest bypasses it (guardrail 8).
- `SuppressionStore` port. `LocalSuppressionStore` (default): Caffeine, `asMap().compute` is the atomic decision (and counts a copy under the same lock eviction takes), a fixed expiry that updates don't extend, bounded by `max-keys`; a window that denied copies logs one `Suppressed key=… count=N` summary when it ends. `RedisSuppressionStore`: one Lua call (`SET NX PX`, else `INCR` + `PTTL`), fails open (ALLOW) on any Redis error with at most one warning per window; no end-of-window summaries yet (P4)
- Wired by `config.SuppressionConfig` from `argus.suppression.store`

### Aggregation Architecture

**Interfaces** (`dev.dinesh.argus.aggregate`)
- `Aggregator<T>`: `compute(List<LogRecord>)`, for callers that already hold the records
- `StreamingAggregator<A, T> extends Aggregator<T>`: `newState()`, `accept(state, record)`, `merge(a, b)`, `result(state)` (the `Collector` shape); memory depends on the result, not the input. Every built-in aggregator implements it

**Implementations** (`dev.dinesh.argus.aggregate.impl`)
- `CountByLevelAggregator`: Groups logs by severity level and counts
- `TopNEndpointsAggregator`: Extracts endpoints from HTTP request logs and ranks by frequency (Misra–Gries, 100k counters: exact up to 100k distinct endpoints, bounded error beyond)
- `CountAggregator`: number of records
- Level counts keep at most 64 distinct levels (JSON/kv levels are free text); later ones go under `other`
- `ErrorRateAggregator`: Calculates error rate per time bucket (hourly by default; v1 `/aggregate` uses minutes)
- `DurationAggregator`: Computes time duration statistics (configurable time unit)

### Code Formatting

The project uses the **Google style** code formatter via the `fmt-maven-plugin`. The `local` profile is **active by default**, so *any* Maven build (`mvn compile`, `mvn test`, …) rewrites Java files in `src/main/java` and `src/test/java`, including untracked ones. Use `-P ci` (or `-P '!local'`) to build without reformatting.

To manually format:
```bash
mvn fmt:format -P local
```

## Current State & Known Gaps

Verified against the code; fix these before or alongside scaling work:
- **v1 `/parse`, `/parse/regexp` and `/filter` still read whole files:** they return every record, so `LogService.readLines` keeps `Files.readAllLines` behind the 50MB guard (over it, v1 answers 500, as it always has: v1 has no error mapping). Aggregations stream.
- **Access-log dates use `Locale.ROOT`** (English `Sep`, as Nginx writes). Local `nginx.log` files generated before this change on an `en_GB` / `en_IN` JVM contain `Sept` and are now rejected; regenerate them with `DataSeeder`.
- **No multiline framing:** a Java stack trace becomes one record per line (each continuation line is `raw` / `unparsed` unless it matches a format). Logback `%ex` is ignored for the same reason.
- **Records without an event time** (JSON / kv lines with no timestamp field, or a Logback layout whose `%d` has no date, e.g. `%d{HH:mm:ss.SSS}`, whose time is kept as the `timestamp` attribute) land in `dt=unknown`, and v1's time filters and time buckets skip them.
- **Archive gaps:** WAL segments not yet uploaded are lost if the node's disk is lost. `compression` defaults to `none` (NDJSON is ≈4x the input; set `zstd` with an archive store). The GCS store is tested against fake-gcs-server and a protocol stub, not real GCS; S3 only against S3Mock. One archiver per WAL volume.
- **Redis is only used by the optional Redis suppression and quota stores:** `spring.cache.type=redis` is configured, but there is no `@EnableCaching` / `@Cacheable` anywhere.
- **Suppression applies to push only:** file ingest bypasses it by design (guardrail 8). Redis-mode windows have no end-of-window summary.
- **Static API keys only:** tenants come from configured API keys (no rotation API, expiry or identity provider yet). Quotas cover push ingest bytes only (no storage or tail-connection quotas yet).
- **Push gaps (P3a):** no OTLP or Fluent Bit `forward` protocol (agents use their HTTP / NDJSON outputs); streams can't be deleted through the API; tested with an HTTP client, not real Fluent Bit or Vector.
- **Export and replay gaps (P3b):** Coralogix is tested against a protocol stub, not the real API; Elasticsearch / OpenSearch against 8.15 and 2.17 containers. After an expired point in time, Elasticsearch exports can repeat hits sharing one timestamp. An export of a range still receiving data isn't repeatable page for page. A replay re-reads a whole file (`fromBeginning`) or an export's range; there is no byte-range replay of a file. Thread priorities are advisory on Linux; the read budget is the isolation. One Elasticsearch page (5,000 hits) is held in memory as text and JSON.
- **Hot tier gaps (P5):** tested against OpenSearch 2.17 and Elasticsearch 8.15 containers, not a managed service; no AWS SigV4 (Amazon OpenSearch Service with IAM auth). Attribute values are indexed as text (no numeric ranges on attributes). One indexer thread, oldest segment first: a tenant's backlog delays others. A tenant moved from dedicated back to shared loses its dedicated days from search. A line more than a day in the future is indexed under its observed day, so a search by its event time can miss it. No search of the cold tier. With a hot store, stream runs every 10s make 6x more (smaller) Parquet objects
- **Kafka WAL gaps (P6a):** tested against Apache Kafka 3.8 and Redpanda 24.2 containers (one broker), not a managed cluster; no SASL/IAM for MSK beyond passing `argus.kafka.properties.*`. Pushed lines are only on local disk (the spool) until their stream run, up to `flush-interval`. One instance: several instances need P6b (instance ids in identities). The archive index lives in the staging root, so a partition moving to another instance (P6b) would miss it. Lag watermarks count records, not bytes
- **Live tail gaps (P4):** one instance's view only (no shared tail across instances); a restart loses the rings (a reconnect gets `reset`; no warm-up from the WAL). Browsers' `EventSource` can't send the API key header: use a fetch-based SSE client. A disconnected client is noticed at its next write (≤ `heartbeat`), unless a proxy keeps the server side open (Docker Desktop's port proxy does): its slot is then held until `max-duration`. Spring logs a WARN (`AsyncRequestNotUsableException`) when a tail client disconnects mid-write
- **v1 time buckets** (`errorRate`, `duration`) grow with the time span the file covers (per-minute buckets: ~525k a year).

## Target Architecture & Guardrails

Full decomposition, capacity maths, STAR-framed ADRs and roadmap: **[`docs/architecture/SCALING_DESIGN.md`](docs/architecture/SCALING_DESIGN.md)**. Inputs: `PLAN.md` (distributed target) and `SKILL.md` (format matrix).

**Sizing target:** 10GB/day (~36M lines, ~4k lines/s peak) from files, streams and centralized logging exports (Coralogix / ELK), on one node with **4GB RAM / 100GB SSD**. Output goes to S3, GCS or a Kubernetes PVC. Shape: **modular monolith first** (ports and adapters), so stage boundaries can later become Kafka topics.

**Pipeline (each stage is a port with pluggable adapters, extending the existing Strategy pattern):**
```
source → framing → parser (ParserRegistry/FormatDetector) → pipeline (normalize → PII mask → suppress 10s → enrich)
       → WAL → sink (OpenSearch hot 1-14d | Parquet+ZSTD on S3/GCS/PVC cold 1-7y)
                 ↳ tail (SSE live tail, after suppression)      checkpoint ← sink acks
```
Planned packages: `framing`, alongside the existing `kafka`, `hot`, `search`, `engine`, `wal`, `tail`, `metrics`, `source`, `parser`, `pipeline`, `sink`, `archive`, `checkpoint`, `export`, `suppression`, `quota`, `tenant`, `model` (`LogEnvelope`), `filter` and `aggregate` (`StreamingAggregator`).

**Guardrails (every change must follow these):**
1. **Never load unbounded data.** Stream sources in ≤500MB segments and process ≤10,000-line / ≤8MB micro-batches. No `readAllLines`, no `List` of a whole file.
2. **Every inter-stage queue is bounded** and declares its overflow policy (block, 429 + `Retry-After`, spill to WAL, or DLQ). Pull sources pause; push sources return 429.
3. **At-least-once delivery with idempotent sinks:** advance a source checkpoint only once its data is durable in the WAL (the committed local segments), and delete a WAL segment only after every routed sink acks it. Writes are keyed by a deterministic `eventId = hash(tenantId, sourceId, offset)` or offset-derived object names.
4. **Tenant isolation:** every record carries `tenantId`, which is resolved from credentials, never from the body. Every cache key, Redis key, index and object prefix starts with it (after at most a fixed deployment prefix shared by all tenants, such as `argus.archive.prefix`).
5. **Memory budget (4GB container):** `-XX:MaxRAMPercentage=60` (≈2.4GB heap), `-XX:MaxDirectMemorySize=512m` for Parquet / ZSTD / upload buffers.
6. **Local SSD is a working area, not the hot tier.** 14 days of raw logs (140GB) doesn't fit, so the WAL is capped at 30GB with high/low watermarks, and segments are deleted only after all sinks ack.
7. **Live tail never backpressures ingest:** per-connection bounded buffer, drop oldest, `{"dropped":N}` marker.
8. **Suppression uses ingest time** and **fails open** (duplicates are better than lost logs). Replay/backfill bypasses suppression and runs on a lower-priority lane.
9. **Emit one canonical log line per batch/request** (Stripe-style) with offsets, counts and latencies, in a `finally` block.
10. **Decide with STAR before building:** any new stage, store or format gets a short STAR ADR in the design doc first.

## Key Design Patterns

1. **Strategy Pattern**: Pluggable parsers (`LogParser` interface) and aggregators (`Aggregator<T>` interface)
2. **Composite Pattern**: `FilterSpec` and `LogFilter` allow combining multiple filter conditions
3. **Service Facade**: `LogService` abstracts complexity of multi-step log processing workflows
4. **Repository Pattern**: `DataSeeder` generates synthetic logs for testing and development

## Configuration

Key properties in `application.properties`:
- `server.port=9000`: Service port
- `log.max-file-size=52428800`: Max file size in bytes (50MB default) for the v1 endpoints that return every record
- `log.max-concurrent-aggregations=2`: v1 streaming aggregations at once; more get 429
- `log.enforce-allowed-roots=true`: v1 reads only under `argus.ingest.allowed-roots`; `false` warns instead (migration window)
- `argus.tenancy.*`: `mode` (`api-key` | `header`), `api-keys[i].tenant` / `api-keys[i].key-sha256` / `api-keys[i].allowed-roots` (a tenant's own input directories; all of a list's entries must come from one property source)
- `spring.cache.type=redis` + `spring.data.redis.*`: Redis settings (caching is not enabled in code; Redis is used only by the Redis suppression store). `timeout=100ms` / `connect-timeout=200ms` so that store fails open quickly
- `argus.suppression.*`: `window` (`10s`, at least 1ms), `max-keys` (`100000` windows in the local store), `store` (`local` | `redis`), `template-fingerprint` (`false`)
- `spring.devtools.*`: LiveReload and restart configuration for local development
- `argus.ingest.*`: streaming ingest. `batch-max-lines` / `batch-max-bytes` (in-memory unit), `segment-bytes` (commit unit), `raw-queue-capacity` / `parsed-queue-capacity`, `max-line-bytes` (longer lines are truncated and counted), `max-concurrent-jobs`, `allowed-roots`, `checkpoint-dir`, `keep-unparsed` (default `true`)
- `argus.sink.local.*`: `root` (output volume; the WAL when an archive store is set), `min-free-bytes` (a new segment fails fast below this) and `compression` (`none` | `zstd`)
- `argus.push.*` (P3a): `spool-dir`, `max-request-bytes` (`8MB` decompressed), `max-request-lines` (`10000`), `max-concurrent-requests` (`16`), `flush-interval` (`1m`), `max-spool-bytes` (`2GB` not yet ingested), `spool-rotate-bytes` (`64MB`), `max-streams-per-tenant` (`32`), `max-concurrent-runs` (`2`), `idempotency-window` (`1024`), `suppress` (`true`)
- `argus.quota.*`: `store` (`local` | `redis`), `ingest-bytes-per-second` (`2MB`, decompressed push bytes), `burst-seconds` (`2`), `tenants.<id>.ingest-bytes-per-second`
- `argus.replay.*` (P3b): `max-concurrent-jobs` (`1`), `bytes-per-second` (`8MB`, shared by every replay job; measured in T8 on 2 CPUs), `max-pending-bytes` (`256MB`: an export pauses while its stream holds this much)
- `argus.tail.*` (P4): `max-connections` (`256`), `max-connections-per-tenant` (`16`), `max-lines-per-second` (`200` per connection), `max-duration` (`1h`), `heartbeat` (`15s`), `ring-lines` (`10000`), `ring-bytes` (`4MB` of estimated heap per tailed tenant), `ring-idle` (`1m`), `max-tenants` (`32` tenants with a ring; 429 for another)
- `argus.hot.*` (P5): `store` (`none` | `opensearch` | `elasticsearch`), `url`, `api-key` / `username` / `password` (from the environment), `index-prefix` (`argus-logs`), `dedicated-tenants`, `retention` (`14d`), `shards` (`1`), `replicas` (`1`), `refresh-interval` (`5s`), `flush-interval` (`10s`: stream runs at least this often with a hot store), `scan-interval` (`1s`), `bulk-max-docs` (`5000`), `bulk-max-bytes` (`5MB`), `retry-initial` / `retry-max` (`1s` / `5m`), `request-timeout` (`30s`). Set `argus.sink.local.compression=zstd` with a hot store
- `argus.search.*` (P5): `max-range` (`14d`), `max-size` (`500`), `max-concurrent-searches` (`8`), `max-response-bytes` (`32MB`), `timeout` (`10s`, then `partial: true`)
- `argus.metrics.max-tenants` (`100`); `management.server.port` (`9001`, Prometheus at `/actuator/prometheus`), `management.health.redis.enabled=false` (Redis health only with a Redis store)
- `argus.export.sources[i].*` (P3b): `name`, `tenant`, `type` (`elasticsearch` | `coralogix`), `url`, `index` (Elasticsearch), `timestamp-field` (`@timestamp`), `api-key` / `username` / `password` (from the environment), `page-size` (`5000`, ≤50,000), `keep-alive` (`10m`), `tier` (`frequent_search` | `archive`), `initial-window` (`1h`), `request-timeout` (`60s`)
- `argus.wal.store` (`local` | `kafka`, P6a); `argus.kafka.*`: `bootstrap-servers`, `topic` (`argus-wal`), `create-topics` (`false`), `check-topic` (`fail` | `warn`), `partitions` (`12`) / `replication-factor` (`3`) for a topic Argus creates, `min-retention` (`7d`), `max-outage` (`10m`), `max-lag-records` (`100000000`), `orphan-timeout` (`15m`), `commit-interval` (`5s`), `hot-linger` (`1s`), `retry-initial` / `retry-max`, `staging-dir`, `properties.*` (passed to every client: `security.protocol`, `sasl.*`, `ssl.*`, from the environment)
- `argus.wal.*` (only with an archive or hot store; with Kafka the watermarks are fractions of `max-lag-records`): `max-bytes` (`30GB` of pending segments), `high-watermark` (`0.8`: ingest pauses, new jobs get 429), `low-watermark` (`0.6`: ingest resumes)
- `argus.archive.*`: `store` (`none` | `filesystem` | `s3` | `gcs`), `prefix`, `delete-after-upload` (`true`), `scan-interval` (`10s`), `staging-dir`, `row-group-size` (`64MB`), `retry-initial` / `retry-max` (`1s` / `5m`), `filesystem.root` (must be outside the WAL), `s3.bucket` / `s3.region` / `s3.endpoint` / `s3.path-style` / `s3.checksums` (`when-required` for S3-compatible stores that reject SDK checksums), `gcs.bucket` / `gcs.endpoint` / `gcs.anonymous` (emulators)
- Runtime data (`./data/` by default) is git-ignored

## Testing Notes

- Unit tests use JUnit 5 and Mockito
- Test files are located in `src/test/java/dev/dinesh/argus/`
- Parser tests: `ParserContractTest` (every parser: never throws, honours `canParse`, thread-safe), `FormatParsersTest` (format-specific cases), `ParserRegistryTest` (detection and fallback order), `LogParserTest`
- Aggregator tests verify statistical calculations, that merging split inputs equals one pass (every `StreamingAggregator`), and the top-N bounds
- Suppression tests: the exact window boundary with a fake clock/ticker, one ALLOW among concurrent calls, summaries and the size bound (`LocalSuppressionStoreTest`), the Redis store against a real Redis (`RedisSuppressionStoreTest`, Testcontainers, skipped without Docker) and fail-open (`RedisSuppressionStoreFailOpenTest`), the API (`SuppressionControllerTest`). `SuppressionLatencyIT` (tag `load`) checks p99 < 1ms
- Filter tests verify filtering conditions
- `ArgusWebApplicationTest` starts the full context, so bean wiring and property binding errors fail `mvn test`
- Ingest tests cover offsets and resume (`FileSourceTest`), crash-safe checkpoints (`FileCheckpointStoreTest`), idempotent segments (`LocalVolumeSinkTest`), backpressure / crash-rerun / cancel (`IngestPipelineTest`), and job slots and path checks (`IngestJobServiceTest`, `IngestControllerTest`)
- Push tests: `StreamSpoolTest` (acknowledged appends, startup truncation of unacknowledged bytes and a torn index entry, keys across restarts, compaction, sealing and numbering, the run-due clock), `PushIngestServiceTest` (every bound and its reason, gzip and gzip bombs, truncation, suppression, keys, tenant isolation, request slots), `StreamRunnerTest` (real pipeline: age / size / sealed-file triggers, one generation per spool file, a file job holding the source, restart), `StreamControllerTest` (statuses and `Retry-After`), `LocalQuotaStoreTest` / `RedisQuotaStoreTest` / `QuotaServiceTest`. Load: `PushIngestIT`, `PushBackpressureIT`, `PushCrashIT` (shared `PushLoad` / `PushServerMain`)
- Replay and export tests (P3b): `ByteRateLimiterTest`, lanes in `IngestJobServiceTest` / `IngestPipelineTest` (a paced run writes the same output; cancel while waiting) / `IngestControllerTest`, `ExportJobServiceTest` (in-memory source: order, cursor after each page, a crash before the cursor re-fetches one page that the key drops, resume vs `fromBeginning` vs another export, retries, pause on spool, read budget, one export per stream, validation and tenant isolation), `ElasticsearchExportSourceTest` (Testcontainers, Elasticsearch and OpenSearch: ties across pages, `[from, to)`, query, expired point in time, errors), `CoralogixExportSourceTest` (protocol stub: halving and growth, limit warning, `INCOMPLETE`, request shape, row → line, errors), `ExportPropertiesTest`, `ExportControllerTest`. Load: `ReplayIsolationIT` (T8)
- Hot tier and search tests (P5): `WalSegmentsTest` (pending per sink, the last acknowledgement deletes segment and markers, kept segments only marked, P2 `.archived` markers honoured, concurrent acknowledgements, an unrouted sink can't acknowledge), `WatermarkWalCapacityTest`, `ArchiveConfigTest` (routing to each configured sink; an unreachable cluster only warns), `SearchEngineClientTest` (stub: flavour and version, auth, statuses, response cap), `HotIndicesTest`, `HotDocumentTest`, `HotIndexerTest` (protocol stub: bulk sizes, 429 items retried, rejections counted, a failed request resumes at the last good bulk, retries exhausted, outside-retention lines, unreadable segments, the action's index / id / routing), `HotTierEngineTest` (OpenSearch 2.17 and Elasticsearch 8.15 via `engine.EngineContainers`: template and mixed attribute types, store mismatch, retention, a segment indexed twice stored once, tenant isolation whatever `q` / `attr` say, paging across shared timestamps, filters, dedicated tenants), `SearchQueryTest`, `SearchControllerTest` / `SearchControllerWithoutHotTierTest`, `StreamRunnerTest` (flush with a hot store). Load: `HotTierIT` (T5 freshness, hot-tier outage, `kill -9` while indexing, the mapping's disk saving; `-Dargus.load.hot-engine=elasticsearch`)
- Kafka WAL tests (P6a), on Apache Kafka 3.8 and Redpanda 24.2 via `kafka.KafkaContainers`: `KafkaSinkTest` (lines then the commit marker in one partition, empty segments, abort markers, topic checks and creation), `SegmentStagerTest` (no broker: interleaved segments, replays restart staging, abort, orphans, revoked partitions, records Argus didn't write, the archive index kept), `ArchiveConsumerTest` (the same objects and keys as the local archiver, a segment re-sent after a crash archived once, a restart re-reads a segment not yet archived), `HotConsumerTest` (protocol stub: markers skipped, a failed bulk resent, nothing resent after a restart), `KafkaWalCapacityTest` (lag hysteresis, an unreachable cluster), `WalForwarderTest`, `KafkaConfigTest` (beans per `argus.wal.store`). Load: `KafkaWalIT`, and `HotTierIT` with `-Dargus.load.wal=kafka`
- Tail and metrics tests (P4): `TailRingTest` (sequences, line and byte bounds, overtaken cursors, waits), `TailHubTest` (tenant isolation, filters, limits, a stalled writer never blocks publishing and is told what it missed, rate cap, `Last-Event-ID` resume and `reset`, heartbeats, idle ring release, shutdown), `TailControllerTest` (real server: 401 / 400 for `Accept: text/event-stream`, SSE headers, a pushed line reaching its tenant only, 429), taps in `PushIngestServiceTest` / `IngestPipelineTest` (`RecordingTail`), `ArgusMetricsTest` (tenant tag cap), metrics counts in `IngestPipelineTest` / `PushIngestServiceTest`, `MetricsEndpointTest` (Prometheus on the management port, health UP without Redis, probes on the API port; needs `@AutoConfigureObservability`, as tests turn metrics export off), canonical cases in `FormatParsersTest` / `ParserContractTest`. Load: `LiveTailIT` (T7 freshness, 16 stalled clients, 1,000 connections)
- Archive tests: the Parquet round trip (`EnvelopeParquetWriterTest`, read back Hadoop-free via `ParquetTestFiles`), every store against `ObjectStoreContractTest` (`S3ObjectStoreTest` on S3Mock and `GcsObjectStoreTest` on fake-gcs-server, skipped without Docker; `GcsResumableUploadTest` covers chunk resume against a protocol stub), `WatermarkWalCapacityTest` (hysteresis, cancel while paused), and `ArchiverTest` (keys, delete vs mark, store failures and backoff, superseded copies, a segment replaced mid-upload, unreadable segments) on real WAL segments (`WalFixture`)

## Common Development Tasks

### Adding a new log format parser
1. Create a class implementing `LogParser` in `dev.dinesh.argus.parser`: a stable `name()`, a cheap `canParse()` with no false negatives, and `parse()` returning null (never throwing) for lines it doesn't own. Put the event time, level and message in `LogRecord`, and everything else in a mutable `meta` map
2. Add it to `ParserRegistry.builtIns` at the right priority (earlier wins detection ties and is tried first as a fallback). A format that needs configuration (like `logback`) is resolved in `ParserRegistry.resolve` instead
3. Add it to `ParserContractTest.parsers()` and add format-specific cases to `FormatParsersTest`
4. Optionally extend `DataSeeder` to generate sample data in the new format

### Adding a new aggregation function
1. Create a class implementing `StreamingAggregator<A, T>` in `dev.dinesh.argus.aggregate.impl` (state must stay bounded however large the file)
2. Implement `compute(List<LogRecord>)` method
3. Add unit test in `AggregatorTest`
4. Add it to `AggregatorTest.aggregators()` (merge equals one pass) and to the aggregator map in `LogService.summarize()` or `aggregate()`

### Adding a new filter type
1. Create a class implementing `LogFilter` in `dev.dinesh.argus.filter.impl`
2. Implement `matches(LogRecord)` method
3. Update `LogController.aggregateLogsV2()` to accept the new filter type if needed

### Adding a Source, Sink or pipeline stage (target architecture)
1. Implement the port (`LogSource`, `LogSink`, or `PipelineStage`) in its package (`source`, `sink`, `pipeline`)
2. Sources must emit `LogBatch` with a resumable `SourceOffset` and respect backpressure (block / pause / 429)
3. Sinks must be idempotent (offset-derived object names or `_id = eventId`), and `SegmentWriter.commit` must return only once the segment is durable
4. Run the shared port contract test so the adapter is substitutable (LSP)
5. Check it against the Guardrails above and the ADRs in `docs/architecture/SCALING_DESIGN.md` §11

## External Dependencies

- **Spring Boot 3.3.3**: Web framework and dependency injection
- **Jackson**: JSON serialization/deserialization
- **OpenCSV**: CSV export functionality
- **SpringDoc OpenAPI 2.5.0**: Swagger/OpenAPI documentation
- **Lombok**: Reduces boilerplate (annotations like `@Data`, `@Slf4j`)
- **Spring Data Redis**: the optional Redis suppression store (Lettuce)
- **Caffeine**: the local suppression store
- **Testcontainers** (test, pinned to 1.21.4 for Docker Engine 29+): a real Redis for `RedisSuppressionStoreTest`, S3Mock for `S3ObjectStoreTest` (MinIO no longer publishes images to Docker Hub)
- **Parquet** (`parquet-hadoop` 1.15, with only the shaded `hadoop-client-api`) and **zstd-jni**: the archive format
- **AWS SDK v2** (`s3`, async Netty client; the Apache client is excluded): `S3ObjectStore`
- **google-auth-library-oauth2-http**: Application Default Credentials for `GcsObjectStore` (6MB; `google-cloud-storage` would be 43MB)
- **kafka-clients** (managed by Spring Boot; not spring-kafka, Argus owns the commit protocol): Kafka as the WAL (P6a). Testcontainers `kafka` and `redpanda` modules for its tests
- **Spring DevTools**: Live reload and restart during development

---

## Staff Engineer Level Code Review

Comprehensive code reviews following SOLID, Clean Code, DRY, KISS principles and production-grade standards.

### Quick Review Commands

#### Current Branch Changes (Production-Grade Review)
Review the diff on current branch against main with focus on:
- SOLID principles violations
- Clean Code violations
- Performance and security issues
- Design pattern misuse
- Production readiness

```bash
# Run code review on pending changes with effort level set to high (more thorough)
/code-review high

# Run ultra-detailed review in the cloud (multi-agent, industry-standard)
/code-review ultra

# Same but save findings as inline PR comments (requires GitHub PR)
/code-review high --comment
```

#### Full Codebase Architecture & Quality Audit
Comprehensive analysis of the entire codebase for structural issues:

```bash
# Audit entire codebase for architecture, design, and code quality issues
# This explores the full project against Staff Engineer standards
/code-review ultra src/main/java --comment
```

### Review Dimensions & Standards

#### 1. SOLID Principles
**S - Single Responsibility Principle**
- Each class should have one reason to change
- Services should not mix concerns (e.g., `LogService` should focus on log operations, not export formatting)
- Filters, parsers, and aggregators each handle one concern

**O - Open/Closed Principle**
- Classes should be open for extension, closed for modification
- Strategy pattern in `LogParser` and `Aggregator<T>` interfaces
- New parsers/aggregators added without modifying existing code

**L - Liskov Substitution Principle**
- Implementations must be substitutable for their interfaces
- All `LogParser` implementations must work interchangeably
- All `Aggregator<T>` implementations must return compatible results

**I - Interface Segregation Principle**
- Clients should not depend on interfaces they don't use
- `LogFilter` is minimal (single `matches()` method)
- `LogParser` provides only essential parsing operations

**D - Dependency Inversion Principle**
- Depend on abstractions, not concrete classes
- `LogController` depends on `LogService` interface/abstraction
- Aggregators and filters are injected/composed as needed

#### 2. Clean Code Standards
- **Meaningful Names**: Classes (`CountByLevelAggregator`, `TimeRangeFilter`) clearly express intent
- **Small Functions**: Methods should fit on one screen, single purpose
- **Comments**: Only WHY, not WHAT—well-named code is self-documenting
- **Error Handling**: Graceful degradation (e.g., `parseLineSafely()` filters nulls)
- **No Code Duplication**: Reusable components via Strategy pattern
- **Formatting**: Google style enforced via Maven plugin

#### 3. DRY (Don't Repeat Yourself)
- **Reusable Parsers**: `LogParser` interface eliminates duplicate parsing logic
- **Reusable Filters**: `FilterSpec` and `LogFilter` allow composable filtering
- **Reusable Aggregators**: `Aggregator<T>` interface enables new aggregations without code duplication
- **Shared Utilities**: `JsonUtils`, `Waits` for common operations

#### 4. KISS (Keep It Simple, Stupid)
- **Simple Abstractions**: Three-layer architecture (controller → service → model)
- **Straightforward APIs**: REST endpoints map clearly to operations
- **Minimal Configuration**: Redis cache and file size limits only
- **Predictable Behavior**: Composable filters follow single concern principle

#### 5. Production Standards
- **Error Handling**: Safe parsing with null handling, file size validation
- **Logging**: SLF4J with appropriate levels (info, debug, error)
- **Testing**: Unit tests for core logic (parsers, aggregators, filters)
- **Configuration Management**: Properties-based config, no hardcoding
- **Performance**: Streaming logs, lazy evaluation where applicable
- **Security**: Input validation (regex patterns), file size limits
- **Monitoring**: Actuator endpoints available for health checks

### Review Checklists

#### Architecture Review
- [ ] Layering: Controller → Service → Model clearly separated
- [ ] Strategy pattern correctly applied to parsers/aggregators
- [ ] Composite pattern for filter composition
- [ ] No circular dependencies between layers
- [ ] Service layer doesn't expose internal details
- [ ] Controllers are thin (business logic in service)
- [ ] Proper abstraction levels (interfaces, concrete classes)

#### SOLID Review
- [ ] No god objects (methods doing multiple concerns)
- [ ] Each class has one reason to change
- [ ] Can extend functionality without modifying existing code
- [ ] Implementations are substitutable for interfaces
- [ ] Classes don't depend on unused interface methods
- [ ] Abstractions, not concrete implementations are dependencies

#### Clean Code Review
- [ ] Variable/method/class names clearly express intent
- [ ] No cryptic abbreviations (except standard ones)
- [ ] Methods ≤20 lines, functions single-purpose
- [ ] Comments explain WHY, not WHAT
- [ ] No TODO/FIXME comments without context
- [ ] Consistent indentation and formatting (Google style)
- [ ] Exception handling is specific, not generic
- [ ] No magic numbers (use constants instead)

#### DRY Review
- [ ] No duplicate code across parsers/filters/aggregators
- [ ] Reusable components extracted to shared modules
- [ ] Utility functions (JsonUtils, Waits) centralized
- [ ] Configuration not duplicated in multiple files
- [ ] Test data generation centralized (DataSeeder)

#### KISS Review
- [ ] No premature optimizations
- [ ] Algorithms straightforward and understandable
- [ ] Simple data structures (Map, List, not complex custom)
- [ ] Configuration minimal and clear
- [ ] No unnecessary abstractions
- [ ] Straightforward test cases

#### Production-Grade Review
- [ ] All paths have error handling
- [ ] Input validation at boundaries (controller layer)
- [ ] File size limits enforced
- [ ] Sensitive operations logged
- [ ] Configuration externalized
- [ ] No hardcoded values
- [ ] Thread-safe where needed (LogService uses streams safely)
- [ ] Graceful degradation on failures
- [ ] Performance acceptable for expected load

### Common Issues to Look For

**Architecture**
- ❌ Service layer mixing concerns (parsing + caching + validation all in one class)
- ✅ Focused services with clear responsibilities

**SOLID Violations**
- ❌ Adding new parser requires modifying existing parser classes (violates OCP)
- ✅ New parsers added by implementing interface, no existing code changed

**Clean Code**
- ❌ `processData()`, `doStuff()`, `temp`, `x` as variable names
- ✅ `parseServerAccessLog()`, `countByLevel()`, `errorRateOverTime()`

**DRY**
- ❌ Same regex pattern in multiple parser classes
- ✅ Pattern defined once, reused across parsers

**KISS**
- ❌ Complex custom caching layer implemented from scratch
- ✅ Using Spring's built-in Redis caching

**Production Issues**
- ❌ No file size validation before loading entire file into memory
- ✅ File size checked, exception thrown if >50MB limit

### How to Run Reviews Locally

#### Review Current Changes
```bash
# High-effort review with reasoning
/code-review high

# Medium effort, faster feedback
/code-review medium

# Ultra review (multi-agent, comprehensive)
/code-review ultra

# Save as inline comments (requires GitHub)
/code-review high --comment
```

#### Review Specific File or Module
```bash
# Review a specific package for design issues
/code-review high src/main/java/dev/dinesh/argus/filter

# Review controller layer
/code-review high src/main/java/dev/dinesh/argus/controller

# Review parsers and their SOLID compliance
/code-review high src/main/java/dev/dinesh/argus/parser
```

#### Full Codebase Architecture Review
```bash
# Comprehensive architecture audit
/code-review ultra src/main/java
```

### When to Escalate Issues

**Critical (Fix Before Merge)**
- SOLID principles violated (OCP, SRP)
- Security vulnerabilities (input validation, injection attacks)
- Memory leaks or performance bottlenecks
- Missing error handling in critical paths
- Production standards not met

**Important (Should Fix)**
- Clean Code violations that impact maintainability
- DRY violations causing maintenance burden
- Missing test coverage for critical logic
- Unclear naming or confusing logic

**Nice to Have (Consider)**
- Performance optimizations not critical for current load
- Refactoring for better readability when code is working
- Style improvements beyond Google format

### Integration with Development Workflow

1. **Before Creating PR**: Run `/code-review high` locally
2. **In PR**: Use `/code-review high --comment` to highlight issues for team review
3. **Architecture Reviews**: Quarterly `/code-review ultra` on full codebase
4. **Onboarding**: New developers run `/code-review medium` to understand patterns
5. **Refactor Planning**: Use review findings to prioritize technical debt

### Related Commands

- `/code-review`: Quick-access help for reviews
- `/simplify`: Applies suggested simplifications to code
- `mvn fmt:format -P local`: Auto-format with Google style
- `/verify`: Run app and verify changes work as expected
