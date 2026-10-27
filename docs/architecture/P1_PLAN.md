# P1 Plan: Parsers, LogEnvelope, Suppression and Streaming Aggregation

> Phase P1 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P0 ([`P0_PLAN.md`](P0_PLAN.md)).
> Exit criteria: **T1** (≥4,150 lines/s sustained on one 4GB node) and **T6** (correct allow/deny at the 10s boundary, p99 decision <1ms locally).
> Ships as two PRs: **P1a** (parsers + envelope, §2–§7; results in §10) and **P1b** (suppression + streaming aggregation, §8; results in §11). Both **implemented**.

## 1. Goal and scope

Ingest the formats Argus already has parsers for (and a configurable Logback-style layout) through **one registry shared by v1 and v2**. Every v2 record becomes a **`LogEnvelope`** with OpenTelemetry field names and a deterministic `eventId`. Then add the 10-second suppression service from the interview brief, and aggregation that doesn't need every record in memory.

| In scope (P1) | Out of scope |
|---|---|
| `LogParser` port without `parseLine(line, pattern)` (ISP fix) | Multiline framing (stack traces), gzip/zstd (later `framing` work) |
| `ParserRegistry` + `FormatDetector`, per-line fallback | PII masking, K8s enrichment (`pipeline` stages after P2) |
| Parsers: access log, JSON, Kubernetes CRI, key-value, Logback layout, raw text | Parquet / object storage (P2) |
| `LogEnvelope` (OTel names), `eventId`, severity normalization | Tenant resolution from API keys (P3; P1 uses `default`) |
| P1b: `Suppressor` + `POST /api/v2/suppression/check`, summaries on expiry | Suppressing file ingest (files are backfill: guardrail 8) |
| P1b: `StreamingAggregator`, v1 aggregation without the 50MB limit | Live tail (P4) |

## 2. Decisions (STAR, per guardrail 10)

### D1. Format detection: per source, with per-line fallback
- **Situation:** v1 and v2 only understand Nginx lines (`LineParser`); the `parser/*` classes are unused. Real files are mostly one format but contain strays (a JSON line in an access log, a garbled line).
- **Task:** pick the right parser without configuration, without paying a 5-parser probe on every line.
- **Action:** options were (a) per-line probing of every parser, (b) per-source detection only, (c) **per-source detection + per-line fallback**. Chosen (c): `FormatDetector` parses a sample (first ≤100 complete lines / 64KB of the file) with each candidate and picks the one that parses most lines, ties broken by registry priority. Each line is parsed with that **primary** parser; only if it fails are the other parsers tried in priority order. A request can name the format (`format`, or `pattern` for a Logback layout) to skip detection.
- **Result:** the common case costs one parser per line; strays are still parsed. Job status reports the format and whether it was `detected` or `requested`, plus how many lines needed a fallback.

### D2. Unparseable lines are kept (lossless archive)
- **Situation:** P0 counts and **drops** lines it can't parse. The archive is the system of record (and suppression was kept off files for the same reason).
- **Action:** options: drop + count (P0), dead-letter file, or **store as a raw envelope**. Chosen: the `raw` parser is the last fallback; such records carry `attributes["argus.unparsed"] = true` so they're easy to find and re-parse later. `argus.ingest.keep-unparsed=false` restores P0's drop-and-count behaviour.
- **Result:** no line is lost to a parser gap. `rejected` now means "dropped" (only when `keep-unparsed=false`); the new `unparsed` counter means "stored as raw".

### D3. `LogEnvelope` and `eventId`
- **Action:** a record in `model`, serialized as NDJSON by the sink (replacing `LogRecord` in v2 output; v1 keeps returning `LogRecord`). Fields, following the OpenTelemetry log data model: `eventId`, `tenantId`, `timestamp` (event time, may be null), `observedTimestamp` (ingest time), `severityText`, `severityNumber` (TRACE=1, DEBUG=5, INFO=9, WARN=13, ERROR=17, FATAL=21, unknown=0), `body`, `traceId`, `spanId`, `resource` (`service.name`), `attributes`, and `source` (`id`, `generation`, `offset`, `format`).
- `eventId = UUIDv3(tenantId, sourceId, generation, lineOffset)`: deterministic, so a replayed segment produces the same ids (guardrail 3) and a future OpenSearch `_id` deduplicates. This needs each line's start offset, so `RawBatch` gains `lineOffsets`.
- **Result:** one schema for every format; Parquet columns (P2) and OpenSearch mappings (P5) are fixed now. `dt` partitions still come from `timestamp`; records without one go to `dt=unknown`, because ingest time would break idempotent replays.

