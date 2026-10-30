# P4 Plan: Live Tail, Metrics and Producers' Canonical Lines

> Phase P4 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P0–P3 ([`P3_PLAN.md`](P3_PLAN.md) for push ingest and the replay lane).
> Exit criterion: **T7**: a line is visible on a live tail ≤2s after it is ingested, and a slow or stalled tail client can't slow ingest. Also measures ADR-6's result: 1,000 concurrent tail connections within the memory budget.
> Ships as one PR: P4 is smaller than P2 or P3.

## 1. Goal and scope

Argus can ingest, suppress, archive and replay, but nobody can watch it happen: there is no way to see a tenant's logs as they arrive, and the only signal about the service itself is its canonical log lines. P4 adds the **live tail** (SSE), **metrics** (Micrometer, Prometheus format), and a parser for the **canonical lines of the applications Argus ingests**.

| In scope (P4) | Out of scope |
|---|---|
| `GET /api/v2/tail`: SSE of one tenant's lines after suppression, filtered on the server (`level`, `source`, `q`) | WebSocket; distributed tail across instances (Redis Stream / Kafka, P5) |
| A per-tenant ring buffer; slow clients drop the oldest lines with a `dropped` marker; `Last-Event-ID` resume | Pre-filling the ring from the WAL after a restart (§8) |
| Per-tenant and node-wide tail connection limits; a per-connection rate cap | Tail of the archive (search is P5) |
| Micrometer metrics for ingest, push, suppression, WAL, archive, exports, replay and tail; Prometheus scrape on a separate management port | Dashboards and alert rules (examples only) |
| Redis health only when a Redis store is in use (open item) | |
| `canonical` parser: `canonical-log-line k=v …` lines (Stripe), with typed values | A generic per-request canonical line for every API call (D6) |

## 2. Decisions (STAR, per guardrail 10)

### D1. Where the tail taps the stream: at the point each line is accepted, after suppression
- **Situation:** lines enter Argus three ways. File jobs parse lines within milliseconds of reading them. Pushed lines are parsed (for suppression) and spooled at receive time, but the stream runner only ingests a spool after up to `flush-interval` (1 minute). Export and replay-lane lines are history, not live.
- **Task:** show each live line once, ≤2s after it is accepted (T7), after suppression (design §9), without a second parse where one already happens.
- **Action:** the options were:

  | Option | For | Against |
  |---|---|---|
  | (a) One tap in the pipeline's parse stage, for every run | One place | Pushed lines appear up to 1 minute late (T7 fails); replays and exports flood the tail with history |
  | (b) Shorten `flush-interval` to ~1s | One tap | One WAL segment and one Parquet object per stream per second: thousands of tiny objects a day |
  | **(c) Two taps: push at receive time (after the spool append is durable), file jobs in the parse stage; stream runs and the replay lane don't publish** | Each line once, at its acceptance; history stays off the tail | Push parses lines it didn't need to parse when suppression is off and someone is tailing (parsing is ~2.5µs a line) |

  Chosen (c).
  - **Push:** `PushIngestService` publishes the request's allowed lines once its spool append returns (not for a duplicate `Idempotency-Key`, not when the append fails). It already parses each line for suppression; with suppression off, it parses only while the tenant has a tail open.
  - **Files:** `IngestPipeline`'s parse stage publishes each batch's envelopes, for live-lane file jobs only (`Request.tailed()`). Stream runs (already published at receive) and replay-lane runs (fromBeginning jobs, exports) don't.
  - A file line is published before its segment is committed: a crash can show it twice on a tail, never lose it from the output. Tail is a view, not a delivery guarantee.
- **Result:** pushed lines are visible within milliseconds of the 202, and file lines within one batch of being read. Pushed lines carry no `eventId` on the tail, since their generation is assigned only when the stream runner ingests the spool; file lines carry the `eventId` the archive will hold.

