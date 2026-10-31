# P5 Plan: Hot Search Tier (OpenSearch / Elasticsearch) and the Search API

> Phase P5 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P2 (the WAL and its archiver, [`P2_PLAN.md`](P2_PLAN.md)), P3 (push and the export sources' search-engine client, [`P3_PLAN.md`](P3_PLAN.md)) and P4 (metrics, [`P4_PLAN.md`](P4_PLAN.md)).
> Exit criterion: **T5**: p99 ingest-to-searchable ≤30s for pushed lines at the target peak (4,150 lines/s) on a 2-CPU node. Also: a hot-tier outage never loses or duplicates a line, and one tenant's search never returns another tenant's lines.
> Kafka (the optional buffer, suppression partitioned by key, a tail shared by instances) moves to **P6**: it changes the deployment, and the hot tier doesn't need it.

## 1. Goal and scope

Argus keeps every line, but the only ways to read them back are the live tail (now) and Parquet in object storage (SQL engines, minutes). Operators need "show me tenant X's errors for service Y in the last hour" in seconds. P5 adds the **hot tier**: every WAL segment is also indexed into OpenSearch or Elasticsearch, kept for 14 days, and served by a **search API** that enforces tenancy on the server.

| In scope (P5) | Out of scope |
|---|---|
| WAL segments acknowledged per sink; deleted once every routed sink has acknowledged them (guardrail 3) | Kafka as the buffer; suppression partitioned by key; tail shared by instances (P6) |
| `HotIndexer`: WAL → `_bulk` with `_id = eventId`, idempotent, oldest segment first, backoff on failure | Searching the cold tier (Athena / BigQuery over Parquet) |
| OpenSearch 2.7+ and Elasticsearch 7.17 / 8.x, detected from `GET /`; one client shared with the export source | AWS SigV4 (Amazon OpenSearch Service with IAM auth): basic and API-key auth only |
| Daily indices per event day, an index template per engine (mappings, `best_compression`, `refresh_interval`), retention by Argus (14 days) | ILM / ISM rollover policies (D4 explains why not) |
| ADR-4 tenancy: a shared index with routing, dedicated indices for configured tenants | Size-based automatic promotion to a dedicated index |
| `GET /api/v2/search`: time-bounded, tenant-filtered, `q` / `level` / `source` / `service` / attribute filters, `search_after` paging | Aggregations / facets in the search API; saved searches; alerts |
| Push freshness: a shorter stream flush while a hot store is configured | Coalescing small WAL segments into larger Parquet objects (§8) |
| Metrics and canonical lines for indexing and search; the mapping's disk saving measured | Typed (numeric) attribute fields (§8) |

## 2. Decisions (STAR, per guardrail 10)

### D1. Each sink acknowledges each WAL segment; the last acknowledgement deletes it (ADR-7)
- **Situation:** since P2, the local segments are a WAL with one consumer, the `Archiver`: a segment is "pending" until it is archived, then deleted (or marked `.archived`). `WatermarkWalCapacity` measures pending bytes, so an archive outage pauses ingest at the high watermark. The design (§8) says the router dual-writes and "the checkpoint advances only when both have acknowledged"; guardrail 3 says a segment is deleted only after every routed sink acknowledges it.
- **Task:** add a second consumer without coupling the two: an OpenSearch outage must not stop archiving and vice versa, neither may lose a line, and the WAL bound must still hold.
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | (a) Dual-write from the pipeline's writer stage, checkpoint after both acknowledge | One pass over each batch | The slowest sink paces ingest; a sink outage stalls every source at once instead of growing the WAL; the checkpoint protocol (P0) changes |
  | (b) A router thread that reads each segment once and feeds both sinks | One read | The two sinks advance together: the slower one holds back the faster, and the router owns both sinks' retries |
  | **(c) Independent consumers of the committed WAL, each with its own acknowledgement per segment** | Sinks fail, retry and catch up independently; the checkpoint protocol is unchanged (it already advances at local commit) | Each segment is read once per sink (sequential reads of files the page cache usually still holds) |

  Chosen (c), the shape of Kafka consumer groups on a local log, which P6 can swap in.
  - **Routed sinks:** `archive` when `argus.archive.store` ≠ `none`, `hot` when `argus.hot.store` ≠ `none`. `WalSegments` moves to a sink-neutral `wal` package and lists the segments a given sink hasn't acknowledged.
  - **Acknowledgement:** an empty marker file per sink next to the segment, `<segment>.archived` (the existing marker, so upgrades keep their state) and `<segment>.indexed`, created with `DurableFiles`. After writing its marker, a sink calls `WalSegments.release(segment)`, which (under one lock per WAL) deletes the segment and its markers once every routed sink has acknowledged it. With `delete-after-upload=false`, or without an archive store (the segments are then the cold output), segments are never deleted, only marked.
  - **Capacity:** `WatermarkWalCapacity` counts the bytes of segments that some routed sink hasn't acknowledged. A hot-tier outage therefore grows the WAL and, after the high watermark (24GB, about 2.4 days at 10GB/day), pauses ingest with 429s, exactly as an archive outage does today. How long that takes depends on the WAL's compression: NDJSON envelopes are ~4x the raw input (40GB/day at the target), so 24GB is ~14 hours; with `argus.sink.local.compression=zstd` (~10x smaller than NDJSON) it is ~6 days. P5 recommends `zstd` with a hot store, as P2 does with an archive store. That follows guardrail 3; the alternative (skip the hot copy past a lag limit, since the cold tier has it) is noted in §8.
  - **Superseded segments** (a crash between a segment's commit and its checkpoint; P2 §3): the replacement has the same start offset and the same `eventId`s for the same lines, so the hot sink just indexes it again (`_id` overwrites). The hot sink needs nothing like `ArchiveIndex`.
- **Result:** each sink has its own lag (`argus_wal_pending_bytes{sink}`), backoff and canonical lines. Ingest pauses only when the WAL bound is reached, whichever sink is behind.

### D2. Indexing: one `HotIndexer` thread, `_bulk` with `op_type=index` and `_id = eventId`
- **Situation:** every WAL line is a `LogEnvelope` with a deterministic `eventId` (guardrail 3). 4,150 lines/s at ~300 bytes is ~1.2MB/s; one node of either engine indexes tens of MB/s.
- **Task:** at-least-once into the engine with no duplicates, bounded memory, and per-document failures that neither block the WAL forever nor disappear silently.
- **Action:**
  - One thread (`SmartLifecycle`, like the `Archiver`), every `scan-interval` (1s): the oldest segment the hot sink hasn't acknowledged, streamed line by line into bulk bodies of at most `bulk-max-docs` (5,000) / `bulk-max-bytes` (5MB), sent one at a time. The catch-up rate after an outage is measured in `HotOutageIT` (§5), not assumed.
  - `op_type=index` with `_id = eventId` and `routing` (D3): a retried bulk, a re-indexed superseded segment or a re-run after a crash overwrites the same documents. Not `create` (a retry would answer 409) and not data streams (they accept only `create`, and a retry after a rollover would land in a new backing index as a duplicate).
  - `filter_path=errors,items.*.status,items.*.error.type,items.*.error.reason`: the response shrinks to what is needed, not one object per document with its shard details.
  - **Per-item results:** 2xx is indexed. 429 / 5xx items are retried (1s, 2s, 4s) within the segment; still failing, the pass stops and backs off (`retry-initial` doubling to `retry-max`, like the archiver). Other 4xx items (a document the mapping rejects) are **rejected**: counted, the first reason kept for the canonical line, not retried, not stored again (the cold tier is the system of record and has the line). A whole-request failure (connection, 5xx, 401/403) backs off without acknowledging.
  - Progress within a segment is kept in memory: after a failure the same segment resumes at its last fully acknowledged bulk, not from the start (a 500MB segment is 1.6M documents). After a restart it starts the segment again; the `_id`s make that harmless.
  - **Lines outside the hot window** (event time older than `retention`, D4) are skipped, counted as `outside_retention`: they would land in an index that retention deletes at once.
  - One `Hot segment finished …` canonical line per segment (guardrail 9): tenant, source, generation, offsets, documents indexed / rejected / skipped, bulks, retries, first rejection reason, duration.
- **Result:** a document is in the engine at least once and stored exactly once per `eventId`. The hot sink acknowledges a segment only when every one of its documents was indexed, rejected (permanent) or skipped.

### D3. Tenancy layout (ADR-4): a shared index with routing, dedicated indices for configured tenants
- **Situation:** ADR-4 chose a hybrid: dedicated indices for large tenants, a shared index with `routing=tenantId` for the rest, to avoid one shard set per small tenant. Tenants come from configured API keys (static, few).
- **Task:** isolation that can't be bypassed from a request; a bounded shard count; names the engines accept.
- **Action:**
  - `argus.hot.dedicated-tenants` lists the tenants with their own indices; everyone else shares. Configured, not measured: tenants are already configured, and moving a tenant later only changes where new days go (search reads both, below). Automatic promotion by size is §8.
  - **Index names:** `<prefix>-YYYY.MM.DD-shared` and `<prefix>-YYYY.MM.DD-t-<tenant>` (`prefix` = `argus-logs`, a deployment prefix shared by all tenants, as guardrail 4 allows). The day comes first, so a tenant named `shared` can't collide with the shared index. Index names must be lowercase, so a dedicated tenant id must be lowercase (checked at startup; tenant ids are otherwise case-sensitive).
  - Every document carries `tenant_id` (`keyword`). Writes to the shared index use `routing=<tenant>`, so one tenant's documents sit on one shard and its searches touch one shard.
  - **Search always filters on the server:** a `term` on `tenant_id` from the caller's API key, in the query's `bool.filter`, next to (never inside) the user's query, and only the caller's index names (its dedicated indices and the shared ones) for the days in range. Nothing in a request names an index or a tenant.
  - **Shards:** one primary per index by default (`number_of_shards=1`, `number_of_replicas=1`): 14 days × (1 shared + N dedicated) indices. 10GB/day of raw logs is a few GB of index per day, within one shard's guidance (10–50GB).
- **Result:** one tenant's search can't reach another's documents, by construction and by test (D6).

### D4. Daily indices per event day; retention by Argus, not ILM / ISM
- **Situation:** the design (§8) proposed daily rollover with ILM (Elasticsearch) / ISM (OpenSearch) deleting at 14 days. The two lifecycle APIs differ in policy format, attachment and rollover aliases.
- **Task:** delete data after 14 days on both engines, keep re-indexing idempotent (D2), and let a search compute which indices to read.
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | (a) Data streams + ILM / ISM rollover | Standard, engine-managed | `create`-only (duplicates across rollovers, D2); two policy dialects; late data lands in today's backing index, so retention follows ingest time, not event time |
  | (b) Daily indices by ingest day + ILM / ISM delete | Simple | A re-index after midnight writes the same `eventId` into another index: a duplicate |
  | **(c) Daily indices by event day; Argus deletes indices older than `retention`** | The same line always maps to the same index (idempotent); search picks indices from the time range; one implementation for both engines | Argus runs the deletion (one call per index per day); a line far in the future needs a rule |

  Chosen (c).
  - **The day** is the event time's UTC day, the same day as the archive's `dt` partition. Lines without an event time use `observedTimestamp` (and are flagged `event_time_missing`). Lines more than a day in the future are indexed under the observed day, keeping their real `@timestamp`.
  - **`HotRetention`** runs hourly: it lists `<prefix>-*` indices (`GET _cat/indices`), parses the date from names matching the exact pattern, and deletes those older than `retention` (14 days). It never deletes a name it doesn't parse. Idempotent, so several instances may run it.
  - **Index template** (composable `_index_template`, both engines) on `<prefix>-*`: the mapping (D5), `index.codec=best_compression`, `refresh_interval` (`5s`, part of T5's budget), shards and replicas. Installed at startup, and again if missing; a version number in `_meta` lets a newer Argus replace an older template (applies to new daily indices).
- **Result:** no lifecycle policy to maintain per engine; re-indexing never duplicates; the engine holds ≤15 days.

### D5. Mapping: fixed top-level fields, attributes as `flattened` / `flat_object`
- **Situation:** attributes are arbitrary (JSON lines, logfmt, canonical lines): with dynamic mapping, one field per new key per index (mapping explosion), and the first value's type wins, so `status=200` in one line and `status="ok"` in another makes the second rejected. The design (§8) asked for `dynamic: strict` or bounded templates, and to *measure* the "~35% smaller" claim, not assume it.
- **Task:** no rejections from attribute types, no mapping explosion, searchable attributes, a smaller index than the default mapping.
- **Action:** `dynamic: strict` at the top level, with snake_case fields like the Parquet schema:

  | Field | Type | Why |
  |---|---|---|
  | `@timestamp`, `observed_timestamp` | `date` (ms) | `date_nanos` rejects dates before 1970; ms is enough to search and sort |
  | `tenant_id`, `source_id`, `source_format`, `severity_text`, `service_name`, `event_id` | `keyword` | Filters and aggregations; `event_id` is the sort tiebreaker for paging (sorting on `_id` is disabled by default in Elasticsearch 8) |
  | `severity_number` | `byte` | `level` filters are a range on it |
  | `source_generation`, `source_offset` | `integer`, `long` (`index: false`) | Returned, never searched |
  | `body` | `text`, `norms: false` | Full text; length normalisation is useless for logs |
  | `trace_id`, `span_id` | `keyword`, `doc_values: false` | Searched by exact value, never aggregated |
  | `attributes`, `resource` | `flattened` (Elasticsearch) / `flat_object` (OpenSearch 2.7+) | One field per object whatever its keys; values are indexed as keywords, so no type conflicts |
  | `event_time_missing` | `boolean` | D4 |

  Attribute values become strings in the index (`attributes.http_status:500` matches; a numeric range on an attribute doesn't). The `_source` keeps their JSON types.
- **Result:** a document can only be rejected for a truly malformed top-level field, which Argus produces itself. The saving is measured against the engine's default dynamic mapping on the same 1M lines (§5).

### D6. The search API: `GET /api/v2/search`, bounded, server-side tenancy
- **Situation:** `SCALING_DESIGN.md` §7 sketched `GET /api/v2/search?tenant&from&to&service&severity&q=`. The tenant must come from the API key (guardrail 4), never from a parameter.
- **Task:** a search operators can use, which can't scan unbounded data, can't reach another tenant, and can't be turned into an expensive query.
- **Action:**
  - **Parameters:** `from`, `to` (ISO 8601, required; `[from, to)`; at most `max-range` (14 days) apart, and `from` within the hot window, else 400 naming the archive). `q` (≤1,024 characters): a `simple_query_string` on `body` only, `default_operator: and`, flags limited to `AND|OR|NOT|PHRASE|PREFIX|WHITESPACE` (no regexes, fuzzy or field names). `level` (minimum severity), `source`, `service`, `attr` (repeatable `key:value`, a `term` on `attributes.<key>`; keys `[\w.-]{1,128}`). `size` (default 100, ≤500), `cursor`.
  - **Query:** `bool.filter` = tenant term + time range + the filters; `must` = the `q` clause. Sort `@timestamp` desc, then `event_id`. Indices: the caller's index names for each day in range, `ignore_unavailable=true`. `timeout` 10s; a timed-out search answers with what it found and `"partial": true`.
  - **Paging:** `search_after` on the sort values, returned as an opaque `cursor` (base64 JSON, carrying the query's hash so a cursor can't be reused with a different query). No point in time: logs are append-mostly and a page boundary repeating or skipping a line that arrived meanwhile is acceptable (stated in the API docs).
  - **Bounds:** `max-concurrent-searches` (8 per node; more get 429 + `Retry-After: 1`, no queue, like v1 aggregations). The response is read with a cap (`max-response-bytes`, 32MB; 500 lines of up to 1MB each could exceed it: then 502 asking for a smaller `size`). The engine's 429 → 503 + `Retry-After: 5`; engine down → 503.
  - **Response:** `{hits: [...], next: cursor | null, partial, tookMs}`; each hit is the envelope's fields (as in the tail's `TailEvent`, without the tenant).
  - One `Search finished …` canonical line per request: tenant, range, filters present (not `q`'s text, which may hold customer data; its length), hits, `partial`, took, outcome.
- **Result:** tenant isolation is the API's, not the caller's, responsibility. The worst search is 14 days × one tenant's indices, a bounded query string and 500 hits.

### D7. Freshness (T5): a shorter stream flush while a hot store is configured
- **Situation:** pushed lines are durable in the spool at the 202 but reach the WAL only when the stream runner ingests the spool: at `segment-bytes`, a sealed spool file, or after `flush-interval` (1 minute, chosen in P3a to keep segments, and so Parquet objects, large). File jobs commit a segment every 500MB and at the end of each run.
- **Task:** p99 from a push's 202 to the line being searchable ≤30s at 4,150 lines/s, without changing what the cold tier gets when there is no hot tier.
- **Action:** with a hot store, the stream runner uses `min(argus.push.flush-interval, argus.hot.flush-interval)`, where `argus.hot.flush-interval` defaults to `10s`. The budget:

  | Step | Worst case |
  |---|---|
  | Waiting for the stream's run | 10s (`hot.flush-interval`) + up to 1s (the runner's tick) |
  | Run: spool → WAL segment committed | ~1s at a stream's share of 4,150 lines/s |
  | Hot indexer notices the segment | 1s (`scan-interval`) |
  | Bulk | <1s |
  | Engine refresh | 5s (`refresh_interval`) |
  | **Total** | **~19s** (≤30s) |

  The cost: up to 6 segments a minute per active stream instead of 1, so 6x more (smaller) Parquet objects. At 32 busy streams, 276k objects a day per tenant (~$1.40/day of S3 PUTs). Coalescing them in the archiver is §8.
- **Result:** T5 is met by configuration; it is measured, not assumed (§5).

### D8. One search-engine client for exports, indexing and search
- **Situation:** `ElasticsearchExportSource` (P3b) already has a `java.net.http` client with API-key or basic auth, engine detection from `GET /` and JSON helpers.
- **Task:** the hot tier needs the same, against possibly another cluster.
- **Action:** extract `engine.SearchEngineClient` (base URL, auth, timeouts, `Flavor` detection cached per client, JSON request / response with a size cap, status → exception mapping). The export source keeps its behaviour and tests; the hot tier gets its own client from `argus.hot.*`. No `opensearch-java` / Elasticsearch client libraries: each pins one engine's API and adds tens of MB.
- **Result:** one place for auth, timeouts and engine differences (`flattened` vs `flat_object`, `_shard_doc`).

### D9. Memory budget
| Item | Bound |
|---|---|
| Bulk body | `bulk-max-bytes` (5MB) being built + one in flight, plus its (filtered) response |
| WAL reading | One buffered reader (64KB) per sink |
| Search | `max-concurrent-searches` (8) × `max-response-bytes` (32MB) of text, plus its parsed form (roughly 2–3x): ~0.5–0.8GB worst case; typical pages are tens of KB |
| Retention, templates | Negligible |

The search worst case is the largest new item, and the cap is what makes it bounded. If it doesn't fit next to P0–P4's budgets (P2 D9, P4 D7) in the 2.4GB heap, the hits are streamed to the client instead of parsed whole; `HotFreshnessIT` records the peak heap with searches running.

## 3. Components

| Class | Package | Role |
|---|---|---|
| `WalSegments` (moved from `archive`), `WalSink` | `wal` | Committed segments; per-sink markers; `pending(sink)`, `acknowledge(segment, sink)`, `release(segment)` |
| `WatermarkWalCapacity` (moved) | `wal` | Pending = not acknowledged by every routed sink |
| `SearchEngineClient` | `engine` | Shared HTTP client (D8); used by `ElasticsearchExportSource`, the hot tier and search |
| `HotIndexer` | `hot` | `SmartLifecycle`; WAL → `_bulk`; per-item results; backoff; canonical line; metrics |
| `HotDocument` | `hot` | `LogEnvelope` → the index document (D5) |
| `HotIndices` | `hot` | Index names per tenant and day (D3, D4); which indices a search reads |
| `HotTemplates` | `hot` | The index template per engine; installed at startup |
| `HotRetention` | `hot` | Hourly deletion of indices older than `retention` |
| `SearchService`, `SearchQuery`, `SearchCursor` | `search` | Validation, query building, paging, bounds, canonical line |
| `SearchController` | `controller` | `GET /api/v2/search` |
| `HotProperties`, `HotConfig` | `config` | `argus.hot.*`, wiring (only with `argus.hot.store` ≠ `none`) |
| `Archiver`, `StreamRunner`, `MetricsConfig`, `ElasticsearchExportSource` | existing | Archive acknowledges through `WalSegments`; stream flush (D7); new gauges; the shared client |

## 4. Configuration and meters

```properties
argus.hot.store=none                      # none | opensearch | elasticsearch (checked against GET / at startup)
argus.hot.url=
argus.hot.api-key=                        # or username / password, from the environment
argus.hot.index-prefix=argus-logs
argus.hot.dedicated-tenants=              # lowercase tenant ids with their own daily indices
argus.hot.retention=14d
argus.hot.shards=1
argus.hot.replicas=1
argus.hot.refresh-interval=5s
argus.hot.flush-interval=10s              # stream runs at least this often while a hot store is set (D7)
argus.hot.scan-interval=1s
argus.hot.bulk-max-docs=5000
argus.hot.bulk-max-bytes=5MB
argus.hot.retry-initial=1s
argus.hot.retry-max=5m
argus.hot.request-timeout=30s
argus.sink.local.compression=zstd        # recommended with a hot store (D1)

argus.search.max-range=14d
argus.search.max-size=500
argus.search.max-concurrent-searches=8
argus.search.max-response-bytes=32MB
argus.search.timeout=10s
```

| Meter | Type | Tags |
|---|---|---|
| `argus_hot_documents_total` | counter | `tenant`, `outcome` (`indexed`, `rejected`, `outside_retention`) |
| `argus_hot_bulk_seconds` | timer | `outcome` |
| `argus_hot_segments_total` | counter | `outcome` |
| `argus_wal_pending_bytes` | gauge | `sink` (`archive`, `hot`); the existing untagged `argus_wal_pending` stays (the watermark's measure: not acknowledged by every sink) |
| `argus_hot_lag_seconds` | gauge | Age of the oldest segment the hot sink hasn't acknowledged |
| `argus_search_requests_seconds` | timer | `tenant`, `outcome` |

## 5. Tests

| Kind | Test |
|---|---|
| Unit | `WalSegmentsTest`: pending per sink, a segment deleted only after every routed sink's marker, kept with `delete-after-upload=false` or without an archive store, existing `.archived` markers honoured, concurrent acknowledgements delete once |
| Unit | `WatermarkWalCapacityTest`: pending counts a segment until every routed sink has acknowledged it |
| Unit | `HotDocumentTest` (field mapping, `event_time_missing`, lifted fields), `HotIndicesTest` (names, the day rules of D4, lowercase dedicated tenants, which indices a search reads), `SearchQueryTest` (the tenant filter is always present and outside the user's clause, `q` flags, `attr` keys, ranges, cursor tied to its query) |
| Protocol stub | `HotIndexerTest` against a stub `_bulk` endpoint: bulk sizes, per-item 429 retried, a mapper rejection counted and acknowledged, a whole-request 5xx backs off without acknowledging, resume within a segment, a superseded segment re-indexed, outside-retention lines skipped |
| Engines (Testcontainers) | `HotTierEngineTest`, parameterised over OpenSearch 2.17 and Elasticsearch 8.15: template install and upgrade, flattened / flat_object attribute search, a segment indexed twice → same count, retention deletes only parsed old names, tenant isolation in the shared index (another tenant's lines never returned, whatever `q` and `attr` say), paging with ties, `partial` on timeout |
| Unit | `SearchEngineClientTest`; `ElasticsearchExportSourceTest` unchanged on the shared client |
| API | `SearchControllerTest`: 401, 400s (missing / reversed / too wide / too old range, bad `level`, `attr`, `size`, cursor), 429 at the search slots, 503 when the engine is down |
| Wiring | `ArgusWebApplicationTest` with `argus.hot.store=none` (no hot beans); `MetricsEndpointTest` for the new meters |
| Load (`load` tag) | `HotFreshnessIT` (**T5**): push at 4,150 lines/s for 5 minutes in a 2-CPU container, probe lines every second; p99 from the 202 to the probe being found by `GET /api/v2/search` ≤30s; file job to searchable |
| Load | `HotOutageIT`: the engine paused for 2 minutes under push load → ingest continues, the archive keeps draining, hot lag grows; after it returns, the index holds exactly the acknowledged lines (count and distinct `event_id`) |
| Load | `HotCrashIT`: `kill -9` while indexing; after restart, documents = lines, no duplicates |
| Measurement | Mapping saving: the same 1M lines indexed with the engine's default dynamic mapping and with D5's, store size after force-merge (the design's "~35%" is checked, not assumed) |

## 6. Implementation order (one commit per step)
1. The plan (this file); ADR-7 and ADR-4's update in the design doc.
2. WAL per-sink acknowledgements: the `wal` package, markers, `release`, capacity; the archiver on it + tests.
3. `SearchEngineClient` extracted; the export source on it.
4. Hot index: `HotDocument`, `HotIndices`, `HotTemplates`, `HotRetention` + engine tests.
5. `HotIndexer` + protocol-stub and engine tests; stream flush with a hot store (D7); metrics.
6. Search API: `SearchService`, `SearchController` + tests.
7. Load tests (T5, outage, crash) and the mapping measurement; results in §9.
8. Docs: CLAUDE.md, README, FEATURES, ROADMAP, CHANGELOG, design doc §7 / §8 / §11.

## 7. Risks
| Risk | Mitigation |
|---|---|
| A hot-tier outage fills the WAL and pauses ingest | 24GB of headroom (~6 days with zstd, ~14 hours without, D1); `argus_hot_lag_seconds` to alert on; §8 notes the "skip past a lag limit" alternative |
| `flattened` and `flat_object` differ in query support | Only `term` on a leaf path and `exists` are used; both engines tested |
| Search responses are large | `size` ≤500 and `max-response-bytes`; slots without a queue |
| One tenant's backlog delays others in the hot indexer (oldest first, one thread) | Same as the archiver today; measured in `HotOutageIT`'s catch-up; per-tenant fairness is §8 |
| Shorter flushes create many small Parquet objects | Only with a hot store; cost quantified in D7; coalescing in §8 |
| Index names collide or retention deletes the wrong index | Day-first names; retention deletes only names it parses exactly |
| The engine's clock or timezone | Days are UTC from Argus's own timestamps; the engine's time is never used |
| Tests need two engine containers (memory) | Containers run one at a time, with a 512MB engine heap |

## 8. Later (not P5)
- **Kafka (P6):** the buffer becomes a topic; the archive and hot sinks become consumer groups (D1 is that shape); suppression partitioned by key; the tail shared across instances.
- **Coalescing small segments** into one Parquet object per source per few minutes or 64MB (D7's cost).
- **Typed attributes:** promote configured attribute keys to numeric fields, for ranges and aggregations (`duration > 0.5`).
- **Skip the hot copy past a lag limit** instead of pausing ingest, if the cold tier alone is enough during long hot-tier outages.
- **Searching the cold tier** (Athena / BigQuery over the Parquet archive) for ranges older than the hot window.
- **Automatic dedicated indices** for tenants above a size threshold; per-tenant fairness in the hot indexer.
- **AWS SigV4** for Amazon OpenSearch Service with IAM authentication.
- **HPA on lag** (the design's Deployment item) once several instances can share work (P6).

## 9. Results

To be filled in after step 7.
