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

## Live tail (v2)
| Feature | Details |
|---|---|
| Server-Sent Events | `GET /api/v2/tail` streams the caller tenant's lines as they are accepted, after suppression: a pushed line once it is durable (not when the spool is ingested, up to a minute later), a file job's lines as they are parsed. Replays and exports stay off the tail |
| Server-side filters | `level` (minimum severity, any spelling), `source` (source or stream id), `q` (body substring, ignoring case) |
| Never slows ingest | One bounded ring per tailed tenant (10,000 lines / 4MB); publishing never waits, and costs nothing while nobody tails the tenant. Each client reads the ring on its own thread: a slow or stalled client blocks only itself, loses the oldest lines and gets `{"dropped": N}` |
| Rate cap | At most 200 matching lines a second per client; the rest are reported as dropped, so a client tailing a 400k lines/s file job sees a sample, not a firehose |
| Resume | Each event's SSE id is resumable with `Last-Event-ID` while the line is still in the ring; otherwise `event: reset` |
| Limits | 16 connections per tenant, 256 per node (429 + `Retry-After`), 1-hour connections (clients reconnect), a heartbeat every 15s |
| Freshness | On 2 CPUs a pushed line reached an open tail in 6.9ms (p50) / 39ms (p99) after the request; 16 clients that never read cost push p99 no more than 16 that read (within 7%, 4 runs); 1,000 connections fit in a 256MB heap |

## Hot tier and search (v2)
| Feature | Details |
|---|---|
| Hot search tier | Opt-in with `argus.hot.store=opensearch|elasticsearch` (OpenSearch 2.7+, Elasticsearch 7.17+, detected and checked at startup). Every WAL segment is indexed with `_bulk`, each line's document id its `eventId`, so retries, crashes and outages never duplicate a line |
| Independent of the archive | The archive and the hot tier each read the WAL and acknowledge each segment; a segment is deleted only once both have it (ADR-7). Either can be down without stopping the other; past the WAL's high watermark ingest pauses with 429s |
| Daily indices, 14-day retention | `argus-logs-YYYY.MM.DD-shared` per day of event time, or `…-t-<tenant>` for dedicated tenants; Argus deletes days past `retention` itself, the same way on both engines (no ILM / ISM policies) |
| Mapping | A strict mapping with `best_compression`: keywords for filters, `body` as text without norms, trace ids without doc values, attributes in one `flattened` / `flat_object` field, so arbitrary attributes neither explode the mapping nor conflict in type. Measured 65.6% smaller than OpenSearch's default dynamic mapping and 37.4% smaller than Elasticsearch's, on synthetic lines |
| Search API | `GET /api/v2/search`: a required range (≤14 days, within the hot window), text in the body (`"phrases"`, `-not`, `a | b`, `prefix*`; no regexes or fuzzy matching), `level`, `source`, `service`, `attr=key:value`; newest first, cursor paging |
| Tenant isolation | The tenant filter comes from the API key and sits beside the caller's query; only the caller's indices are read. Tested on both engines with queries and attribute filters naming another tenant |
| Bounded searches | 8 searches at once per node (429, no queue), ≤500 hits a page, a 32MB cap on the engine's answer, a 10s engine timeout (then `partial: true`); 503 + `Retry-After` while the cluster is busy or down |
| Freshness | With a hot store, pushed lines are ingested at least every 10s. On 2 CPUs at 4,150 lines/s, a pushed line was found by the search API 11.8s (p50) / 20.1s (p99) after its 202 (T5: ≤30s) |
| Rejections | A document the engine rejects is counted (`argus_hot_documents_total{outcome="rejected"}`) and its reason logged, not retried: the archive keeps the line. 429 / 5xx items are retried |

## Formats and parsing
| Feature | Details |
|---|---|
| Built-in parsers | Canonical log lines (`canonical`: `[ts] canonical-log-line k=v …`, Stripe style, numbers and booleans typed), Nginx/Apache access log (`nginx_access`), JSON (`json`), Kubernetes CRI (`cri`, including a JSON body), key-value (`kv`), raw text (`raw`) |
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