### D2. One ring buffer per tenant; each connection is a cursor into it
- **Situation:** the design (§9) names a bounded queue per connection that drops the oldest lines. With N connections on one tenant, that is N copies of every line, made on the ingest thread.
- **Task:** publishing must cost the ingest thread the same whether 0 or 100 clients are tailing, must never wait, and memory must not grow with the number of connections.
- **Action:** `TailHub` keeps one **ring** per tenant that has a tail open. Publishing appends the batch's references to the ring under its lock and signals; an append past capacity drops the oldest entries. Each connection keeps its own sequence number (cursor) into the ring and reads at its own pace. A cursor that the ring has overtaken (the client was too slow) jumps to the oldest entry still in the ring, and the client receives `event: dropped` `{"dropped": N}`. That is "drop oldest, per connection", with one copy per tenant.
  - **Bounds:** `ring-lines` (10,000) and `ring-bytes` (4MB, estimated from each event's body and attributes, two bytes a character plus ~96 bytes per map entry: a 1MB line can't pin 10GB, and a 500-character JSON line with 20 fields counts as the ~3KB it holds, not 500). A ring is created by the tenant's first connection and released one minute after its last one closes (so a reconnect can resume).
  - **Cost when nobody tails:** one map lookup per batch (push: per request). Nothing is built or copied.
  - **Filters run on the connection's thread**, not the ingest thread: `level` (minimum severity, any spelling `Severity` knows), `source` (exact source or stream id), `q` (case-insensitive substring of the body, ≤256 characters). `FilterSpec` works on v1 `LogRecord`s, and converting each envelope to reuse it would cost more than it saves.
  - **Rate cap:** each connection sends at most `max-lines-per-second` (200) matching lines and examines at most 50x that; past either, it waits out the second and skips to the newest line. `dropped` counts matching lines over the cap plus every line it never examined (skipped, or overtaken by the ring), whether or not those would have matched the filter: knowing would mean examining them. For a filtered tail, `dropped` is therefore an upper bound on the lines missed. A tail of a 400k lines/s file job then costs ~200 serializations a second per client, not 400k.
  - **Resume:** each event's SSE `id` is `<ring id>-<sequence>`. A reconnect with `Last-Event-ID` from the same ring resumes after that line (or reports what was dropped); from another ring (a restart, or a ring released after idle) it starts at the newest line with `event: reset`.
- **Result:** the ingest thread's cost is one lock and an array copy per batch, independent of clients. Memory is bounded by `ring-bytes` per tailed tenant (D7 gives the worst case).