### D4. Logback layouts are compiled to a regex
- **Situation:** SKILL.md asks for custom layouts such as `%d{ISO8601} [%t] %-5p %c - %m%n`. Logback's `PatternLayout` only *formats*; there is no inverse parser in Logback.
- **Action:** `LogbackPatternParser` compiles a layout to an anchored regex: `%d{fmt[, zone]}`, `%p/%le/%level`, `%t/%thread`, `%c/%lo/%logger`, `%m/%msg/%message`, `%X{key}`, `${NAME}`, any other conversion word → attribute; literal spaces match `\s+` so `%-5p` padding works. Unsupported: `%replace`, `%ex` blocks, nested `%(...)` groups (rejected with 400).
- **Result:** the user-facing feature that `parseLine(line, pattern)` hinted at, without forcing every parser to implement a pattern overload.

### D5. Suppression is an API in P1; file ingest bypasses it (P1b)
- **Situation:** the brief needs "print only if not printed in the last 10s" with an immediate answer. Guardrail 8 uses ingest time, and a 10GB historical file is read in ~4 minutes, so ingest time can't separate its lines; files are backfill.
- **Action (chosen with the user):** `POST /api/v2/suppression/check` answers allow/deny; file ingest is untouched. HTTP push ingest (P3) uses the same `Suppressor`.
- **Result:** the archive stays lossless; the suppression logic is testable in isolation for T6.

## 3. Components (P1a)

| Class | Package | Responsibility |
|---|---|---|
| `LogParser` | `parser` (moved from `model`) | `name()`, `canParse(line)` (cheap probe), `parse(line) → LogRecord` or null. No checked exceptions, no pattern overload. `ServerAccessLogParser` keeps its legacy `parseLine(line, pattern)` method for existing callers. |
| `ServerAccessLogParser` | `parser` | Absorbs `LineParser` (deleted): precompiled regex, `Locale.ROOT`, level from HTTP status, optional trailing `category` token. |
| `JsonLogParser` | `parser` | Jackson tree; timestamp from `timestamp`/`@timestamp`/`time`/`ts` (ISO-8601 or epoch s/ms), level from `level`/`severity`/`log.level`/`levelname`, body from `message`/`msg`/`body`/`log`. Other fields become attributes (not duplicated). |
| `CriLogParser` | `parser` | Kubernetes container runtime lines: `<RFC3339Nano> <stdout|stderr> <F|P> <body>`. A JSON body is parsed with `JsonLogParser` (container apps logging JSON). |
| `KeyValueLogParser` | `parser` | `key=value` / `key="quoted value"`; must start with a pair, so access lines with query strings don't match. |
| `LogbackPatternParser` | `parser` | §2 D4. |
| `RawTextParser` | `parser` | Always succeeds: body = line, level from the first `TRACE/DEBUG/INFO/WARN/ERROR/FATAL` word. Last fallback only; never a detection candidate. |
| `ParserRegistry` | `parser` | Built-ins in priority order (access, JSON, CRI, key-value), `resolve(format, pattern)` for explicit requests (400 on unknown format or bad layout), and `chain(primary, keepUnparsed)` returning a `ParserChain` that tries the primary, then the rest, then `raw`. |
| `FormatDetector` | `parser` | §2 D1. |
| `LogEnvelope`, `Severity` | `model` | §2 D3. `Severity.normalize` maps `warning`, `err`, `crit`, `fatal`, bunyan/pino numbers (10–60), etc. |
| `EnvelopeFactory` | `pipeline` | `LogRecord` + source context → `LogEnvelope` (eventId, severity, trace/span and `service.name` lifted from attributes). |

`IngestPipeline` detects the format after opening the file, parses with the chain, builds envelopes in the parser stage, and passes `LogBatch(List<LogEnvelope>)` to the sink. `LogService` (v1) uses the same registry without the raw fallback, so v1 returns only parsed records but now understands JSON, CRI and key-value files.

## 4. API changes (P1a)

`POST /api/v2/ingest/file` gains optional `format` (`nginx_access`, `json`, `cri`, `kv`, `raw`, `logback`) and `pattern` (a Logback layout; implies `logback`). Job status gains `format`, `formatSource` (`detected`/`requested`), `fallbackParsed` and `unparsed`.

