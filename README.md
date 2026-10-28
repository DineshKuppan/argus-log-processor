# Argus Log Processing Service

A Spring Boot service that ingests, parses, filters, aggregates and archives logs. It streams files of any size with bounded memory, detects the log format by itself, keeps each tenant's data apart, and archives to Parquet + ZSTD on a Kubernetes volume or S3.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Sizing target:** 10GB/day (~36M lines, ~4k lines/s peak) on one node with 4GB RAM and a 100GB SSD.

| | |
|---|---|
| [FEATURES.md](FEATURES.md) | Everything Argus does today, by area |
| [CHANGELOG.md](CHANGELOG.md) | What changed, merged PR by merged PR |
| [ROADMAP.md](ROADMAP.md) | What's next (P3b → P5) and the open items |
| [docs/architecture/SCALING_DESIGN.md](docs/architecture/SCALING_DESIGN.md) | Target architecture, capacity maths, ADRs |
| [CLAUDE.md](CLAUDE.md) | Code map, guardrails and conventions for contributors |

## What's new

- **HTTP push ingest, quotas and suppression on push (P3a).**
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

Fluent Bit (its HTTP output's defaults put the time in `date`, which Argus reads):
```ini
[OUTPUT]
    Name      http
    Match     *
    Host      argus.example
    Port      9000
    URI       /api/v2/ingest/streams/web
    Format    json_lines
    Compress  gzip
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

### 6. Archive to Parquet (optional)
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

## API

Every endpoint needs an API key: 401 without one. Request bodies are JSON.

| Method | Endpoint | What it does |
|---|---|---|
| POST | `/api/v2/ingest/file` | Start a streaming ingest job: `path`, optional `sourceId`, `fromBeginning`, `format`, `pattern`. 202, or 400 / 404 / 409 (source busy) / 429 + `Retry-After` (job slots busy, or the WAL full while the archive catches up) |
| POST | `/api/v2/ingest/streams/{id}` | Push lines (NDJSON or text, optional `Content-Encoding: gzip`, `Idempotency-Key`, `format`, `pattern`). 202 once durable; 200 for a repeated key; 429 + `Retry-After` (busy, quota, WAL or spool full), 413, 409 (too many streams), 415 |
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
- **Health:** `/actuator/health`. It reports DOWN when Redis isn't reachable, even with the in-memory suppression store; see [ROADMAP.md](ROADMAP.md).

## Generate test data

`DataSeeder` streams a reproducible Nginx access log of any size to disk:
```bash
java -Dloader.main=dev.dinesh.argus.seeder.DataSeeder -cp target/argus-log-processor-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher data/in/big.log 10GB
```
The repository includes `app.json` (25,000 JSON lines) and `k8s.cri.txt` in `src/main/resources/logs/`.

## Tests

```bash
mvn test                                         # 340 tests; Redis, S3 and GCS tests need Docker
mvn -P ci test                                   # same, without reformatting the code
mvn test -Dgroups=load -DexcludedGroups=none     # load: 1GB ingest + archive at 256MB heap, T3 archive outage, 1M suppression
                                                 # decisions, push throughput / isolation, push backpressure, kill -9 during push
```

## License
This project is licensed under the MIT License; see the [LICENSE](LICENSE) file for details.
