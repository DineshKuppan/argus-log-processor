# Changelog

All notable changes to Argus. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

No versions have been tagged yet (the pom says `0.1.0`), so changes are grouped by merged pull request, newest first. Each phase's plan and measured results are in [`docs/architecture/`](docs/architecture/).

## [Unreleased]

### P6b: several instances (unmerged)
Plan and results: [`P6_PLAN.md`](docs/architecture/P6_PLAN.md) D8–D13, §9.

#### Added
- **`argus.instance.id`:** each instance's sources get their own identity. The id joins every `eventId`, archive key (`instance=I/`), the Parquet `source_instance` column and the hot tier's `source_instance` field (template v2, which also adds the field to existing indices). Without an id, every identity is unchanged.
- **Several instances in the shared consumer groups:** the archive index moves into the object store, so whichever instance owns a partition supersedes a replayed segment's earlier copy. A consumer whose client id is already a member of its group waits for that member to leave instead of joining.
- **A live tail shared through a Kafka topic** (`argus.kafka.tail-topic`, `argus-tail`):
  - a tail on any instance sees every instance's lines, within 54ms (p99) across instances;
  - `Last-Event-ID` resumes on another instance;
  - a full bus drops and counts lines (`argus_tail_bus_dropped_total`) and never waits.
- **Suppression decided in pipelined batches:** one Redis round trip per 1,000 lines. A 10,000-line push's suppression takes 681ms (p99) with Redis, instead of 10,000 round trips.
- **Graceful drain on shutdown** (`argus.instance.drain-timeout`, 90s):
  1. readiness goes down;
  2. new pushes get 503 + `Retry-After: 1`;
  3. pushes in progress finish;
  4. every spool is ingested.
- **Job ids name their instance;** another instance's job answers 404 naming it.
- **`deploy/`:** a Dockerfile, plus Kubernetes manifests: a StatefulSet, services, an ingress with push affinity, a KEDA scaler on consumer lag and an HPA alternative. A `kind` overlay; the `kind` check scaled 1 → 3 on archive lag and back to 1, with each pod draining.
- `MultiInstanceIT` and `SuppressionAcrossInstancesIT` (tag `load`).

#### Changed
- `ObjectStore.get` (for the archive index) on every store.
- Argus's Kafka consumers set `allow.auto.create.topics=false`. Brokers should run with `auto.create.topics.enable=false`: a broker-created `argus-wal` has unsafe defaults, which Argus refuses.

#### Measured
- **`kill -9` of one of three instances under load:** exactly 300,000 of 300,000 lines in both the archive and the hot tier, on Apache Kafka 3.8 and Redpanda 24.2.

### P6a: Kafka as the WAL: [#12](https://github.com/DineshKuppan/argus-log-processor/pull/12) (2026-11-01)
Plan and results: [`P6_PLAN.md`](docs/architecture/P6_PLAN.md).

#### Added
- **`argus.wal.store=kafka`** (default `local`): committed segments go to a Kafka topic (`argus.kafka.*`) instead of the local volume. `KafkaSink` writes one record per line, keyed `<tenant>/<source>`, then a commit marker per segment; the source checkpoint advances only once the marker is acknowledged (ADR-8). No transactions, so lines are visible at once.
- **The sinks as consumer groups:** `argus-archive` rebuilds each segment in local staging and archives it with the unchanged P2 archiver, so the cold tier's keys are the same as with the local WAL; `argus-hot` indexes records as they arrive. Each commits offsets only for what its sink has.
- **Lag watermarks:** ingest pauses (429 `WAL_FULL`, `paused: WAL_FULL`) when a sink's consumer lag passes `argus.wal.high-watermark` × `argus.kafka.max-lag-records` (100M), measured off the request path. Startup refuses topic settings that could delete lines the sinks don't have.
- A broker outage blocks the writers for up to `argus.kafka.max-outage` (10m); pushes keep landing in their spools.
- `WalForwarder`: switching from the local WAL sends its unacknowledged segments to the topic at startup.
- Metrics: `argus_kafka_lag_records{group}`, `argus_kafka_lag_age_seconds{group}`, `argus_kafka_segments_total{side,outcome}`, `argus_kafka_send_blocked_seconds`, and kafka-clients' own producer metrics.
- Broker tests on Apache Kafka 3.8 and Redpanda 24.2 (Testcontainers); `KafkaWalIT` (tag `load`); `HotTierIT -Dargus.load.wal=kafka`.