v2 output lines change from `LogRecord` to `LogEnvelope`:
```json
{"eventId":"5f0c…","tenantId":"default","timestamp":"2025-10-09T01:11:40Z","observedTimestamp":"2026-10-27T…Z",
 "severityText":"WARN","severityNumber":13,"body":"POST /api/orders HTTP/1.1",
 "attributes":{"ip":"10.0.0.12","status":403,"bytes":"1467","ref":"-","ua":"Mozilla/5.0"},
 "source":{"id":"api-app","generation":0,"offset":0,"format":"nginx_access"}}
```

## 5. Configuration
- `argus.ingest.keep-unparsed` (default `true`): store lines no parser understands as `raw` envelopes.

## 6. Tests (P1a)
| Test | Covers |
|---|---|
| `ParserContractTest` (parameterized over every parser) | `parse` never throws on garbage, returns null when `canParse` is false, is thread-safe for concurrent use |
| Per-parser tests | Sample lines from `src/main/resources/logs/*` and real-world variants (epoch timestamps, `@timestamp`, CRI with JSON body, quoted kv values, Logback layout from SKILL.md) |
| `LogParserTest` | The cases commented out in `1f6beaf`, restored against the new interface |
| `FormatDetectorTest` | Each sample file is detected correctly; mixed files pick the majority; nothing parseable → `raw` |
| `ParserRegistryTest` | Fallback order, `keep-unparsed` on/off, 400 for unknown format and bad layouts |
| `SeverityTest`, `EnvelopeFactoryTest` | Severity mapping; eventId deterministic and offset-sensitive; trace/service lifting |
| `IngestPipelineTest` | JSON and mixed-format files end to end, replay gives identical eventIds |
| Load test | T1 on the 1GB access log (P0 measured ≈957k lines/s; must stay ≫4,150) |

## 7. Implementation order (P1a, one commit per step)
1. `LogParser` port, parsers, `ParserRegistry`, `FormatDetector` (+ tests; restore `LogParserTest`).
2. v1 `LogService` on the registry; null-safe timestamps in v1 filters and aggregators.
3. `LogEnvelope`, `Severity`, `EnvelopeFactory`; `RawBatch.lineOffsets`.
4. Pipeline + sink on envelopes; API `format`/`pattern`; job status fields; delete `LineParser` (the pipeline used it until this step).
5. Docs (CLAUDE.md, SCALING_DESIGN.md) and load test results.

## 8. P1b: suppression and streaming aggregation (second PR)

### 8.1 Decisions (STAR)

#### D6. Suppression store: Caffeine locally, one Lua call in Redis
- **Situation:** the brief needs an immediate allow/deny per `(tenant, message)` for 10s. Peak ingest is ~4k lines/s, so at most ~40k distinct keys can be live in one window.
- **Task:** atomic first-wins decisions (exactly one ALLOW among concurrent identical calls), a per-window suppressed count, and T6's p99 <1ms locally.
- **Action:** options were (a) `ConcurrentHashMap` + a sweeper thread, (b) **Caffeine** (`expireAfterWrite`, `maximumSize`, removal listener), (c) Redis only. Chosen: `LocalSuppressionStore` on (b) by default; `asMap().putIfAbsent` is the atomic decision, and an expired-but-not-yet-evicted entry counts as absent. `RedisSuppressionStore` (`argus.suppression.store=redis`) runs one Lua script per call: `SET key 0 NX PX window` → ALLOW, else `INCR key` (keeps the TTL) + `PTTL` → DENY with the count. That is one round trip, and the counter is the key's own value. Any Redis error **fails open** (ALLOW, guardrail 8) with a rate-limited warning, under a 100ms command timeout.
- **Result:** decisions in microseconds locally. Redis keys start with the tenant (`<tenant>:supp:<hash>`, guardrail 4). `max-keys` defaults to 100k (2.5× the peak window), about 30MB. A size eviction can only end a window early, which allows a duplicate rather than losing a line.

#### D7. Key hash: truncated SHA-256 instead of xxHash64
- **Situation:** the outline named xxHash64, which the JDK doesn't ship.
- **Action:** a new dependency (zero-allocation-hashing) for a 64-bit hash, or the JDK's **SHA-256 truncated to 128 bits** (hardware-accelerated, well under 1µs for a log line). Chosen: SHA-256 → 22 base64url characters.
- **Result:** no new dependency, and no realistic collisions; one tenant can't craft a collision that suppresses another tenant's message, because the tenant is part of the key prefix anyway.

