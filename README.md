# Argus Log Processing Service

A Spring Boot service that ingests, parses, filters, aggregates and archives logs. It streams files of any size with bounded memory, detects the log format by itself, keeps each tenant's data apart, and archives to Parquet + ZSTD on a Kubernetes volume or S3.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Sizing target:** 10GB/day (~36M lines, ~4k lines/s peak) on one node with 4GB RAM and a 100GB SSD.

| | |
|---|---|
| [FEATURES.md](FEATURES.md) | Everything Argus does today, by area |
| [CHANGELOG.md](CHANGELOG.md) | What changed, merged PR by merged PR |
| [ROADMAP.md](ROADMAP.md) | What's next (P5) and the open items |
| [docs/architecture/SCALING_DESIGN.md](docs/architecture/SCALING_DESIGN.md) | Target architecture, capacity maths, ADRs |
| [CLAUDE.md](CLAUDE.md) | Code map, guardrails and conventions for contributors |

## What's new

- **Kafka or Redpanda as the WAL (P6a).**
  - With `argus.wal.store=kafka`, committed segments go to a Kafka topic instead of the node's disk, so losing the node loses at most the push spools' last few seconds. The archive and the hot tier consume it as groups; the archive's object keys don't change.
  - Ingest pauses with 429 `WAL_FULL` when a sink falls far enough behind that Kafka's retention could delete lines it hasn't stored; a broker outage is backpressure, absorbed by the push spools.
  - 130–132k lines/s pushed end to end at a 256MB heap, the same as the local WAL. A 60s broker outage, an archive outage and a `kill -9` lost and duplicated nothing, on Apache Kafka 3.8 and Redpanda 24.2. Pushed lines are searchable in 22.3s (p99).