#### Changed
- `HotBulkWriter` holds the `_bulk` code `HotIndexer` had, shared with the hot consumer; one backoff helper for the archiver, indexer and consumers. A non-JSON `_bulk` answer fails the pass instead of setting a segment aside (from #11's review).
- `LocalVolumeSink` is created by `SinkConfig` (only with the local WAL); `WalConfig`, the `Archiver` bean and the `HotIndexer` bean exist only with `argus.wal.store=local`.

#### Fixed (in review, before merge)
- The archive consumer could archive a partial copy of a segment over its complete one: after a restart or rebalance it resumes at a committed offset that another source's open segment can hold inside an already archived segment. Commit markers now carry `argus-first` (the Kafka offset of the segment's first line), and a copy staged from past it is dropped.
- An archive outage longer than `orphan-timeout` expired the open segments of the held partitions and let the commit pass their first lines. The orphan clock now stops while the consumer holds its partitions, and `orphan-timeout` must be longer than `max-outage`.

#### Measured
- **T1:** 131.9k lines/s pushed to Apache Kafka, 130.1k to Redpanda, the same as the local WAL in the same runs (125.6k / 131.7k), at a 256MB heap.
- **T3:** a 60s broker outage under 2,000 lines/s was absorbed by the spools (no 429s), then all 180,800 lines were archived exactly once; an unwritable archive store paused ingest at the lag watermark (156–160 429 `WAL_FULL`), then all 180,400 lines were archived once. Both brokers.
- **T4:** `kill -9` mid-push: exactly 400,000 rows archived for 400,000 lines, both brokers.
- **T5 on Kafka:** p50 9.9s, p99 22.3s from the 202 to the search API finding the line (host, OpenSearch). A 60s engine outage and a `kill -9` while indexing lost and duplicated nothing.

### P5: hot search tier and the search API: [#11](https://github.com/DineshKuppan/argus-log-processor/pull/11) (2026-11-01)
Plan and results: [`P5_PLAN.md`](docs/architecture/P5_PLAN.md).

#### Added
- **Hot tier** (`argus.hot.*`, opt-in with `argus.hot.store=opensearch|elasticsearch`): `HotIndexer` indexes every committed WAL segment into OpenSearch 2.7+ or Elasticsearch 7.17+ with `_bulk`, `_id = eventId` and per-tenant routing, so retries, crashes and outages never duplicate a line. 429 / 5xx items are retried; documents the engine rejects are counted and logged (the archive keeps them); a failed request backs off and resumes at the last good bulk. One `Hot segment finished …` canonical line per segment.
  - Daily indices per UTC day of event time: `argus-logs-YYYY.MM.DD-shared` (routed by tenant) or `…-t-<tenant>` for `argus.hot.dedicated-tenants`. `HotRetention` deletes days past `retention` (14 days) on either engine, instead of ILM / ISM policies.
  - A versioned composable index template: strict mapping, `best_compression`, `refresh_interval` 5s, attributes as `flattened` (Elasticsearch) / `flat_object` (OpenSearch). The configured store is checked against the cluster at startup.
- **`GET /api/v2/search`:** a required `[from, to)` (≤14 days, within the hot window), `q` (body only; phrases, `-not`, `|`, prefixes; no regexes or fuzzy), `level`, `source`, `service`, repeatable `attr=key:value`, `size` (≤500) and a cursor; newest first. The tenant filter comes from the API key and sits beside the caller's query. 8 searches at once per node (429), 503 + `Retry-After` while the cluster is busy or down, 404 without a hot tier. One `Search finished …` canonical line per request, without the query's text.
- `engine.SearchEngineClient`: one OpenSearch / Elasticsearch client (auth, timeouts, flavour and version), shared by the export source, the hot tier and search.
- Metrics: `argus_hot_documents_total{outcome}`, `argus_hot_segments_total`, `argus_hot_bulk_seconds`, `argus_hot_lag_seconds`, `argus_search_requests_seconds`, `argus_wal_sink_pending_bytes{sink}`.
- `HotTierIT` (T5, outage, crash, mapping saving; tag `load`); `HotTierEngineTest` on OpenSearch 2.17 and Elasticsearch 8.15.

