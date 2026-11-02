# P7 Plan: Record Framing (P7a), PII Masking and Enrichment (P7b)

> Phase P7 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds the three pipeline stages of the target diagram (§3) that no earlier phase built: `framing`, `PII mask` and `enrich`. It touches P0's read → parse → write stages ([`P0_PLAN.md`](P0_PLAN.md)), P1's parser chain ([`P1_PLAN.md`](P1_PLAN.md)), P3a's push path, where suppression runs ([`P3_PLAN.md`](P3_PLAN.md)), and P4's tail taps ([`P4_PLAN.md`](P4_PLAN.md)).
> Built and merged as two PRs, like P3a / P3b and P6a / P6b. **A source whose lines are all single-line records ingests byte for byte as before:** the same records, `eventId`s, segment ranges, archive keys and hot-tier ids.
> Exit criteria:
> - **P7a:** a Java, Python or Go stack trace becomes one record in every tier (WAL, archive, hot tier, tail) and is suppressed as one record. This holds whether it arrives in a file or a push, and when it straddles a batch, a segment, a run or two push requests. `kill -9` with records across segment boundaries loses and duplicates nothing (T4). T1 is still met with framing on.
> - **P7b:** with the default detectors, no planted card number, JWT, AWS key, bearer token, password value or email appears in the WAL, the spools, the archive, the hot tier, the tail, Argus's own logs or a canonical line. Masking an already-masked line changes nothing. Kubernetes pod metadata derived from `/var/log/pods` paths is searchable as `resource.k8s.*`. T1 is still met with masking on.

## 1. Goal and scope

Since P1, every `\n`-terminated line is one record. Two things in today's data are wrong because of that, and a third is missing:
- **Multiline records are split** (CLAUDE.md "No multiline framing"). A Java exception with 60 frames becomes 61 records:
  - the head line keeps the level and time;
  - the 60 `\tat …` lines are `raw` / `unparsed` with no level, so a `level=ERROR` search finds the message but never the trace;
  - on the tail, the frames of two concurrent exceptions interleave;
  - at push time, **suppression works per line**. The 10s window drops `\tat org.springframework…` frames as copies of an earlier trace's frames, so a stored trace has holes. This is a correctness bug today, not only a usability gap.
  - Logback's `%ex` is ignored for the same reason. Kubernetes' CRI runtime splits lines over 16KB into `P` (partial) fragments that are never joined.
- **Secrets and personal data reach every tier unchanged.** This includes the 7-year archive, which can't be cleaned afterwards (Parquet objects are immutable, and the archive's purpose is retention). `IngestPipeline.sampleUnparsed` also writes raw lines into Argus's own log.
- **Records carry no deployment context.** A line from `/var/log/pods/payments_api-7d9f_…/api/0.log` doesn't say its namespace, pod or container, though the path does.

| In scope (P7a) | In scope (P7b) | Out of scope |
|---|---|---|
| A `RecordFramer` in the parse stage, carrying an open record across batches (D1) | `PiiMasker` on each line, before parsing, at both entry points: the parse stage and push receive (D7) | Rewriting data already archived or indexed (§8) |
| Continuation rules: built-in rules for Java / Python / Go traces, a configured start pattern, `none` (D2) | Built-in detectors with cheap prefilters (cards with Luhn, JWT, bearer, AWS keys, secret key=values, email; IP and phone opt-in), configured custom detectors, per-tenant selection (D8) | Detectors requested per job or request (configuration only, as for export sources) |
| Record boundaries = checkpoint, batch and segment boundaries; held-back tail records (D3) | `redact` or `hmac` replacement (D8) | Named-entity recognition (names, addresses) |
| The multiline record: body, `argus.lines`, `exception.type` (D4) | Metrics and canonical-line counts, never values (D9) | Kubernetes API lookups (labels, owners), §8 |
| Push: records framed at receive time, then suppressed and tailed as records, across requests (D5) | Enrichment: Kubernetes resource attributes from CRI paths, plus configured resource attributes per tenant (D10) | v1's `/api/v1/logs` endpoints (they stay line-based) |
| CRI partial lines (`P` … `F`) joined (D6) | | JSON-array framing, gzip/zstd *file* inputs (§8) |

