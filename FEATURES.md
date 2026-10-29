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
| Replay lane | `fromBeginning` jobs (or `lane: replay`) run on their own slots (`argus.replay.max-concurrent-jobs`, 1) with a shared read budget (8MB/s) and low-priority threads, so a re-ingest never takes live ingest's slots or bandwidth. On 2 CPUs a 1GB replay left live push p99 within the noise (15.6ms vs 15.8 / 23.6ms before and after); the same re-read unthrottled raised it 3–8x |
| Long lines | Lines over `max-line-bytes` (1MB) are truncated and counted |

## Push ingest (v2)
| Feature | Details |
|---|---|
| HTTP push | `POST /api/v2/ingest/streams/{id}`: NDJSON or plain lines, optionally `Content-Encoding: gzip`, from agents (Fluent Bit, Vector) or any HTTP client. Up to 32 streams per tenant |
| Acknowledged once durable | A request's lines are fsynced in the stream's spool, with an index entry, before the 202. The ordinary pipeline then ingests the spool like a file (same parsers, checkpoints, WAL and archive), batching up to `flush-interval` (1 minute) of pushes into one segment |
| Retries without duplicates | `Idempotency-Key`: a repeated key is answered 200 with the original offsets and writes nothing, even after a restart. Tested with `kill -9` mid-load: no acknowledged line lost, none ingested as two events |
| Every limit has a status | 429 + `Retry-After` for busy request slots (1s), a request with the same `Idempotency-Key` still in progress (1s), the tenant's quota (exact wait), a full WAL (60s) or a full spool (30s); 413 over 8MB decompressed (gzip bombs stop there) or 10,000 lines; 409 for too many streams or a stream id that is a file job's source id; 415 for an unknown encoding |
| Per-tenant quotas | A token bucket per tenant on decompressed bytes (default 2MB/s, 2s burst, per-tenant overrides), in memory or in Redis (one Lua call, fails open). One tenant flooding doesn't get another refused |
| Suppression on push | Repeated messages within 10s are dropped as they arrive (receive time), comparing the parsed message rather than the whole line; the response counts them |
| Crash-safe spool | Bytes that were never acknowledged are truncated at startup; spool files rotate at 64MB and are deleted once ingested; each spool file is its own output generation |
| Status | `GET /api/v2/ingest/streams/{id}` (and the list): spool bytes, bytes not yet ingested, request and line counters, the last run |
| Speed | 132k lines/s end to end (8 clients, 1,000-line gzip batches) at a 256MB heap; request p99 93ms |