#### Changed
- **The WAL is acknowledged per sink** (ADR-7): `WalSegments` and `WatermarkWalCapacity` move to the `wal` package; each sink (archive, hot tier) writes its own marker (`.archived` as before, `.indexed`), and the last acknowledgement deletes the segment. The watermarks count segments some sink hasn't acknowledged. Without an archive store, segments are kept (they are the cold output) and only marked.
- With a hot store, stream runs happen at least every 10s (`argus.hot.flush-interval`), for T5.
- `ElasticsearchExportSource` uses the shared client; its behaviour is unchanged.

#### Measured
- **T5 (2 CPUs, 4,150 lines/s pushed, OpenSearch):** from the 202 to the search API finding the line, p50 11.8s, p99 20.1s (≤30s). Elasticsearch on the host: p99 17.8s.
- **Hot-tier outage (60s, 2 CPUs):** push and archiving went on; afterwards the index held exactly the 200,800 acknowledged lines. **`kill -9` while indexing:** exactly 160,000 documents for 160,000 lines.
- **Mapping:** 65.6% smaller on disk than OpenSearch's default dynamic mapping, 37.4% than Elasticsearch's (200,000 synthetic lines).
- **Earlier load tests after the per-sink WAL change:** T1 push 133k lines/s end to end (two runs; a first run during container shutdown gave 111k); T3 push backpressure: 218 429s, the WAL drained 20.7s after the outage, all 1M lines archived; T3 file ingest: paused 48s at the watermark during a 30s outage, then all 5.4M lines archived; T4 `kill -9`: none of 400,000 lines lost or split.

### P4: live tail, metrics and producers' canonical lines: [#10](https://github.com/DineshKuppan/argus-log-processor/pull/10) (2026-10-30)
Plan and results: [`P4_PLAN.md`](docs/architecture/P4_PLAN.md).