## 2. Decisions (STAR, per guardrail 10)

### D1. Framing runs in the parse stage, holding one open record across batches
- **Situation:**
  - `FileSource` cuts batches (≤10,000 lines / ≤8MB) and segments (~500MB) on line boundaries. Each `RawBatch` carries the lines' byte offsets.
  - The parse stage turns each line into one envelope with `eventId = UUIDv3(tenant, source, instance, generation, line offset)`. The writer commits a segment at `lastInSegment` and checkpoints its end offset (P0 commit protocol).
  - Push requests are parsed in `PushIngestService` (to find messages for suppression and the tail) and then spooled as lines. Stream runs ingest the spool through the same pipeline.
- **Task:** join lines into records without loading anything unbounded, without a new queue or thread, and without breaking the commit protocol or the ids of single-line records.
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | (a) Frame in `FileSource`, on bytes | Batches would hold whole records | The reader would need format knowledge (which lines does the primary parser accept?); the spool reader and the push path would each need a copy |
  | **(b) Frame in the parse stage, after parsing each line, holding the open record across batches** | One place sees both the line and its parse result; no new thread or queue; push reuses the same class | The stage must move batch and segment ends back to the open record's start (D3) |
  | (c) A separate framing stage thread between reader and parser | Isolated | One more queue to bound and one more thread per run, for work that costs less than parsing |
  | (d) Leave it to agents (Fluent Bit / Vector multiline) | Nothing to build | Files and exports have no agent; the suppression bug stays for agents without multiline config |

  Chosen (b). A `framing.RecordFramer`, one per run (it has state), sits behind the port named in §3 of the design: `offer(line, offset, parsed) → completed records`, `open()`, `flush()`. It is used by `IngestPipeline`'s parse stage and by `PushIngestService`.
- **Result:** memory adds at most one open record per run (≤ `max-record-bytes`, 1MB) to the bounded queues' budget (guardrail 1). The reader, the queues and the writer are unchanged.

### D2. What continues a record
- **Situation:**
  - Every record has a head line that the source's format parses (Logback, kv, CRI, …). Continuation lines (frames, `Caused by:`, a Python traceback) are lines that parse as nothing, or that the `kv` fallback mis-parses.
  - Some sources have no structured primary format (`raw`).