#### D8. Summaries: on expiry locally, not yet in Redis
- **Action:** the local store's removal listener logs one line per window that denied ≥1 message (`Suppressed message="…" count=N window=10s`); Caffeine's system scheduler evicts expired windows promptly. Redis has no expiry callback without keyspace notifications, so Redis-mode summaries are deferred to P4 (canonical lines and metrics). Each Redis DENY still returns the running count.
- **Result:** with the default local store, no suppressed signal is lost silently.

#### D9. Streaming aggregation: mergeable accumulators, bounded top-N
- **Situation:** `Aggregator.compute(List)` needs every record in memory, so v1 caps files at 50MB. `LogService` also duplicates three of the aggregators.
- **Action:** `StreamingAggregator<A, T>` with `newState()`, `accept(state, record)`, `merge(a, b)`, `result(state)` (the shape of `java.util.stream.Collector`, so it parallelizes later). It extends `Aggregator<T>` with a default `compute(List)`, so existing callers and tests are unchanged. The four aggregators become streaming; the `LogService` copies are deleted. Top-N endpoints uses **Misra–Gries** with 100k counters: exact while there are ≤100k distinct endpoints, and beyond that every endpoint with more than N/100k hits is kept, with its count low by at most N/100k. Error rate takes a bucket unit (v1 `/aggregate` keeps per-minute buckets).
- **Result:** v1 `/aggregate`, `/aggregateV2`, `/export/json`, `/export/csv` and `/cli-summary` stream the file through `FileSource` and the registry in ≤10,000-line batches, with no size limit. The 50MB limit remains only on `/parse`, `/parse/regexp` and `/filter`, which return every record.

### 8.2 Components
| Class | Package | Responsibility |
|---|---|---|
| `Suppressor` | `suppression` | `check(tenantId, message)` → `SuppressionDecision(decision, windowEndsAt, suppressedCount)`; builds the key with `MessageFingerprint` |
| `MessageFingerprint` | `suppression` | `trim`, optional template mode (UUIDs, hex ids, numbers → placeholders), SHA-256/128 base64url |
| `SuppressionStore` port, `LocalSuppressionStore`, `RedisSuppressionStore` | `suppression` | D6, D8 |
| `SuppressionController` | `controller` | `POST /api/v2/suppression/check` |
| `SuppressionProperties` | `config` | `argus.suppression.*` |
| `StreamingAggregator` + the four aggregators | `aggregate` | D9 |
| `FileSource.startAt(offset, includeUnterminatedLine)` | `source` | v1 reads a static file to its end, including a last line without `\n` (`readAllLines` did) |