## Exports (v2)
| Feature | Details |
|---|---|
| Elasticsearch and OpenSearch | `POST /api/v2/ingest/exports` copies `[from, to)` of an index into a stream: point in time + `search_after`, each hit's `_source` one JSON line, an optional query object. Elasticsearch 7.12+ and OpenSearch 2.x, told apart by `GET /`, each with the tiebreaker it supports (`_shard_doc`, `_id`). Tested on 8.15 and 2.17 |
| Coralogix | The DataPrime query API, one query per time window: a full window is halved and queried again, a sparse one doubles the next (up to 1h). Each row becomes its `userData` plus timestamp, severity and labels. Frequent-search or archive tier |
| Resumable, no duplicate pages | Each page is durable in the stream's spool (under a key derived from the export and the page) before the cursor after it is saved. Re-posting the export resumes; a crash re-fetches one page, which the key drops. An expired Elasticsearch point in time can repeat hits sharing one timestamp, never miss one |
| Paced | Exports run on the replay lane and pause while their stream has 256MB not yet ingested, or the WAL is full, leaving the spool to live pushes |
| Configured sources only | A request names a source configured for its tenant; URLs and credentials never come from requests (no SSRF), and credentials never appear in logs or status |

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
| WAL compression | `argus.sink.local.compression=zstd`: `.ndjson.zst` segments, 10x smaller than NDJSON (0.40x the input) for 9% of ingest speed |
| Backpressure from the archive | With an archive store, ingest pauses before its next segment while more than 80% of `argus.wal.max-bytes` (30GB) is waiting for upload, and resumes below 60%. Job status shows `paused: WAL_FULL`; new jobs get 429. Tested with a 30s store outage during a 500MB ingest at a 256MB heap |
| Parquet + ZSTD archive | Opt-in with `argus.archive.store=filesystem|s3`. Each committed segment becomes Parquet, is uploaded, and only then leaves the WAL. Measured 14.6x smaller than the input on synthetic data |
| Exact hour partitions | `…/tenant=T/dt=…/hour=HH/source=…/gen=…/FROM-TO.parquet`: one object per hour of data, Hive-partitioned for Athena and BigQuery |
| Outage tolerance | An object-store outage never fails ingest: segments wait in the WAL and are retried with exponential backoff (1s → 5min); if the outage outlasts the WAL's room, ingest pauses rather than failing |
| No duplicate objects | Re-uploads replace objects. A replayed segment deletes its earlier copy through `ArchiveIndex`, even in other partitions |
| Stores | A directory or Kubernetes PersistentVolume; AWS S3 or an S3-compatible endpoint (path-style, optional checksum relaxation), with credentials only from the AWS SDK's default chain; Google Cloud Storage through its JSON API (resumable 16MB chunks that resume after a failure; Application Default Credentials / Workload Identity) |
| Bad data | A segment that isn't valid NDJSON is set aside as `.unreadable`. An event time that microseconds can't hold is archived as null and kept in `argus.timestamp` |
| Lean dependencies | Parquet needs only the shaded `hadoop-client-api`, with no Hadoop runtime and no Avro; GCS needs only Google's auth library (6MB) instead of `google-cloud-storage` (43MB) |

## Suppression
| Feature | Details |
|---|---|
| 10-second window | `POST /api/v2/suppression/check` allows a message once per tenant per window, then denies and counts its copies |
| Message key | `<tenant>:supp:` + SHA-256/128 of the trimmed message. `template-fingerprint` also treats messages differing only in numbers, UUIDs or hex ids as the same |
| Stores | In memory (Caffeine; bounded; one summary line per window that denied copies) or Redis (one atomic Lua call; shared by every instance) |
| Fails open | When Redis is unavailable, messages are allowed after a 100ms timeout, with one warning per window |
| On push ingest | Applied to every pushed line at receive time; file ingest bypasses it by design |
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
| Tenant isolation | Checkpoints, output, archive keys, suppression and quota keys, event ids, jobs and push streams are all per tenant. Another tenant's job or stream is a 404 |
| Ingest quotas | Per-tenant pushed bytes per second (token bucket); over it, that tenant alone gets 429 with the exact `Retry-After` |
| Path restriction | v1 and v2 read only under the tenant's allowed roots. The check runs before existence and again after resolving symlinks, so a 404 never reveals a path outside them |
| Input validation | Tenant and source ids are limited to safe characters, because they become directory names and keys |

## Operations
| Feature | Details |
|---|---|
| Canonical log lines | One wide line per ingest run, push request, export page and export job, v1 scan, suppression check and archived segment, with offsets, counts and timings |
| Memory sizing | Runs in 4GB with `-XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=512m` |
| OpenAPI | Swagger UI at `/swagger-ui.html`, with the API-key scheme declared |
| Actuator | `/actuator/health` and the other Spring Boot endpoints |
| Test data | `DataSeeder` streams a reproducible Nginx log of any size to disk |
| Tests | 398 tests: port contract tests (parsers, object stores), crash and replay tests, Testcontainers for Redis, S3, GCS, Elasticsearch and OpenSearch, a Coralogix protocol stub, and load tests (1GB ingest + archive at a 256MB heap, T3 archive outage, 1M suppression decisions; push throughput and tenant isolation, push during an archive outage, `kill -9` during push; T8 replay isolation) |