- **Task:** decide continuations with few false joins, by default, at parse-stage speed, and with no tenant-supplied regex on the hot path that could backtrack catastrophically.
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | (a) Every line the primary parser rejects continues the previous record | No configuration | A `raw` or mixed source would become one giant record; an unparsable *head* would be glued onto the previous event |
  | (b) Filebeat-style `pattern` / `negate` / `match`, configured per source only | Fully general | Nothing works until someone configures it; most traces look alike |
  | **(c) Built-in continuation rules, guarded by the primary parser, plus an optional start pattern per source** | Works by default for the common runtimes; a head line can never be absorbed; the escape hatch covers other layouts | Built-in rules need maintaining |

  Chosen (c), as `framing` on file jobs and streams (`auto` default, `none`, or `start`):
  - **`auto`:** while a record is open, a line continues it when **the primary parser (if structured) does not parse it** *and* it matches a built-in rule, each anchored at the start of the line and checked on its first 256 characters:
    - an indented line (space or tab), which covers Java `\tat …`, `\t... 12 more`, Python's `  File "…", line 3` and Go's `\t/app/main.go:42`;
    - `Caused by: `, `Suppressed: `, `Traceback (most recent call last):`, `goroutine N [`, `panic: `;
    - a fully-qualified exception line, `^[\w$]+(\.[\w$]+)+(: .*)?$`, ending in `Exception`, `Error` or `Throwable` before the colon (Java's `java.lang.IllegalStateException: boom`);
    - a Python exception line (`ValueError: …`, `^[A-Za-z_][\w.]*(Error|Exception|Warning|Exit|Interrupt)(: .*)?$`), but only directly after an indented traceback line.
  - **`start:<regex>`** (≤256 characters; checked on a line's first 256 characters, so a 1MB line costs no more than a short one): a matching line starts a record and every other line continues it. This is for layouts the rules don't cover. It is set per file job or stream like `pattern` today: a tenant's own data, CPU bounded by the 256-character window.
  - **`none`:** every line is a record (P0–P6 behaviour).
  - Exports (`ExportJobService`) always use `none`: each hit is one JSON document, already a record.
  - JSON-format sources are unaffected in practice: their stack traces are inside a JSON string, and every line parses as JSON, so no line is ever a continuation.
- **Result:** a head line (one the primary parser accepts) always starts a new record, so a false join can only attach an unparsable, stack-shaped line to the event before it. The guard has no effect for a `raw` source, so there the rules alone decide, and `framing=none` turns them off.

### D3. Offsets and boundaries: every checkpoint is at a record start
- **Situation:**
  - Segments are named by their source byte range (`START-END`). The archive's object keys and `ArchiveIndex` derive from it.
  - A crash replays the open segment from the last checkpoint, and the result must be the same records with the same ids (guardrail 3).
  - A run ends at the last `\n` of a growing file, or at a spool's visible length, so the last record may still be being written.
- **Task:** keep replays deterministic and the commit protocol unchanged when records span batches, segments and runs.
- **Action:**
  - **A record's offset is its head line's offset**, so its `eventId` is the head line's. A single-line record gets exactly the id it gets today, and so does a record the framer cuts at `max-record-*` (D4).
  - **Invariant:** batch ends, segment ends and checkpoints fall only at record starts. The parse stage emits a `LogBatch` whose `endOffset` is the open record's start, and the next batch starts there.
  - **Segment boundaries:** when the reader's batch closes a segment (`lastInSegment`) with a record open, the record moves into the next segment and the committed segment ends at its start. If that would leave a segment with no records, the boundary moves to the next batch's end instead (a record is ≤1MB; a segment is 500MB).
  - **Replays are deterministic:** framing from a record start is a pure function of the bytes after it. A run resumed from a checkpoint (a record start) therefore frames exactly as the crashed run did, except for the end-of-run decision below.
  - **End of run.** The open record is emitted when no more lines can follow it in this generation:
    - the file ends at EOF and its modification time is at least `argus.framing.flush-after` (5s) ago;
    - the spool file is sealed;
    - its last append is at least `flush-after` ago;
    - or the run is `InstanceDrain`'s.
    Otherwise it is **held back**: the run commits up to its start, the checkpoint stays there, the job status reports `heldBackBytes`, and the next run re-reads it (≤1MB). A stream run whose only content is a held record commits nothing.
  - **Continuations that come too late.** A record emitted at the end of a run can still get continuation lines later (a writer that paused longer than `flush-after`). Those lines, and continuation lines at the start of a new generation (a rotated file, a new spool file), have no open record. They become their own record with `argus.framing = orphan`, so nothing is lost, and the next replay of the file frames them the same way only if timing allows. That is the existing case of a replay changing a segment's end: `ArchiveIndex` supersedes the earlier copy's objects, and the hot tier overwrites by `_id` (P2 / P5).
- **Result:** the P0 commit protocol, segment naming and checkpoint format are unchanged. A held record delays its own ingestion by at most `flush-after` plus one run interval, which is well within T5 (30s).

### D4. What a multiline record holds
- **Situation:** consumers read `body`, `severity*`, `timestamp` and attributes. The hot tier maps `body` as `text` (no keyword sub-field, so no Lucene term limit); `HotDocument` cuts only attribute and resource leaves at 8,000 characters.
- **Task:** make a trace searchable and readable in every tier without new columns or a mapping change.
- **Action:**
  - **Body:** the head line's message, then `\n` and each continuation line verbatim (a trailing `\r` removed). The head's timestamp, level, attributes, trace context and format apply to the whole record.
  - **`argus.lines`** = the number of lines, only when greater than 1.
  - **`exception.type`** (OpenTelemetry semantic convention): the class name from the first exception line, such as `java.lang.IllegalStateException` or `ValueError`. Set only when the head itself has no such attribute. It is searchable as `attr=exception.type:…`.
  - **Bounds:** `max-record-lines` (500) and `max-record-bytes` (1MB, = `max-line-bytes`). Beyond either, further continuation lines are dropped, counted in `argus.multiline.dropped` and in the job's `truncated`, and the record is marked `argus.multiline.truncated=true`. Their bytes stay inside the record's range, so offsets still tile the segment.
  - **Tail:** a record is one `TailEvent`, and its `weight` counts the whole body, as for any line.
  - **Suppression:** the key is the whole record text (head message + continuation lines), so two different traces under the same message are both kept, and a repeated identical trace is suppressed as one. With `template-fingerprint`, line numbers in frames are normalised like any number.
- **Result:** no schema change. The Parquet `body` column, the hot `body` field and `TailEvent` already hold any string.

### D5. Push: frame at receive time, suppress and tail records, across requests
- **Situation:**
  - `PushIngestService` parses each line, decides suppression in batches of 1,000 (`SUPPRESS_BATCH`, P6 D11), publishes allowed lines to the tail, and compacts the body in place before one spool append.
  - The stream run later ingests the spool through `IngestPipeline`.
  - Agents may cut a trace across two requests.
- **Task:** fix per-line suppression (§1) so the spool and every tier receive whole records. Receive-time framing must agree with the stream run's framing of the spool.
- **Action:**
  - **Within a request:** the request's lines go through the stream's framer (the stream's format plus its `framing` setting). Suppression, the tail and compaction then act on records: a denied record's lines are all dropped together, and an allowed record's lines are all spooled.
  - **Across requests:** `PushStream` keeps the framing state of its last appended request: whether it ended with a record open, and whether that record was denied. If the next request starts with continuation lines, they extend that record:
    - denied → dropped, and counted as suppressed;
    - allowed → spooled, and published to the tail as a `continued` event for the earlier record.
    The state changes only after the spool append succeeds, under the stream's append lock, so concurrent requests on one stream see it in append order.
  - **The stream run frames the spool with the same rules** and so rejoins a record cut across appends, because the spool holds only allowed records' lines.
  - **After a restart** the state is lost. Leading continuation lines are then allowed, and the run joins them to the spool's previous record, which is right when that record was allowed and wrong when it was denied. Both cases are rare: they need a restart between two requests of one trace. Accepted (§7).
- **Result:** one suppression decision per record. Pushes with single-line records take exactly the path they take today.

### D6. CRI partial lines
- **Situation:** the CRI runtime writes a long container line as `P` fragments followed by an `F` fragment. `CriLogParser` keeps `logtag` but each fragment is its own record, and a JSON body split across fragments doesn't parse.
- **Task:** restore the container's original line before parsing its body.
- **Action:** with primary format `cri`, the framer joins a `P` fragment's body with the following fragments of the same stream (`stdout` / `stderr`) up to the next `F`. Bodies are concatenated with no separator, and the result is parsed once: JSON bodies become JSON records. The record takes the first fragment's timestamp and offset. `max-record-bytes` bounds it, as in D4. A `P` that is never closed is emitted (or held) like any open record (D3). Stack-trace rules (D2) then apply to the joined lines.
- **Result:** a 50KB JSON log line from a pod is one parsed record, not four raw ones.

### D7. PII masking: text-level, before parsing, at both entry points
- **Situation:**
  - Lines enter at two points: the pipeline's parse stage (files, stream runs, exports) and `PushIngestService` (before suppression, the tail and the spool).
  - The spool is on local disk for up to `flush-interval`. `sampleUnparsed` logs raw lines.
- **Task:** keep detected values out of every tier, the spool and Argus's own logs, at ingest speed, without format-specific code.
- **Action:**

  | Option | For | Against |
  |---|---|---|
  | (a) A `PipelineStage` on parsed envelopes (body + attribute values) | Structure-aware | Runs after parsing: the spool, the tail at receive time, `sampleUnparsed` and suppression keys all see raw values first; every attribute map walked per record |
  | **(b) Text-level masking of each line, before framing and parsing, at both entry points** | One pass over one string; covers every format; the spool, logs, keys and tail never see a value | Must not break a format's syntax (JSON quoting) |
  | (c) At the sinks | Last moment | Each sink needs it; the tail and the spool still see raw values |

  Chosen (b). One `pipeline.PiiMasker` per tenant configuration (thread-safe, compiled at startup) is applied:
  - in the parse stage, to each line before framing (D1);
  - in `PushIngestService`, to each line before framing and suppression, so the spool holds masked bytes. The in-place compaction writes into a second buffer from the first line whose masked form is longer.

  Rules for detectors and replacements:
  - **Detectors never match `"` or `\`, and replacement tokens contain neither.** A JSON line therefore stays valid JSON. If a custom detector breaks a line's syntax anyway, the line falls back to another parser or `raw` and is kept (never lost).
  - **Idempotence:** a replacement token never matches any detector, so masking a masked line is a no-op. Stream runs re-mask spooled lines, and a property test checks this for every detector.
- **Result:** the spool, WAL, archive, hot tier, tail, suppression keys, unparsed samples and canonical lines see only masked text. The source file itself is the tenant's and is left unchanged.

### D8. Detectors and replacement
- **Situation:** regexes on every line are the main cost. Timestamps and ids look like numbers, and nginx `remote_addr` is operational data.
- **Task:** catch the high-risk values by default, with few false positives, and keep T1.
- **Action:**
  - **Built-in detectors.** Each runs only when its prefilter matches; a prefilter is a character scan done once per line for all detectors.

    | Detector | Matches | Prefilter | Default |
    |---|---|---|---|
    | `card` | 13–19 digits, optionally grouped by single spaces or dashes, first digit 2–6, Luhn-valid. The first-digit rule excludes epoch ms / ns, which start with 1 until 2033 | a run of ≥13 digits once separators are skipped | on |
    | `jwt` | `eyJ…\.eyJ…\.[\w-]*` | `eyJ` | on |
    | `bearer` | the token after `Bearer ` / `Basic ` | `earer ` / `asic ` | on |
    | `aws-key` | `AKIA` / `ASIA` + 16 uppercase alphanumerics; `aws_secret_access_key` values | `AKIA`, `ASIA`, `aws_secret` | on |
    | `secret-kv` | the value of `password`, `passwd`, `secret`, `token`, `api_key`, `apikey`, `authorization` keys, as `k=v`, `k: v` or `"k":"v"`, case-insensitive | one of the key names | on |
    | `email` | RFC 5322 dot-atom local part `@` domain with a dot | `@` | on |
    | `ipv4`, `ipv6` | addresses | digits and dots / colons | off |
    | `phone` | E.164 `+` and 8–15 digits | `+` | off |

  - **Custom detectors:** `argus.pii.custom[i].name` and `.pattern`, from configuration only, never from a request. They are compiled at startup and rejected if the pattern can match `"` or `\` (checked on a sample alphabet).
  - **Per tenant:** `argus.pii.enabled` (true) and `argus.pii.detectors` (the defaults above); `argus.pii.tenants.<id>.detectors` overrides the list for one tenant (an empty list turns masking off for it).
  - **Replacement**, `argus.pii.mode`:
    - `redact` (default): `[REDACTED:card]`.
    - `hmac`: `[card:9f2c…]`, the first 16 hex characters of HMAC-SHA256(`argus.pii.hmac-key`, tenant + value). Equal values correlate within a tenant without being recoverable. The key is required in this mode and comes from the environment; it never appears in `toString` or in logs.
- **Result:** the defaults cover credentials and payment data, which must never be retained, and email, which is personal data under GDPR. IP addresses and phone numbers stay visible unless a tenant opts in, because operators rely on client IPs. Cost is measured against T1 in §5 (budget: ≤15% of measured throughput, and T1's 4,150 lines/s with a wide margin).

### D9. Counting what was masked and framed, never logging it
- **Situation:** guardrail 9 asks for counts on the canonical lines. Masked values are exactly what must not be logged.
- **Task:** make masking and framing observable.
- **Action:**
  - **Canonical lines:** `Push request finished …`, `Ingest finished …` and `Export page finished …` gain `masked=N` (values replaced) and `records=N` (records after framing; `lines` stays the line count).
  - **Job and stream status** gain `records`, `multilineRecords`, `heldBackBytes` and `masked`.
  - **Meters:** `argus.pii.masked{tenant, detector}`, `argus.ingest.records{tenant}`, `argus.framing.orphans{tenant}`.
  - `sampleUnparsed` logs the line after masking.
- **Result:** an operator can see that a tenant's cards are being masked (and how many) without the logs or the scrape ever containing one.

### D10. Enrichment: resource attributes from Kubernetes paths and configuration
- **Situation:**
  - `LogEnvelope.resource` holds only `service.name` today. Parquet has a `resource` map column, and the hot tier flattens `resource`.
  - Kubernetes writes container logs at `/var/log/pods/<namespace>_<pod>_<uid>/<container>/<n>.log`, with symlinks `/var/log/containers/<pod>_<namespace>_<container>-<id>.log`.
- **Task:** attach deployment context without a dependency on the Kubernetes API and without changing ids.
- **Action:**
  - **Path-derived resource** (file jobs only): `k8s.namespace.name`, `k8s.pod.name`, `k8s.pod.uid` and `k8s.container.name`, parsed from the job's path once per run (both layouts). A path matching neither adds nothing.
  - **Configured resource:** `argus.enrich.tenants.<id>.resource.<key>=<value>` (for example `deployment.environment=prod`, `cloud.region=eu-west-1`) for every record of the tenant, and `argus.enrich.resource.*` for all tenants (a deployment's own attributes).
  - **Precedence:** the record's own (`service.name` lifted from attributes) over path-derived over configured.
  - **Where:** `EnvelopeFactory` takes a per-run resource template, merged once per record. The resource is not part of `eventId`.
  - **Kubernetes API lookups** (labels, owner workload) need RBAC and a watch cache: §8.
- **Result:** `GET /api/v2/search?attr=…` can't filter on resource yet. Instead, `service` (already a filter) and the hot tier's flattened `resource.k8s.namespace.name` are searchable, and the archive has them for Athena / BigQuery. A search filter on resource keys is a small follow-up (§8).

## 3. Components

| Component | Package | New / changed |
|---|---|---|
| `RecordFramer` (port), `StackTraceFramer` (`auto`), `StartPatternFramer` (`start`), `NoFraming` (`none`), `CriPartialJoiner` | `framing` | New |
| `FramingSpec`: parses `framing` (`auto` / `none` / `start:<regex>`) and validates its length | `framing` | New |
| `IngestPipeline` parse stage: framer per run, batch / segment end at the open record, held-back tail, mask before framing | `ingest` | Changed |
| `IngestPipeline.Request`: `framing`, and `finalRun` (drain; a sealed spool file) | `ingest` | Changed |
| `PushIngestService`: mask, frame, suppress and tail per record; per-stream framing state | `ingest` | Changed |
| `PushStream`: `framing`, last-request framing state | `ingest` | Changed |
| `IngestController`, `StreamController`: `framing` parameter | `controller` | Changed |
| `ExportJobService`: `framing=none` for export streams | `export` | Changed |
| `PiiMasker`, `PiiDetector` (built-ins), `PiiProperties` | `pipeline` | New |
| `ResourceEnricher` (path + configured), `EnrichProperties` | `pipeline` | New |
| `EnvelopeFactory`: record offset, resource template | `pipeline` | Changed |
| `ArgusMetrics`: `pii.masked`, `ingest.records`, `framing.orphans` | `metrics` | Changed |
| `IngestJobStatus`, `StreamStatus`: `records`, `multilineRecords`, `heldBackBytes`, `masked` | `ingest` | Changed |

## 4. Configuration and meters

| Property | Default | Meaning |
|---|---|---|
| `argus.framing.default` | `auto` | Framing for jobs and streams that don't request one (`none` restores P0–P6 behaviour) |
| `argus.framing.max-record-lines` | `500` | Continuation lines beyond are dropped and counted |
| `argus.framing.max-record-bytes` | `1MB` | Same, in bytes |
| `argus.framing.flush-after` | `5s` | An open record at the end of a run is emitted once its source was quiet this long; otherwise held back |
| `argus.pii.enabled` | `true` | Masking at both entry points |
| `argus.pii.detectors` | `card,jwt,bearer,aws-key,secret-kv,email` | Built-in detectors applied |
| `argus.pii.tenants.<id>.detectors` | the global list | Per-tenant override (empty: off for that tenant) |
| `argus.pii.custom[i].name` / `.pattern` | none | Configured detectors |
| `argus.pii.mode` | `redact` | `redact` or `hmac` |
| `argus.pii.hmac-key` | none | Required with `hmac`; from the environment |
| `argus.enrich.resource.*` | none | Resource attributes for every record |
| `argus.enrich.tenants.<id>.resource.*` | none | Per tenant |
| `argus.enrich.kubernetes-paths` | `true` | Path-derived `k8s.*` for file jobs |

Meters: `argus_pii_masked_total{tenant,detector}`, `argus_ingest_records_total{tenant}`, `argus_framing_orphans_total{tenant}`; the existing `tenant` tag cap applies.

## 5. Tests

| Kind | Test |
|---|---|
| Unit | `StackTraceFramerTest`: Java (nested `Caused by`, `Suppressed`, `... N more`), Python, Go panics; a head line never absorbed; `raw` primary; the 256-character window; `max-record-*` truncation counts |
| Unit | `StartPatternFramerTest`, `FramingSpecTest` (length, invalid regex → 400) |
| Unit | `CriPartialJoinerTest`: `P`+`P`+`F` → one parsed JSON record; interleaved `stdout` / `stderr`; an unclosed `P` |
| Pipeline | `IngestPipelineTest`: a trace across a batch boundary, across a segment boundary (segment ends at the record start; next segment starts there), an empty-segment boundary skipped; held back while the file is fresh, emitted once quiet; **single-line sources: records, ids and segment ranges identical with `auto` and `none`** (the compatibility check) |
| Pipeline | Crash replay: kill the run after a segment whose end moved back, resume, compare records and ids with an uninterrupted run |
| Push | `PushIngestServiceTest`: a repeated trace suppressed as one record; two traces with one message both kept; a trace across two requests (allowed / denied head); concurrent requests on one stream; the tail gets one event per record; single-line pushes unchanged |
| Stream | `StreamRunnerTest`: the spool's records rejoined across appends; a sealed spool file flushes its open record; drain flushes |
| Unit | `PiiMaskerTest`: each detector's positives and near-misses (epoch ms, ISO times, UUIDs, nginx lines, version numbers); JSON lines stay parseable; **idempotence property** over generated lines; `hmac` stable per tenant and distinct across tenants; custom pattern matching `"` rejected at startup |
| End to end | `PiiEndToEndTest`: planted values in a file job and a push; assert absent from the spool files, WAL segments, Parquet objects, hot documents (protocol stub), tail events, captured logs and canonical lines |
| Unit | `ResourceEnricherTest`: both path layouts, unmatched paths, precedence |
| Archive / hot | `EnvelopeParquetWriterTest`, `HotDocumentTest`: multiline bodies and `resource.k8s.*` round-trip |
| Load (`load` tag) | `FramingIT`: **T1** on a seeded file with 5% multiline traces, `auto` vs `none`, and with masking on vs off (budget ≤15%); **T4** `kill -9` mid-file with records across segment boundaries, exact records afterwards; a push of traces under suppression |
| Seeder | `DataSeeder` gains a `--stack-traces` ratio and planted PII for the load and end-to-end tests |

## 6. Implementation order (one commit per step)

**P7a (first PR)**
1. The plan (this file); ADR-9 and the §12 roadmap row in the design doc.
2. `framing` package: `RecordFramer`, `StackTraceFramer`, `StartPatternFramer`, `NoFraming`, `FramingSpec` + unit tests.
3. `IngestPipeline`: framer in the parse stage, record offsets, batch and segment ends at record starts, held-back tail, `finalRun` + pipeline and replay tests.
4. `CriPartialJoiner` + tests.
5. Push: receive-time framing, record suppression and tail, per-stream state; `StreamRunner` final runs + tests.
6. `framing` on the APIs and status fields; exports `none`.
7. `DataSeeder` traces; `FramingIT` (T1, T4); results in §9.
8. Docs: CLAUDE.md (remove "No multiline framing", add §P7a), README, CHANGELOG.

**P7b (second PR)**
9. `PiiMasker`, detectors, `PiiProperties` + unit and property tests.
10. Masking at both entry points; counts on canonical lines, statuses and meters; `sampleUnparsed` after masking.
11. `PiiEndToEndTest`.
12. `ResourceEnricher`, `EnrichProperties`, `EnvelopeFactory` resource template + tests.
13. `FramingIT` with masking on; results in §9.
14. Docs: CLAUDE.md, README, CHANGELOG, design doc §3 / §4.

## 7. Risks

| Risk | Mitigation |
|---|---|
| A false join attaches an unrelated line to the previous record | The primary-parser guard (a head line always starts a record); rules anchored and specific; `framing=none` or `start:` per source; `argus.lines` makes joins visible |
| A held-back record waits too long on a slow writer | `flush-after` (5s), then emitted; later continuations become `orphan` records (counted), never lost |
| Single-line sources change ids or segment ranges | The compatibility test in `IngestPipelineTest` compares `auto` with `none` byte for byte |
| A restart between two requests of one pushed trace attaches its tail to the wrong record (D5) | Rare (needs a restart mid-trace); agents with multiline enabled avoid it; documented in Known Gaps |
| A record cut across two spool files (rotation mid-trace) | The second file's leading lines become an `orphan` record; at most once per 64MB of spool |
| Masking misses values (formats the detectors don't know) | Defaults cover the high-risk classes; custom detectors; the end-to-end test plants every class; documented as best effort, not a compliance guarantee |
| Masking false positives hide operational data (ids, numbers) | Card's first-digit and Luhn rules; IP and phone off by default; per-tenant lists; `masked` counts per detector to spot surprises |
| Masking breaks a JSON line | Detectors and tokens exclude `"` and `\`; a broken line falls back and is kept |
| Throughput drops with masking and framing | Prefilters; one scan per line; measured in `FramingIT` against the ≤15% budget |
| Data archived before P7b is unmasked | Out of scope; §8 (re-archive from source, or a rewrite job) |

## 8. Later (not P7)
- **Re-masking the existing archive:** a replay-lane job that rewrites a range of Parquet objects through the masker (new detectors, or data archived before P7b).
- **Kubernetes API enrichment:** pod labels and owner workload from a watch cache (needs RBAC and a `ServiceAccount`).
- **A search filter on resource keys** (`res=k8s.namespace.name:payments`).
- **JSON-array framing** and gzip / zstd **file** inputs (the design's `JsonArrayFramer`, decompression decorators).
- **Structured exception fields** (`exception.message`, `exception.stacktrace` as separate attributes) if consumers want them apart from `body`.
- **Framing for v1's endpoints**, if v1 is kept.

## 9. Results

To be filled in from `FramingIT` and the end-to-end tests.