#### Added
- **Live tail:** `GET /api/v2/tail` (Server-Sent Events) streams the caller tenant's lines as they are accepted, after suppression: pushed lines once durable in the spool, live file jobs' lines as they are parsed (stream runs and the replay lane don't publish).
  - Filters `level`, `source`, `q`; `Last-Event-ID` resume, `event: reset` when it can't; `event: dropped` `{"dropped":N}` for lines a client missed; a heartbeat every 15s.
  - `TailHub` keeps one ring per tailed tenant (10,000 lines / 4MB of estimated heap, counting each event's attributes; released a minute after its last connection, or earlier when all 32 rings (`max-tenants`) are taken). Publishing never waits; each connection reads the ring with its own cursor on its own thread, at most 200 lines a second (`argus.tail.*`).
  - Limits: 16 connections per tenant, 256 per node, 32 tailed tenants (429 + `Retry-After: 30`), one hour per connection. One `Tail connection finished …` canonical line per connection.
- **Metrics:** Micrometer with the Prometheus registry at `:9001/actuator/prometheus` (`management.server.port`). Counters are recorded where the canonical lines are emitted (`argus_ingest_*`, `argus_push_*`, `argus_suppression_decisions_total`, `argus_archive_*`, `argus_export_*`, `argus_replay_wait_seconds`, `argus_tail_*`); gauges for spool and WAL pending bytes, WAL full, active jobs per lane, archive lag, tail connections and Redis fail-opens. The `tenant` tag is capped at `argus.metrics.max-tenants` (100).
- `/livez` and `/readyz` on the API port.
- **`canonical` parser:** `[timestamp] canonical-log-line k=v …` (or `canonical_log_line`), with numbers and booleans typed when the number keeps the value (`id=00123` stays text); first in detection priority.
- `LiveTailIT` (T7, tag `load`); `PushLoad.Server.residentKilobytes()`.

#### Changed
- Redis appears in `/actuator/health` only with a Redis suppression or quota store (`management.health.redis.enabled=false`; `RedisHealthConfig`), so health is UP without Redis by default.
- The 401 for a missing API key, and the tail's 400 / 429, set their content type, so clients sending `Accept: text/event-stream` get them instead of a 500.
- Push parses lines for the tail even with suppression off, but only while the tenant is tailed.
- ADR-6: stay on Java 17; platform threads per tail connection, virtual threads once on Java 24+.
- `KeyValueLogParser`'s pair pattern (now shared with `canonical`) runs in linear time: a run of key characters with no `=` was retried from every position, so a 1MB line took ~45 minutes (40,000 characters: 4.6s).

#### Measured
- **T7 freshness (2 CPUs, 4GB container):** push-to-tail p50 6.9ms, p99 38.9ms (300 pushes); a file job's first line reached the tail 77ms after the job was submitted.
- **T7 isolation (2 CPUs):** under ~10k lines/s of pushes, 16 tail clients that never read cost push p99 −1.5% to +6.5% against 16 that read (4 runs), with throughput unchanged; the stalled clients were then told what they missed. Tailing itself (16 reading clients against none) cost −4% to +17% of push p99.
- **P3a push load tests after P4:** T1 146k lines/s end to end (P3a: 132k), request p99 97ms, peak heap 249MB of 256MB; T3 196 429s, the WAL drained in 15.8s after the outage and all 1M lines were archived; T4 kill -9 after 590 of 800 batches lost and duplicated nothing.
- **ADR-6:** 1,000 open tail connections at a 256MB heap (peak 189MB); one pushed line reached all of them, fan-out p99 484ms on 2 CPUs (171ms on the host, where the server's resident memory was 511MB).

### P3b: export sources and the replay lane: [#9](https://github.com/DineshKuppan/argus-log-processor/pull/9) (2026-10-30)
Plan and results: [`P3_PLAN.md`](docs/architecture/P3_PLAN.md) §8, §10.

#### Added
- **Exports:** `POST /api/v2/ingest/exports` (`GET` / `DELETE /exports/{id}`) copies `[from, to)` from a configured source into a stream, as a replay-lane job.
  - `ElasticsearchExportSource`: Elasticsearch 7.12+ or OpenSearch 2.x (detected from `GET /`), point in time + `search_after`, `_source` as the line. Each flavour sorts with the tiebreaker it supports; an expired point in time is replaced.
  - `CoralogixExportSource`: the DataPrime query API, one query per adaptive time window (halved when full, doubled when sparse).
  - Each page is appended to the stream spool under an `Idempotency-Key` derived from the export, the run and the page, and only then is the cursor saved (`checkpoint-dir/tenant=T/_export/<stream>.json`). Re-posting an export resumes it; `fromBeginning` runs it again; a crash re-fetches one page, which the key drops.
  - Transient failures are retried 3 times (1s, 2s, 4s); 4xx and bad queries fail at once.
  - Sources are configured (`argus.export.sources[i]`), each for one tenant; requests never carry URLs or credentials, and credentials are never logged.
  - One `Export page finished …` canonical line per page and `Export finished …` per job.
- **Replay lane** (`argus.replay.*`): `fromBeginning` file jobs (or `lane: replay`) and exports run on their own slots (1) with one shared read budget (8MB/s) and minimum-priority threads. An export also pauses while its stream has 256MB not yet ingested, or the spool is half full.
- `ReplayIsolationIT` (T8, tag `load`); `-Dargus.load.docker-cpus=N` runs any push load test's server in an N-CPU, 4GB container.

#### Changed
- `POST /api/v2/ingest/file` takes `lane`; job status shows it. A busy lane's 429 names the lane.
- `IngestPipeline.Request` gains a read limit and low-priority stage threads (`onReplayLane`).

#### Measured
- **T8:** with the application in a 2-CPU, 4GB container, live push p99 during a 1GB replay at 8MB/s was 15.6ms and 20.1ms in two runs, against 15.8–27.9ms in the windows before and after (no measurable slowdown). The same re-read unthrottled on the live lane raised it to 65–179ms. At the planned 16MB/s, p99 rose 44%, so the default is 8MB/s.

### P3a: HTTP push ingest, quotas and suppression on push: [#8](https://github.com/DineshKuppan/argus-log-processor/pull/8) (2026-10-29)
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
- `SuppressionStore.release`: a pushed request whose spool write fails gives back its suppression windows, so its retry isn't suppressed.
- Load tests (tag `load`), with the application in its own 256MB JVM: `PushIngestIT` (T1 and tenant isolation), `PushBackpressureIT` (T3 for push), `PushCrashIT` (T4 for push).

#### Changed
- File jobs and stream runs share `SourceLeases`, so a source has one run at a time whichever started it. A 409 for a busy source now names its owner. A file job can't use a push stream's id (409), nor a stream a file source's id.
- `IngestPipeline.Request` gains `endOffset` and `pathIsIdentity`; `ResumePoint` gains `PATH_CHANGED`.
- `JsonLogParser`: fractional epoch seconds keep their fraction (Fluent Bit's `json_date_format double`).
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