- **Hot search tier and the search API (P5, PR #11).**
  - With `argus.hot.store=opensearch|elasticsearch`, every line is also indexed into OpenSearch 2.7+ or Elasticsearch 7.17+ and kept for 14 days; `GET /api/v2/search` finds a tenant's lines by time range, text, level, source, service and attributes.
  - The hot tier reads the WAL on its own, as the archive does: either can be down without stopping the other, and a segment leaves the WAL only once both have it. Re-indexing (after a crash, a retry or an outage) never duplicates a line: each document's id is its `eventId`.
  - Tenancy is enforced by the server: the tenant filter comes from the API key and sits beside, never inside, the caller's query.
  - On 2 CPUs at the target peak (4,150 lines/s), a pushed line is searchable in 11.8s (p50) / 20.1s (p99), within T5's 30s. A 60s cluster outage and a `kill -9` while indexing lost and duplicated nothing. The strict mapping is 65.6% (OpenSearch) / 37.4% (Elasticsearch) smaller on disk than the default dynamic mapping.
- **Live tail, metrics and canonical lines (P4, PR #10).**
  - `GET /api/v2/tail` streams a tenant's lines as Server-Sent Events as they are accepted, after suppression, filtered by `level`, `source` and `q`. A pushed line reaches a tail in 39ms (p99, 2 CPUs).
  - One bounded ring per tailed tenant: publishing never waits, so a slow client only loses lines (`{"dropped": N}`) and can't slow ingest. On 2 CPUs, 16 tail clients that never read cost push latency no more than 16 that read; 1,000 open connections fit in a 256MB heap.
  - Prometheus metrics on a separate management port (`:9001/actuator/prometheus`): rates, outcomes and latencies of ingest, push, suppression, archive, exports, replay and tail; spool, WAL and archive-lag gauges.
  - Health no longer reports DOWN when Redis is unreachable but unused.
  - `canonical` parser for Stripe-style `canonical-log-line k=v …` lines from the applications Argus ingests.
- **Exports from Elasticsearch, OpenSearch and Coralogix, and a replay lane (P3b, PR #9).**
  - `POST /api/v2/ingest/exports` copies a time range from a configured source into a stream: Elasticsearch or OpenSearch with a point in time and `search_after`, Coralogix with its DataPrime query API in adaptive time windows. Each page is durable in the spool before its cursor is saved, so an interrupted export resumes where it stopped, without duplicating a page.
  - Re-reads (`fromBeginning`) and exports run on a **replay lane**: their own job slots and a shared read budget (8MB/s), so a backfill never takes live ingest's slots or bandwidth. Suppression and the push quota don't apply to replays.
  - Sources, URLs and credentials are configured per tenant; a request only names a source.
  - On 2 CPUs, a 1GB replay left live push latency unchanged within noise; the same re-read without the lane made it 3–8x slower.
- **HTTP push ingest, quotas and suppression on push (P3a, PR #8).**
  - `POST /api/v2/ingest/streams/{id}` takes NDJSON or plain lines, optionally gzipped, from agents such as Fluent Bit and Vector. A request is acknowledged only once it is fsynced in the stream's spool, and the ordinary pipeline ingests the spool shortly after.
  - `Idempotency-Key` makes a retried request a no-op, even across a restart. Tested with `kill -9` mid-load: nothing acknowledged was lost or duplicated.
  - Per-tenant ingest quotas (token bucket, local or Redis), and every limit answers with its own status: 429 + `Retry-After`, 413, 409 or 415.
  - Repeated messages are suppressed as they arrive (10s window).
  - 132k lines/s end to end at a 256MB heap, 32x the target.
- **Backpressure, a compressed WAL and GCS (P2b).**
  - When the archive falls behind (e.g. S3 or GCS is down), ingest **pauses** at the WAL's high watermark instead of failing, then resumes. Tested with a 30s outage during a 500MB ingest at a 256MB heap.
  - `argus.sink.local.compression=zstd` makes the local WAL 10x smaller, so 30GB holds about a week of backlog.
  - `argus.archive.store=gcs`: native Google Cloud Storage with Workload Identity.
- **Parquet archive (P2a, PR #5).**
  - Ingested logs are converted to Parquet + ZSTD and uploaded to a directory (PVC) or to S3 (GCS since P2b). The local output becomes a write-ahead buffer, so an S3 outage doesn't stop ingest.
  - **14.6x smaller than the raw input** on a 1GB synthetic test.
  - Opt-in: `argus.archive.store=filesystem|s3`.
- **API keys and tenants (PR #4).**
  - Every `/api/**` call, v1 included, needs an API key, which resolves the tenant.
  - Checkpoints, output, event ids and jobs are kept per tenant.
  - v1 reads only files under the caller's allowed directories.
- **10-second suppression API and streaming aggregation (P1b, PR #3).**
  - `POST /api/v2/suppression/check` allows a message once per tenant per 10s window, with a p99 of 8.8µs.
  - v1 aggregations stream files of any size.
- **Format detection and a canonical record (P1a, PR #2).**
  - Access log, JSON, Kubernetes CRI, key-value, raw text and Logback layouts.
  - One OpenTelemetry-style `LogEnvelope` with a deterministic `eventId`.
- **Streaming ingest (P0, PR #1).**
  - 10GB files in a 4GB container, at a peak heap of 810MB.
  - Crash-safe checkpoints: a `kill -9` loses nothing.

Details: [CHANGELOG.md](CHANGELOG.md).

## Prerequisites

- **JDK 17 or 21** to build. Not 24+: the Lombok version Spring Boot 3.3.3 manages crashes on JDK 24. If your default JDK is newer, prefix Maven with `JAVA_HOME=/path/to/jdk-17`.
- Maven 3.8+
- Optional:
  - **Docker**, for the Redis and S3 tests (Testcontainers; they're skipped without it).
  - **Redis**, only for `argus.suppression.store=redis`.
  - An **S3 bucket** (or S3-compatible store) or a **GCS bucket**, only for `argus.archive.store=s3|gcs`.

## Build and run

```bash
git clone git@github.com:DineshKuppan/argus-log-processor.git
cd argus-log-processor

mvn clean package -DskipTests            # the default "local" profile also reformats the code (Google style)
java -jar target/argus-log-processor-0.1.0.jar
# or: mvn spring-boot:run
```

The service listens on **http://localhost:9000**. Swagger UI is at **http://localhost:9000/swagger-ui.html**.

For a 4GB node:
```bash
java -XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError \
  -jar target/argus-log-processor-0.1.0.jar
```

## Quick start

### 1. Create an API key
The shipped configuration has no keys, so every API call answers **401**. Configuration stores only each key's SHA-256:

```bash
printf %s "my-secret-key" | shasum -a 256      # → <64 hex chars>
```
```properties
argus.tenancy.api-keys[0].tenant=acme
argus.tenancy.api-keys[0].key-sha256=<64 hex chars>
# Optional: directories only this tenant may read (otherwise it shares argus.ingest.allowed-roots)
argus.tenancy.api-keys[0].allowed-roots=./data/in/acme
```
Send the key as `X-Argus-Api-Key: my-secret-key` or `Authorization: Bearer my-secret-key`.

For local experiments only, `argus.tenancy.mode=header` trusts a caller-chosen `X-Argus-Tenant` header instead.

### 2. Ingest a file (v2, asynchronous, any size)
```bash
curl -X POST localhost:9000/api/v2/ingest/file \
  -H 'X-Argus-Api-Key: my-secret-key' -H 'Content-Type: application/json' \
  -d '{"path":"./src/main/resources/logs/app.json","sourceId":"app"}'
# → 202 {"jobId":"…","state":"RUNNING",…}

curl localhost:9000/api/v2/ingest/jobs/<jobId> -H 'X-Argus-Api-Key: my-secret-key'
```
- **Format:** detected from the first 100 lines, or set with `format` (`nginx_access`, `json`, `cri`, `kv`, `raw`, `logback`) or `pattern` (a Logback layout).
- **Output:** NDJSON `LogEnvelope`s under `data/out/tenant=acme/dt=…/source=app/gen=…/`.
- **Resume:** a rerun of the same `sourceId` continues from its checkpoint. A rotated or truncated file starts again as a new generation.

### 3. Aggregate a file (v1, synchronous, streamed)
```bash
curl -X POST localhost:9000/api/v1/logs/aggregate \
  -H 'X-Argus-Api-Key: my-secret-key' -H 'Content-Type: application/json' \
  -d '{"file":"./src/main/resources/logs/app.json","topN":5}'
```

### 4. Ask whether to print a message (10s suppression)
```bash
curl -X POST localhost:9000/api/v2/suppression/check \
  -H 'X-Argus-Api-Key: my-secret-key' -H 'Content-Type: application/json' \
  -d '{"message":"disk full on /var"}'
# → {"decision":"ALLOW",…}; the same message again within 10s → {"decision":"DENY","suppressedCount":1,…}
```

### 5. Push lines from an agent
```bash
printf '{"ts":"2026-10-28T12:00:00Z","level":"ERROR","msg":"disk full"}\n' | gzip | \
  curl -X POST localhost:9000/api/v2/ingest/streams/web --data-binary @- \
  -H 'X-Argus-Api-Key: my-secret-key' -H 'Content-Type: application/x-ndjson' \
  -H 'Content-Encoding: gzip' -H 'Idempotency-Key: batch-0001'
# → 202 {"streamId":"web","accepted":1,"suppressed":0,"spool":1,"startOffset":0,"endOffset":64,…}
# The same Idempotency-Key again → 200 with the original offsets; nothing is written twice

curl localhost:9000/api/v2/ingest/streams/web -H 'X-Argus-Api-Key: my-secret-key'   # spool and run status
```
- **When it's ingested:** a stream run starts once a line has waited `argus.push.flush-interval` (1 minute) or a segment's worth is waiting. Output lands next to file output, under `source=web`.
- **Retries:** send a unique `Idempotency-Key` per batch for exactly-once. Without one, a retried batch may be ingested twice.
- **Limits:** 429 + `Retry-After` when the tenant is over its quota (`argus.quota.ingest-bytes-per-second`, 2MB/s), the WAL or spool is full, or 16 requests are already in progress; 413 over 8MB decompressed or 10,000 lines.

Fluent Bit (`json_date_key timestamp`: its default key, `date`, isn't read as the event time):
```ini
[OUTPUT]
    Name      http
    Match     *
    Host      argus.example
    Port      9000
    URI       /api/v2/ingest/streams/web
    Format    json_lines
    Compress  gzip
    json_date_key timestamp
    Header    X-Argus-Api-Key my-secret-key
```
Vector:
```toml
[sinks.argus]
type = "http"
inputs = ["app_logs"]
uri = "http://argus.example:9000/api/v2/ingest/streams/web"
compression = "gzip"
encoding.codec = "json"
framing.method = "newline_delimited"
request.headers.X-Argus-Api-Key = "my-secret-key"
```
Both send only static headers, so their retries are at-least-once.

### 6. Export from Elasticsearch, OpenSearch or Coralogix
```properties
argus.export.sources[0].name=elk
argus.export.sources[0].tenant=acme
argus.export.sources[0].type=elasticsearch          # OpenSearch too; told apart by GET /
argus.export.sources[0].url=https://es.internal:9200
argus.export.sources[0].index=logs-*
argus.export.sources[0].api-key=${ELK_API_KEY}       # or username / password
argus.export.sources[1].name=cx
argus.export.sources[1].tenant=acme
argus.export.sources[1].type=coralogix
argus.export.sources[1].url=https://api.eu2.coralogix.com
argus.export.sources[1].api-key=${CX_API_KEY}
```
```bash
curl -X POST localhost:9000/api/v2/ingest/exports -H "X-Argus-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"source":"elk","streamId":"elk-2026-10","query":{"term":{"service":"checkout"}},
       "from":"2026-10-01T00:00:00Z","to":"2026-10-02T00:00:00Z"}'
# → 202 {"jobId":"…","lane":"REPLAY","state":"QUEUED",…}; poll GET /api/v2/ingest/exports/{jobId}
# Coralogix takes DataPrime text: "query":"source logs | filter $l.subsystemname == 'checkout'"
```
- **Resuming:** posting the same export again (same source, query and range, same stream) continues from its saved cursor; `"fromBeginning":true` exports it again.
- **Pace:** exports and `fromBeginning` file jobs share `argus.replay.bytes-per-second` (8MB/s) and `argus.replay.max-concurrent-jobs` (1). An export pauses while its stream has 256MB not yet ingested.
- **Delivery:** every page once, with one exception: if an Elasticsearch point in time expires mid-export (`keep-alive`, 10m without a page), hits sharing the last exported timestamp can be exported twice.

### 7. Watch it live
```bash
curl -N localhost:9000/api/v2/tail?level=warn -H "X-Argus-Api-Key: $KEY"
# : connected
# id: 3f2a91c0-41
# data: {"source":"web","format":"json","severityText":"error","severityNumber":17,"body":"disk full",…}
# event: dropped
# data: {"dropped":1250}        ← this client missed up to this many lines (too slow, or over 200 lines/s)
```
- **Filters:** `level` (minimum severity), `source` (a source or stream id), `q` (body substring, ignoring case).
- **Resume:** reconnecting with `Last-Event-ID` continues after the last line seen while it is still in the tenant's ring (10,000 lines); otherwise `event: reset`.
- **Limits:** 16 connections per tenant, 256 per node (429 + `Retry-After`); connections close after an hour.
- **Browsers:** `EventSource` can't send the API key header; use a fetch-based SSE client.

### 8. Metrics
```bash
curl -s localhost:9001/actuator/prometheus | grep '^argus_'
# argus_ingest_lines_total{lane="live",outcome="primary",tenant="acme"} 2000000.0
# argus_push_requests_total{outcome="rejected_quota",tenant="acme"} 12.0
# argus_wal_pending_bytes 1.2884901888E10
```
The management port (9001) names every tenant and its volumes: keep it internal. Kubernetes probes: `/livez` and `/readyz` on port 9000.

### 9. Search the last 14 days (optional hot tier)
```properties
argus.hot.store=opensearch                # or elasticsearch; checked against the cluster at startup
argus.hot.url=https://search.internal:9200
# argus.hot.api-key / username / password from the environment (ARGUS_HOT_API_KEY, …)
argus.hot.dedicated-tenants=bigco         # tenants with their own daily indices; the rest share one, routed by tenant
argus.sink.local.compression=zstd         # recommended: the WAL holds the lines while the cluster is down
```
```bash
curl -G localhost:9000/api/v2/search -H "X-Argus-Api-Key: $KEY" \
  --data-urlencode from=2026-11-01T09:00:00Z --data-urlencode to=2026-11-01T10:00:00Z \
  --data-urlencode 'q=payment -retry' --data-urlencode level=error --data-urlencode attr=http_status:503
# → {"hits":[{"eventId":"…","timestamp":"…","severityText":"ERROR","body":"payment failed","attributes":{"http_status":503},…}],
#    "next":"eyJoIjoi…","partial":false,"tookMs":14}
# Next page: the same parameters plus cursor=<next>
```
- **Index layout:** `argus-logs-YYYY.MM.DD-shared` (or `…-t-<tenant>`) per day of event time, deleted after `argus.hot.retention` (14 days) by Argus itself, on either engine.
- **Freshness:** with a hot store, pushed lines are ingested at least every 10s (`argus.hot.flush-interval`), so they are searchable within 30s.
- **Bounds:** `from`/`to` required, at most 14 days apart and within the hot window (older lines are in the archive); `q` searches the body (`"phrases"`, `-not`, `a | b`, `prefix*`; no regexes); `size` ≤500; 8 searches at once per node (429), 503 while the cluster is down.
- **Outages:** the WAL holds what the cluster hasn't indexed; past the WAL's high watermark, ingest pauses (429), as for an archive outage.

### 10. Archive to Parquet (optional)
```properties
# A directory or Kubernetes PersistentVolume…
argus.archive.store=filesystem
argus.archive.filesystem.root=./data/archive

# …or S3. Credentials come from the AWS SDK's default chain (env, profile, IRSA, instance role)
argus.archive.store=s3
argus.archive.s3.bucket=my-log-archive
argus.archive.s3.region=eu-west-1

# …or GCS, with Application Default Credentials (Workload Identity, a service account, gcloud)
argus.archive.store=gcs
argus.archive.gcs.bucket=my-log-archive

argus.archive.prefix=argus/prod
# Recommended with any store: a zstd WAL, 10x smaller than NDJSON
argus.sink.local.compression=zstd
```
- **Keys:** `<prefix>/tenant=T/dt=YYYY-MM-DD/hour=HH/source=ID/gen=G/FROM-TO.parquet`, Hive-partitioned for Athena and BigQuery.
- **Lifecycle:** each local segment is deleted once its upload succeeds.
- **S3-compatible stores:** set `s3.endpoint`, `s3.path-style=true` and, if the store rejects the AWS SDK's checksums, `s3.checksums=when-required`.
- **Backpressure:** when more than 80% of `argus.wal.max-bytes` (30GB) is waiting for upload, ingest pauses (job status `paused: WAL_FULL`) and new jobs get 429. It resumes below 60%.

### 11. Kafka as the WAL (optional)
```properties
argus.wal.store=kafka
argus.kafka.bootstrap-servers=kafka-0.kafka:9092,kafka-1.kafka:9092
argus.kafka.topic=argus-wal              # created with argus.kafka.create-topics=true, else must exist
# argus.kafka.properties.security.protocol=SASL_SSL, sasl.*, ssl.* (from the environment)
```
- Committed segments go to a Kafka topic (Apache Kafka 3.x or Redpanda) instead of the local disk, so a lost node loses at most the push spools' last `flush-interval`. The archive and the hot tier consume it as groups `argus-archive` and `argus-hot`; the archive's object keys are the same as with the local WAL.
- Ingest pauses when a sink's consumer lag passes 80% of `argus.kafka.max-lag-records` (100M), before Kafka's retention could delete lines it hasn't archived. Startup refuses a topic that is compacted, keeps less than 7 days or deletes by size.
- A broker outage blocks the writers (backpressure) for up to `argus.kafka.max-outage` (10m); pushes keep landing in their spools meanwhile.
- Switching from the local WAL: restart with `argus.wal.store=kafka`; segments not yet archived or indexed are sent to the topic first.

## API

Every endpoint needs an API key: 401 without one. Request bodies are JSON.

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/v2/ingest/file` | Start a streaming ingest job: `path`, optional `sourceId`, `fromBeginning`, `format`, `pattern`, `lane` (`live` / `replay`; `replay` by default with `fromBeginning`). 202, or 400 / 404 / 409 (source busy) / 429 + `Retry-After` (the lane's job slots busy, or the WAL full while the archive catches up) |
| POST | `/api/v2/ingest/exports` | Export `[from, to)` from a configured source into a stream: `source`, `streamId`, `query`, `from`, `to`, `fromBeginning`. 202, or 400 / 404 (no such source for the tenant) / 409 (an export already running on the stream) / 429 + `Retry-After` (replay slots busy, WAL full) |
| GET, DELETE | `/api/v2/ingest/exports/{id}` | Export progress (pages, lines, `paused`, cursor position) / cancel before the next page |
| GET | `/api/v2/search` | Search the hot tier (with `argus.hot.store`): `from`, `to` (required, ≤14 days, within the hot window), `q`, `level`, `source`, `service`, `attr=key:value`, `size`, `cursor` → `hits`, `next`, `partial`. 400 out of bounds, 404 without a hot tier, 429 + `Retry-After` (search slots busy), 503 + `Retry-After` (cluster busy or down) |
| GET | `/api/v2/tail` | Live tail (Server-Sent Events) of the tenant's lines: `level`, `source`, `q`, `Last-Event-ID`. `event: dropped` / `reset`; 400 bad filter, 429 + `Retry-After` at a connection or tailed-tenant limit |
| POST | `/api/v2/ingest/streams/{id}` | Push lines (NDJSON or text, optional `Content-Encoding: gzip`, `Idempotency-Key`, `format`, `pattern`). 202 once durable; 200 for a repeated key; 429 + `Retry-After` (busy, same key in progress, quota, WAL or spool full), 413, 409 (too many streams, or the id is a file job's source id), 415 |
| GET | `/api/v2/ingest/streams/{id}`, `/api/v2/ingest/streams` | A stream's (or every stream's) spool, counters and last run. Another tenant's stream is 404 |
| GET | `/api/v2/ingest/jobs/{id}` | Job progress: bytes, lines, format, segments, outputs, `paused` / `pausedMs`. Another tenant's job is 404 |
| DELETE | `/api/v2/ingest/jobs/{id}` | Cancel a job at its next batch; committed segments stay |
| POST | `/api/v2/suppression/check` | `{"message"}` → `ALLOW` / `DENY`, `windowEndsAt`, `suppressedCount` |
| POST | `/api/v1/logs/aggregate` | `file`, `topN`: counts by level, top endpoints, error rate per minute. Any file size |
| POST | `/api/v1/logs/aggregateV2` | Adds filters (`from`, `to`, `levels`, `regex`): total, counts by level, top endpoints, records per hour. Any file size |
| POST | `/api/v1/logs/export/json`, `/export/csv` | Aggregates as JSON or CSV. Any file size |
| POST | `/api/v1/logs/cli-summary` | Plain-text summary. Any file size |
| GET | `/api/v1/logs/parse?file=` | Every record of a file (≤50MB) |
| GET | `/api/v1/logs/parse/regexp?file=&pattern=` | Records whose message matches a regex (≤50MB) |
| POST | `/api/v1/logs/filter` | Filtered records (≤50MB) |

- **v1 errors:** 400 for bad input or a path outside the caller's roots, 404 for a missing file, and 429 + `Retry-After` when both aggregation slots are busy.
- **Health and metrics:** `:9001/actuator/health` and `:9001/actuator/prometheus` (management port); `/livez` and `/readyz` on the API port. Redis is part of health only with a Redis suppression or quota store.

## Generate test data

`DataSeeder` streams a reproducible Nginx access log of any size to disk:
```bash
java -Dloader.main=dev.dinesh.argus.seeder.DataSeeder -cp target/argus-log-processor-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher data/in/big.log 10GB
```
The repository includes `app.json` (25,000 JSON lines) and `k8s.cri.txt` in `src/main/resources/logs/`.

## Tests

```bash
mvn test                                         # 534 tests; Redis, S3, GCS, Elasticsearch, OpenSearch, Kafka and Redpanda tests need Docker
mvn -P ci test                                   # same, without reformatting the code
mvn test -Dgroups=load -DexcludedGroups=none     # load: 1GB ingest + archive at 256MB heap, T3 archive outage, 1M suppression
                                                 # decisions, push throughput / isolation, push backpressure, kill -9 during push,
                                                 # T8 replay isolation, T7 live tail, T5 hot-tier freshness, a hot-tier outage and
                                                 # kill -9 while indexing (-Dargus.load.docker-cpus=2 runs the server in a 2-CPU
                                                 # container); Kafka as the WAL (KafkaWalIT, host only: T1, broker and archive outages,
                                                 # kill -9; -Dargus.load.kafka-broker=redpanda; HotTierIT with -Dargus.load.wal=kafka)
```

## License
This project is licensed under the MIT License; see the [LICENSE](LICENSE) file for details.