## Kafka as the WAL (optional)
| Feature | Details |
|---|---|
| Kafka or Redpanda as the WAL | `argus.wal.store=kafka`: committed segments go to a topic, one record per line keyed by tenant and source, then a commit marker; the checkpoint advances only once Kafka has acknowledged the marker. A lost node loses at most the push spools' last `flush-interval` |
| Sinks as consumer groups | `argus-archive` rebuilds each segment and archives it with the unchanged P2 archiver (the same object keys as the local WAL); `argus-hot` indexes records as they arrive. Each commits offsets only for what its sink has, so a restart or rebalance re-reads the rest |
| Lag watermarks | Kafka deletes by retention, not acknowledgement: ingest pauses (429 `WAL_FULL`) when a sink's lag passes 80% of `max-lag-records`, before unarchived lines could expire |
| Safe topic | Startup refuses a compacted topic, retention under 7 days, deletion by size or records over the topic's limit; `create-topics=true` creates a safe one |
| Broker outages | Writers block (backpressure) for up to `max-outage` (10m); pushes keep landing in their spools meanwhile, then everything is delivered once |
| Switching stores | At startup, local segments not yet archived or indexed are sent to the topic first |
| Tested brokers | Apache Kafka 3.8 (KRaft) and Redpanda 24.2 |

## Several instances (optional)
| Feature | Details |
|---|---|
| Instance identity | `argus.instance.id` (the pod name) joins every `eventId`, archive key, Parquet row (`source_instance`) and hot document, so two instances ingesting the same source id never overwrite each other. Unset: identities unchanged |
| Shared consumer groups | Every instance consumes its share of partitions; the archive index lives in the object store, so a partition can move between instances. `kill -9` of one of three under load: exact in both tiers |
| Shared live tail | Through a tail topic: a tail on any instance sees every instance's lines (p99 54ms across instances), `Last-Event-ID` resumes on another instance; the tail never slows ingest (drops and counts) |
| Suppression across instances | Redis, one pipelined round trip per 1,000 lines; each message allowed once across instances |
| Graceful drain | On SIGTERM: readiness down, pushes 503 + `Retry-After: 1`, in-progress pushes finish, spools ingested (`drain-timeout` 90s) |
| Kubernetes | `deploy/k8s`: StatefulSet, services, ingress with push affinity (`upstream-hash-by`), KEDA scaler on consumer lag (or an HPA via the Prometheus Adapter); checked in `kind` |

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
| Canonical log lines | One wide line per ingest run, push request, export page and export job, tail connection, v1 scan, suppression check, archived segment, indexed segment and search (without the query's text), with offsets, counts and timings |
| Metrics | Prometheus at `:9001/actuator/prometheus` (a separate management port: the scrape names tenants): lines, bytes and outcomes per tenant and lane, segment commit time, push requests by outcome and latency, suppression decisions, spool and WAL pending bytes (also per sink), WAL full, archive lag and upload time, hot-tier documents by outcome, bulk latency and lag, searches by outcome and latency, export pages and retries, replay wait, tail connections and dropped lines, Redis fail-opens, plus JVM and HTTP metrics |
| Memory sizing | Runs in 4GB with `-XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=512m` |
| OpenAPI | Swagger UI at `/swagger-ui.html`, with the API-key scheme declared |
| Health | `/actuator/health` on the management port, UP without Redis unless a Redis store is configured; `/livez` and `/readyz` probes on the API port too |
| Test data | `DataSeeder` streams a reproducible Nginx log of any size to disk |
| Tests | 488 tests: port contract tests (parsers, object stores), crash and replay tests, Testcontainers for Redis, S3, GCS, Elasticsearch and OpenSearch (exports, and the hot tier and search on both engines), Coralogix and `_bulk` protocol stubs, and load tests (1GB ingest + archive at a 256MB heap, T3 archive outage, 1M suppression decisions; push throughput and tenant isolation, push during an archive outage, `kill -9` during push; T8 replay isolation; T7 live tail; T5 hot-tier freshness, a hot-tier outage, `kill -9` while indexing, the mapping's disk saving) |