### D3. A thread per connection, on Java 17; virtual threads later
- **Situation:** ADR-6 recommended Java 21 "before P4", for thousands of mostly idle tail connections. A connection blocks when its client is slow (the socket's send buffer fills), and its sender must then wait without holding anything ingest needs.
- **Task:** serve up to 1,000 connections per node (ADR-6's result), each isolated from ingest and from the other clients.
- **Action:** the options were:

  | Option | For | Against |
  |---|---|---|
  | (a) Java 21, a virtual thread per connection | Cheap blocking | Blocking socket writes inside Tomcat's `synchronized` sections can pin carrier threads on 21 (JEP 491 fixes it in 24): a few stalled clients could stall every virtual thread, Tomcat's included if `spring.threads.virtual.enabled` |
  | (b) A small sender pool shared by all connections | Few threads | One stalled client blocks a pool thread in a write; enough of them stall every client |
  | **(c) A platform thread per connection (small stack), capped by connection limits** | Blocking is harmless and isolated; no build change | ~1,000 threads at the cap (stacks reserve virtual memory and commit only what they use) |

  Chosen (c). The SSE response is a Spring `SseEmitter` (Tomcat holds no request thread while it is open). Its sender thread waits on the tenant ring's condition with a 15s timeout, and sends a `:` heartbeat comment when nothing arrives, which is also how a vanished client is noticed. Events are written in batches: one write and one flush per wakeup, not per line.
- **Result:** a stalled client blocks only its own thread, never ingest or another client. The build stays on Java 17. ADR-6 changes to "virtual threads once the build is on Java 24+", measured by the 1,000-connection case of `LiveTailIT` (§5).

### D4. Tail limits
| Bound | Default | When reached |
|---|---|---|
| Connections per node | `max-connections` (256) | 429, `Retry-After: 30` |
| Connections per tenant | `max-connections-per-tenant` (16) | 429, `Retry-After: 30`, that tenant only |
| Lines per connection | `max-lines-per-second` (200) | Skipped, reported as dropped |
| Connection age | `max-duration` (1h) | Closed; the client reconnects with `Last-Event-ID` |
| Ring per tenant | `ring-lines` (10,000), `ring-bytes` (4MB) | Oldest lines dropped |
| Tenants with a ring | `max-tenants` (32) | Rings nobody reads are released early; if all are read, 429 for a new tenant |

Each connection emits one canonical line when it closes (guardrail 9): `Tail connection finished tenant=… filter=… sent=… dropped=… reason=… durationMs=…`. Tenancy as everywhere else: the tenant comes from the API key (`TenantInterceptor`), never a parameter. Browsers' `EventSource` can't send headers, so browser clients need a fetch-based SSE client; API keys in URLs would end up in access logs.

### D5. Metrics: Micrometer, recorded where the canonical lines are emitted, scraped on a management port
- **Situation:** each unit of work already emits one canonical line with its counts and durations (guardrail 9). Logs answer "what happened to this request", not "how many lines a second, how full is the WAL, now". Spring Boot Actuator is already a dependency; only `health` is exposed.
- **Task:** the rates, levels and latencies an operator needs, at negligible cost on the hot paths, without leaking one tenant's data to another.
- **Action:**
  - **Micrometer** (Boot's metrics facade) with the **Prometheus** registry (`micrometer-registry-prometheus`), at `/actuator/prometheus`. Boot adds JVM, GC, thread, process and `http.server.requests` metrics.
  - **Counters are incremented per batch or request**, from the same values the canonical lines print, so the two never disagree; a 10GB job reports lines as it goes, not when it ends. Gauges read state that already exists (WAL pending bytes, spool pending bytes, connections).
  - **Tags:** `tenant` where it is useful, plus `lane`, `outcome`, `reason`. Tenant values come from configured API keys, so they are bounded; `header` mode accepts any tenant, so a `MeterFilter` caps the `tenant` tag at `argus.metrics.max-tenants` (100) distinct values and drops meters beyond.
  - **Management port:** `management.server.port=9001`. Metrics name every tenant and their volumes, and the API port's authentication is per tenant, so the scrape endpoint lives on a port that a NetworkPolicy or firewall keeps internal. `health` stays on both.
- **Result:** the meters in §4 at about one `LongAdder` increment per batch. Prometheus scrapes `:9001/actuator/prometheus`.

### D6. Producers' canonical lines are a parser; Argus keeps its per-operation lines
- **Situation:** Stripe-style canonical lines (`canonical-log-line http_method=GET http_path=/v1/charges status=200 duration=0.050 …`, often after a `[timestamp]` prefix) don't start with `key=`, so `kv` rejects them and they are stored as `raw`. Argus's own canonical lines exist per operation (push request, ingest run, archive segment, export page and job, v1 scan) and P4 adds one per tail connection.
- **Task:** store producers' canonical lines as wide records, queryable by field; decide whether Argus also needs a generic per-request line (design §10's `CanonicalLogFilter`).
- **Action:**
  - A `canonical` parser: an optional timestamp prefix (`[…]` or ISO 8601), the `canonical-log-line` marker, then logfmt pairs. Numbers and booleans are typed (`status=200` is a number, `duration=0.050` a decimal) when the number keeps the value: a leading zero (`id=00123`, `zip=02134`), more digits than a long, or more than 15 significant digits in a decimal stay text. A `[bracket]` that isn't a time (a thread or request id) is kept as the `prefix` attribute. So NDJSON output and the tail show them as numbers. `timestamp`/`level`/`message` keys are lifted like `kv`. A JSON line with `"type": "canonical-log-line"` already parses as `json` with every field kept. Registered first in `ParserRegistry.builtIns`: its marker is unambiguous, so it can't steal other formats' lines.
  - **No generic per-request line.** `http.server.requests` (D5) gives every endpoint's rate, status and latency, and the push, ingest and export lines already say what happened inside. A line per `/suppression/check` call (possibly thousands a second) would be a log flood for no new fact.
- **Result:** canonical lines from ingested applications are stored with their fields as attributes (in Parquet, `attributes['http_path']` for a `GROUP BY`), with `source.format = canonical` marking them.

### D7. Memory budget
| Item | Bound |
|---|---|
| Tail rings | `ring-bytes` (4MB, estimated heap) × `max-tenants` (32): at most 128MB |
| Tail connections | 256 threads × a 256KB requested stack, mostly uncommitted; one batch of serialized events each (≤200 lines) |
| Metrics | A few hundred meters (tenants × tags), well under 10MB |

Within the 2.4GB heap and the native headroom of guardrail 5.

## 3. Components

| Component | Package | Role |
|---|---|---|
| `TailHub` | `tail` (new) | Per-tenant rings, `isWatched(tenant)`, `publish(tenant, events)`, connection accounting and limits, ring release after idle; `SmartLifecycle` closes every connection on shutdown |
| `TailRing` | `tail` | A bounded ring of events with sequence numbers, line and byte bounds, a condition to wait on |
| `TailConnection` | `tail` | One client: its cursor, filter, rate cap and sender thread; writes batched SSE frames, heartbeats, `dropped` / `reset` events; canonical line on close |
| `TailFilter`, `TailEvent` | `tail` | Filter parsing and matching; the event (envelope + source + estimated size) and its JSON view |
| `TailController` | `controller` | `GET /api/v2/tail?level=&source=&q=`, `Last-Event-ID`; 400 bad filter, 429 + `Retry-After` at a limit |
| `ArgusMetrics` | `metrics` (new) | The meters of §4, resolved once per tag set; the tenant-tag `MeterFilter` |
| `IngestPipeline`, `PushIngestService`, `IngestJobService`, `ReplayLane`, `Archiver`, `ExportJobService`, `Suppressor`, `WatermarkWalCapacity`, `StreamRegistry` | existing | Record metrics where their canonical lines are emitted; push and the parse stage publish to the tail (D1); `Request.tailed()` |
| `CanonicalLineParser` | `parser` | D6 |
| `TailProperties`, `MetricsConfig`, `RedisHealthConfig` | `config` | Binding; the Redis health contributor only with a Redis store |

## 4. Configuration and meters

```properties
argus.tail.max-connections=256
argus.tail.max-connections-per-tenant=16
argus.tail.max-lines-per-second=200
argus.tail.max-duration=1h
argus.tail.heartbeat=15s
argus.tail.ring-lines=10000
argus.tail.ring-bytes=4MB
argus.tail.ring-idle=1m                   # a ring outlives its last connection this long, for resumes
argus.tail.max-tenants=32                 # tenants with a ring at once: the tail's memory bound

argus.metrics.max-tenants=100
management.server.port=9001
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.probes.add-additional-paths=true   # /livez and /readyz on the API port too
management.health.redis.enabled=false     # on again only with argus.suppression.store / argus.quota.store=redis
```

| Meter (Prometheus name) | Type | Tags |
|---|---|---|
| `argus_ingest_lines_total` | counter | `tenant`, `lane`, `outcome` (`primary` / `fallback` / `unparsed` / `rejected`) |
| `argus_ingest_bytes_total` | counter | `tenant`, `lane` |
| `argus_ingest_segments_total`, `argus_ingest_segment_commit_seconds` | counter, timer | `tenant` / — |
| `argus_ingest_jobs_active` | gauge | `lane` |
| `argus_push_requests_total`, `argus_push_request_seconds` | counter, timer | `tenant`, `outcome` (`accepted`, `duplicate`, `rejected_quota`, …) |
| `argus_push_lines_total` | counter | `tenant`, `result` (`accepted` / `suppressed` / `truncated`) |
| `argus_spool_pending_bytes` | gauge | — |
| `argus_suppression_decisions_total` | counter | `tenant`, `decision` |
| `argus_store_fail_open_total` | counter | `store` (`suppression` / `quota`: Redis calls answered by failing open) |
| `argus_wal_pending_bytes`, `argus_wal_full` | gauge | — |
| `argus_archive_segments_total`, `argus_archive_bytes_total`, `argus_archive_upload_seconds` | counter, timer | `outcome` / — |
| `argus_archive_oldest_pending_seconds` | gauge | — (how far the archive is behind) |
| `argus_export_pages_total`, `argus_export_lines_total`, `argus_export_retries_total` | counter | `tenant`, `source` |
| `argus_replay_wait_seconds` | timer | — (time replay readers spent waiting for the read budget) |
| `argus_tail_connections` | gauge | — |
| `argus_tail_lines_sent_total`, `argus_tail_lines_dropped_total` | counter | `tenant` |

## 5. Tests

| Kind | Test |
|---|---|
| Unit | `TailRingTest`: sequence numbers, line and byte bounds, overtaken cursors report what was dropped, waiting and signalling |
| Unit | `TailHubTest` (with `TailConnection`): no ring while unwatched, per-tenant isolation (another tenant's lines never appear), filters, limits, a stalled writer (blocking `write`) never blocks `publish` and is told what it missed, the rate cap, `Last-Event-ID` resume and `reset`, heartbeats, ring released after idle, shutdown |
| Unit | `CanonicalLineParserTest` + `ParserContractTest` / `FormatParsersTest` cases; detection doesn't change for other formats |
| Unit | `ArgusMetricsTest` (the tenant-tag cap); in `IngestPipelineTest` / `PushIngestServiceTest` (`SimpleMeterRegistry`), counters match the canonical line's counts for a pipeline run and push requests |
| API | `TailControllerTest`: 401 without a key, SSE headers, filters, 400 for a bad `level`, 429 at each limit, `Last-Event-ID` |
| Wiring | `MetricsEndpointTest`: `/actuator/prometheus` on the management port (not the API port) lists the `argus_*` meters; health is UP without Redis; `/livez` / `/readyz` on the API port |
| Pipeline | Push and file lines published once each; stream runs and replay-lane runs publish nothing |
| Load (`load` tag) | `LiveTailIT` (T7): pushed and file-ingested marker lines reach an SSE client, p99 ≤2s; push load alone, then with 16 tail clients that never read, throughput and p99 within the T8 margin (≤20%), and a stalled client then sees `dropped`; 1,000 open tail connections (limits raised) within the memory budget, a line reaching them all |

## 6. Implementation order (one commit per step)
1. The plan (this file), ADR-6 update.
2. Metrics: `ArgusMetrics`, the Prometheus registry and management port, the meters of §4 at the existing canonical lines, Redis health.
3. Tail core: `TailRing`, `TailHub`, `TailConnection`, `TailFilter` + tests.
4. Tail taps (push, parse stage, `Request.tailed()`) and `TailController` + tests.
5. `canonical` parser + tests.
6. T7 load tests (freshness, slow clients, 1,000 connections); measurements in §9.
7. Docs: CLAUDE.md, README, FEATURES, ROADMAP, CHANGELOG, design doc §9/§10/§11.

## 7. Risks
| Risk | Mitigation |
|---|---|
| A popular tenant's ring holds large envelopes alive | `ring-bytes` from line lengths; rings exist only while tailed |
| Tail threads take CPU from ingest on a 2-CPU node | Rate cap per connection; measured in `LiveTailIT` |
| Proxies buffer SSE | `X-Accel-Buffering: no`, `Cache-Control: no-cache`, heartbeats |
| Metrics on hot paths | Counters resolved once per tag set and incremented per batch, not per line |
| The management port is exposed by accident | Separate port, documented; the scrape includes tenant ids and volumes, not log content |
| Pushed lines tailed but the client retries with a new key | They show twice on the tail, as they are stored twice (at-least-once without a key) |

## 8. Later (not P4)
- **Pre-fill the ring after a restart** from the newest WAL segments (design §5 warm-up): lines are on disk; until then, a reconnect after a restart gets `reset`.
- **Several instances:** each instance tails what it ingests. A shared tail needs a Redis Stream or Kafka topic per tenant (P5).
- **Virtual threads** once the build moves to Java 24+ (D3).
- **Dead connections behind proxies:** a proxy that keeps the server side of a closed client open makes the server's writes succeed, so the connection holds its slot until `max-duration`. A shorter idle limit, or slots reclaimed when a tenant is at its limit (oldest connection first), would bound it (§9).

## 9. Results

`LiveTailIT` (tag `load`), the application in its own 256MB JVM pushed to over HTTP. "2 CPUs" is `-Dargus.load.docker-cpus=2`: a container with 2 CPUs and 4GB, the sizing target's node; "host" is a 10-core laptop.

| Check | 2 CPUs | Host |
|---|---|---|
| Push-to-tail latency (300 single-line pushes, from sending the request to the line arriving on an open tail) | p50 6.9ms, p99 38.9ms, max 333ms | p50 12.7ms, p99 28.0ms |
| File job to its first line on the tail | 77ms | 38ms |
| Push p99 with 16 tail clients that never read, against 16 that read (4 runs) | −1.5%, +6.5%, −0.8%, −1.2% (e.g. 24.3 vs 24.6ms) | — (10s windows: too noisy, ±40%) |
| Push p99 with 16 reading clients, against no tail (what tailing costs) | +16.9%, +5.6%, −3.9%, +14.4% | — |
| Push throughput (~10k lines/s offered) | unchanged within 3% in every window | 7.1–7.4k lines/s |
| A stalled client, once it reads, is told what it missed | yes | yes |
| 1,000 open tail connections: one pushed line reaches all | fan-out p50 340ms, p99 484ms; peak heap 189MB of 256MB | p99 171ms; resident memory 489MB idle, 511MB after the fan-out |

- **T7 is met:** a line is visible on a tail within tens of milliseconds of the 202, far within 2s, and a client that stops reading costs ingest no more than one that reads: its sender thread blocks in a socket write, the ring overtakes its cursor, and publishing never waits.
- **Tailing itself costs up to ~17% of push p99** on 2 CPUs while 16 clients read (building and publishing envelopes, and 16 senders serializing 200 lines a second each); across runs it ranged from −4% to +17%, so part of it is noise. It applies only while a tenant is tailed.
- **How the slow-client check is measured:** four windows of the same push load (no tail; 16 reading clients; 16 stalled clients; no tail again). The first version compared the stalled window with the windows before and after, and failed once at +42%: the ring outlives its last client by `ring-idle`, so the "after" window was still paying the publishing cost (it was +78% itself), and the comparison mixed "tailing costs something" with "a slow client costs more". Comparing stalled with reading clients isolates the second.
- **Docker Desktop's port proxy keeps the server side of a closed client open**, so through it a client that disconnects still holds its tail slot (the server's writes keep succeeding) for at least a minute: the test raises the per-tenant limit to 32 for that reason. A real proxy or load balancer that behaves the same way would hold slots until `max-duration` (1h). A heartbeat can't detect it; an idle timeout per connection would (§8).
- **ADR-6's result holds on Java 17:** 1,000 connections, each a platform thread with a 256KB stack, fit in a 256MB heap with about 500MB resident in all. The fan-out to 1,000 clients is the slowest path measured (p99 484ms on 2 CPUs), still within T7.
- **The rate cap showed in the first run:** the last line of a 1,000-line file, parsed as one batch, never reached the tail. Past 200 lines a second a client gets a `dropped` count instead, by design (§2 D2), so the freshness check times the file's first line.