### 8.3 API
`POST /api/v2/suppression/check`, header `X-Argus-Tenant` (optional, default `default`; `[A-Za-z0-9._-]{1,64}`, else 400; a stand-in until P3's API keys, never read from the body), body `{"message": "..."}` (missing → 400):
```json
{"decision":"DENY","windowEndsAt":"2026-10-27T09:00:10Z","suppressedCount":3}
```
`windowEndsAt` is when the same message is next allowed; `suppressedCount` is how many copies this window has denied, including this one (0 on ALLOW).

### 8.4 Configuration
- `argus.suppression.window` (default `10s`), `max-keys` (`100000`), `store` (`local` | `redis`), `template-fingerprint` (`false`).
- `spring.data.redis.timeout=100ms`, so a slow Redis fails open quickly instead of after Lettuce's 60s default.

### 8.5 Tests (T6)
| Test | Covers |
|---|---|
| `LocalSuppressionStoreTest` | Fake clock: DENY at 9.999s, ALLOW at 10.000s; counts; 32 concurrent identical calls → exactly one ALLOW; summary on expiry; size bound |
| `RedisSuppressionStoreTest` | Same semantics against a real Redis (Testcontainers; skipped without Docker); fail open when Redis is unreachable |
| `MessageFingerprintTest`, `SuppressorTest` | Trim, template mode, tenant isolation |
| `SuppressionControllerTest` | 200 ALLOW then DENY; 400 for a bad tenant header or missing message |
| `SuppressionLatencyIT` (tag `load`) | p99 decision <1ms with the local store |
| `AggregatorTest` | Each streaming aggregator: `merge` of split halves equals one pass; Misra–Gries exact below capacity, bounded error above |
| `LogServiceTest` | Streaming endpoints match the list-based results; a file larger than `log.max-file-size` aggregates; last line without `\n` is counted |

### 8.6 Implementation order (one commit per step)
1. This plan section.
2. `StreamingAggregator`, the four aggregators, `FileSource` option, v1 streaming endpoints (delete the `LogService` copies).
3. Suppression: fingerprint, stores, `Suppressor`, properties.
4. `POST /api/v2/suppression/check` + controller tests.
5. Docs and results (CLAUDE.md, SCALING_DESIGN.md, §11 here).

## 9. Risks
| Risk | Mitigation |
|---|---|
| Fallback chain slow on raw-text-heavy files | Detection picks `raw` as primary when nothing else fits, so fallback only runs for strays; load test on a mixed file |
| JSON logs without timestamps land in `dt=unknown` | Reported via `unparsed`/format stats; P2's Parquet writer partitions the same way, so no data is misplaced |
| v2 output schema change | P0 has just merged and nothing consumes the NDJSON yet; the schema is documented here and in CLAUDE.md |
| Logback layouts that don't round-trip to a regex | Reject unsupported conversion words with a 400 and a clear message instead of guessing |

## 10. Results (P1a)
- **Tests:** 149 pass (`mvn -P ci test`), up from 74 at the end of P0. They include a parser contract test that every parser must pass, and the `LogParserTest` cases restored from `1f6beaf`.
- **T1:** the 1GB access-log load test (256MB heap) ran at **364,726 lines/s**, 88× the 4,150 lines/s target. Peak heap was 202MB. P0 ran at about 957k lines/s; the difference is the cost of building an envelope and a UUIDv3 `eventId` per line.
- **Real app:** `POST /api/v2/ingest/file` on the bundled `app.json` detected `json` and wrote 25,000 envelopes with 0 fallback, unparsed or rejected lines. An unknown `format` returns 400 listing the known formats. v1 `/parse` on the same file now returns all 25,000 records; before P1 it returned none.
- **Deviations:**
  - `LineParser` was deleted in step 4, not step 2, because the pipeline used it until then.
  - Replayed segments are no longer byte-identical: `observedTimestamp` is the time of each run. `eventId`s, paths and every other field are identical, and the crash-replay test compares output with that field removed.
  - A record's `dt` comes from the first record in the segment that *has* an event time.

## 11. Results (P1b)
- **Tests:** 183 pass (`mvn -P ci test`), up from 155 at the end of P1a, including the Redis store against a real Redis 7.2 (Testcontainers).
- **T6:** with the fake clock, a copy at 9.999s is denied and one at 10.000s is allowed; exactly one of 32 concurrent identical calls is allowed (16 against Redis). `SuppressionLatencyIT`, 1M decisions on a mix of hot and unique messages with the local store: **p50 1µs, p99 8.8µs, p99.9 16.5µs** (target p99 <1ms).
- **T1:** the 1GB ingest load test still runs at 377,065 lines/s with a 202MB peak heap (ingest doesn't call the suppressor).
- **Real app:**
  - Local store: ALLOW, then DENY with counts 1 and 2 (a copy with surrounding spaces counts as the same message), ALLOW for another tenant, 400 for `X-Argus-Tenant: bad:tenant` and for a missing message. When the window ended, the app logged `Suppressed key=acme:supp:… count=2 window=10s message="disk full on /var"`.
  - Redis store: the same decisions; Redis held one key, `acme:supp:…`, with value 2 and a 9.5s TTL. With Redis stopped, calls were allowed after the 100ms timeout, with one fail-open warning.
  - v1 `/aggregateV2` on a 120MB, 1,291,417-line access log (over the 50MB limit) returned in 2.3s; `/cli-summary` and `/export/csv` also returned 200. `/parse` on the same file is still refused.
- **Deviations:**
  - SHA-256/128 instead of xxHash64 for the key (D7), to avoid a new dependency.
  - Testcontainers is pinned to 1.21.4: Spring Boot 3.3.3 manages 1.19.8, which Docker Engine 29 rejects (API 1.32 < minimum 1.40).
  - Real-app testing found that Redis-mode `windowEndsAt` was dated from before the call, so a slow first round trip made it 330ms early. It is now dated from the reply (`c82ec25`).
  - The streaming-vs-list test generates its access lines rather than reading `src/main/resources/logs/nginx.log`: that file is git-ignored (`*.log`), so a clean checkout doesn't have it.
