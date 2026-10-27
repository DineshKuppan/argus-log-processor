# P1 Plan: Parsers, LogEnvelope, Suppression and Streaming Aggregation

> Phase P1 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P0 ([`P0_PLAN.md`](P0_PLAN.md)).
> Exit criteria: **T1** (≥4,150 lines/s sustained on one 4GB node) and **T6** (correct allow/deny at the 10s boundary, p99 decision <1ms locally).
> Ships as two PRs: **P1a** (parsers + envelope, §2–§7) and **P1b** (suppression + streaming aggregation, §8).

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
2. v1 `LogService` on the registry; null-safe timestamps in v1 filters and aggregators; delete `LineParser`.
3. `LogEnvelope`, `Severity`, `EnvelopeFactory`; `RawBatch.lineOffsets`.
4. Pipeline + sink on envelopes; API `format`/`pattern`; job status fields.
5. Docs (CLAUDE.md, SCALING_DESIGN.md) and load test results.

## 8. P1b outline (second PR)
- **`Suppressor` port** with `LocalSuppressionStore` (Caffeine `expireAfterWrite(10s)`, `maximumSize` 1M) and `RedisSuppressionStore` (`SET key 1 NX PX 10000`, fail open on errors). Key = `tenantId + ":" + xxHash64(trim(body))`; template fingerprinting (numbers/UUIDs → placeholders) is an opt-in flag.
- **`POST /api/v2/suppression/check`** `{"message": "..."}` → `{"decision":"ALLOW"|"DENY","windowEndsAt":…,"suppressedCount":N}`. Tenant from the `X-Argus-Tenant` header (a stand-in until P3's API keys; never from the body, guardrail 4).
- **Summaries:** when a window that denied ≥1 message expires, log one canonical line `"<msg>" suppressed N times in 10s`.
- **T6 tests:** a fake ticker for the exact boundary (9.999s deny, 10.000s allow), concurrency (exactly one ALLOW among N concurrent identical calls), Redis via Testcontainers, a latency check tagged `load` (p99 <1ms local).
- **`StreamingAggregator<S, T>`** (`accept`, `merge`, `result`) ports of the four aggregators; v1 `/aggregate` and `/aggregateV2` stream the file through `FileSource` + the registry, so the 50MB limit only remains on endpoints that return every record.
- New dependency: `com.github.ben-manes.caffeine:caffeine` (managed by Spring Boot); Testcontainers for Redis.

## 9. Risks
| Risk | Mitigation |
|---|---|
| Fallback chain slow on raw-text-heavy files | Detection picks `raw` as primary when nothing else fits, so fallback only runs for strays; load test on a mixed file |
| JSON logs without timestamps land in `dt=unknown` | Reported via `unparsed`/format stats; P2's Parquet writer partitions the same way, so no data is misplaced |
| v2 output schema change | P0 has just merged and nothing consumes the NDJSON yet; the schema is documented here and in CLAUDE.md |
| Logback layouts that don't round-trip to a regex | Reject unsupported conversion words with a 400 and a clear message instead of guessing |
