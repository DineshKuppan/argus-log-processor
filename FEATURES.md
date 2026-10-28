# Features

What Argus does today, by area. Planned work is in [ROADMAP.md](ROADMAP.md); the history is in [CHANGELOG.md](CHANGELOG.md).

## Ingest (v2)
| Feature | Details |
|---|---|
| Streaming file ingest | `POST /api/v2/ingest/file` runs an asynchronous job over a file of any size. Memory depends on queue sizes, not the file: a 10GB file ran in a 4GB container at an 810MB peak heap |
| Bounded pipeline | Read → parse → write threads joined by bounded queues (2 and 4 batches). A full queue blocks the stage before it |
| Batches and segments | Each batch holds ≤10,000 lines or ≤8MB. Each segment (~500MB of the source) is one output file and one checkpoint |
| Crash-safe resume | Each checkpoint records the file's inode, a SHA-256 of its first 1KB, a generation and a byte offset. It is written with fsync + atomic rename, only after the segment is durable. A `kill -9` replays at most one segment and never duplicates one |
| Rotation and truncation | A file that was replaced, truncated, or truncated and regrown (`copytruncate`) restarts from 0 as a new generation, without overwriting earlier output |
| Partial last line | A run stops at the last `\n`; a line still being written waits for the next run |
| Job control | `GET` / `DELETE /api/v2/ingest/jobs/{id}`: progress, format, line counts, outputs. Jobs run on `max-concurrent-jobs` slots, with no queue (429 when busy) and one job per source at a time |
| Long lines | Lines over `max-line-bytes` (1MB) are truncated and counted |

## Formats and parsing
| Feature | Details |
|---|---|
| Built-in parsers | Nginx/Apache access log (`nginx_access`), JSON (`json`), Kubernetes CRI (`cri`, including a JSON body), key-value (`kv`), raw text (`raw`) |
| Logback layouts | `pattern` compiles a layout such as `%d{ISO8601} [%t] %-5p %c - %m%n` into a parser (`logback`) |
| Format detection | Each source's format is detected from its first 100 lines, or set with `format` / `pattern` |
| Per-line fallback | A line the main parser rejects tries the other parsers, then `raw`. Unparsed lines are kept with `argus.unparsed` (`keep-unparsed=false` drops them) |
| Canonical record | `LogEnvelope` follows the OpenTelemetry log data model: event and observed time, severity text and number, body, trace and span ids, `service.name`, attributes, and the source with its offset |
| Deterministic ids | `eventId = UUIDv3(tenant, source, generation, offset)`, so a replay reproduces the same ids |

## Output and archive
| Feature | Details |
|---|---|
| Local NDJSON output / WAL | `tenant=T/dt=YYYY-MM-DD/source=ID/gen=G/START-END.ndjson`, durable before checkpointing. Names come from offsets, so a replay replaces a file rather than duplicating it |
| Parquet + ZSTD archive | Opt-in with `argus.archive.store=filesystem|s3`. Each committed segment becomes Parquet, is uploaded, and only then leaves the WAL. Measured 14.6x smaller than the input on synthetic data |
| Exact hour partitions | `…/tenant=T/dt=…/hour=HH/source=…/gen=…/FROM-TO.parquet`: one object per hour of data, Hive-partitioned for Athena and BigQuery |
| Outage tolerance | An object-store outage never stalls ingest: segments wait in the WAL and are retried with exponential backoff (1s → 5min) |
| No duplicate objects | Re-uploads replace objects. A replayed segment deletes its earlier copy through `ArchiveIndex`, even in other partitions |
| Stores | A directory or Kubernetes PersistentVolume; AWS S3 or an S3-compatible endpoint (path-style, optional checksum relaxation). S3 credentials come only from the AWS SDK's default chain |
| Bad data | A segment that isn't valid NDJSON is set aside as `.unreadable`. An event time that microseconds can't hold is archived as null and kept in `argus.timestamp` |
| Lean dependencies | Parquet needs only the shaded `hadoop-client-api`, with no Hadoop runtime and no Avro |

## Suppression
| Feature | Details |
|---|---|
| 10-second window | `POST /api/v2/suppression/check` allows a message once per tenant per window, then denies and counts its copies |
| Message key | `<tenant>:supp:` + SHA-256/128 of the trimmed message. `template-fingerprint` also treats messages differing only in numbers, UUIDs or hex ids as the same |
| Stores | In memory (Caffeine; bounded; one summary line per window that denied copies) or Redis (one atomic Lua call; shared by every instance) |
| Fails open | When Redis is unavailable, messages are allowed after a 100ms timeout, with one warning per window |
| Speed | p99 8.8µs per decision with the local store |

## Analysis (v1, synchronous)
| Feature | Details |
|---|---|
| Streaming aggregations | Counts by level, top-N endpoints (Misra–Gries), error rate per minute, totals and records per hour, in one pass over a file of any size |
| Filters | Time range, levels and message regex, combined (`aggregateV2`) |
| Export | Aggregates as JSON or CSV; a plain-text CLI summary |
| Record listing | `parse`, `parse/regexp` and `filter` return every record of a file up to 50MB |
| Load shedding | At most 2 aggregations at once; more get 429 + `Retry-After` instead of queueing |

## Security and tenancy
| Feature | Details |
|---|---|
| API keys | Every `/api/**` call needs `X-Argus-Api-Key` or `Authorization: Bearer`. Configuration stores only SHA-256 hashes |
| Fails closed | The tenant is resolved before dispatch, so a new endpoint is authenticated by default. Missing or unknown key → 401 + `WWW-Authenticate` |
| Tenant isolation | Checkpoints, output, archive keys, suppression keys, event ids and jobs are all per tenant. Another tenant's job is a 404 |
| Path restriction | v1 and v2 read only under the tenant's allowed roots. The check runs before existence and again after resolving symlinks, so a 404 never reveals a path outside them |
| Input validation | Tenant and source ids are limited to safe characters, because they become directory names and keys |

## Operations
| Feature | Details |
|---|---|
| Canonical log lines | One wide line per ingest run, v1 scan, suppression check and archived segment, with offsets, counts and timings |
| Memory sizing | Runs in 4GB with `-XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=512m` |
| OpenAPI | Swagger UI at `/swagger-ui.html`, with the API-key scheme declared |
| Actuator | `/actuator/health` and the other Spring Boot endpoints |
| Test data | `DataSeeder` streams a reproducible Nginx log of any size to disk |
| Tests | 261 tests: port contract tests (parsers, object stores), crash and replay tests, Testcontainers for Redis and S3, and load tests (1GB ingest + archive at a 256MB heap, 1M suppression decisions) |
