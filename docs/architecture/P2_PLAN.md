# P2 Plan: Parquet Archive, Object Storage and the Local WAL

> Phase P2 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P0, P1 and the hardening work ([`P0_PLAN.md`](P0_PLAN.md), [`P1_PLAN.md`](P1_PLAN.md), [`HARDENING_PLAN.md`](HARDENING_PLAN.md)).
> Exit criteria: **T3** (a sink stalled for 10 minutes: no OOM, sources paused, WAL under its watermark, full recovery) and **T9** (cold tier ≤3TiB at 7 years, i.e. ≥8x compression).
> Ships as two PRs, like P1:
> - **P2a** (§2–§7): Parquet + ZSTD archive, the `ObjectStore` port with filesystem (PVC) and S3 adapters, and the archiver that moves committed segments to it. Measures T9.
> - **P2b** (§8): WAL watermarks that pause ingest instead of failing, WAL compression, a GCS adapter. Measures T3.

## 1. Goal and scope

Today v2 ingest writes NDJSON segments to a local volume and stops there. The output is about 1.9x the input, and it stays on the node's 100GB SSD. P2 turns those segments into the **write-ahead log** of the target design: the archiver converts each committed segment to Parquet + ZSTD, uploads it to object storage under an offset-derived key, and deletes it from the WAL once the upload is confirmed.

| In scope (P2) | Out of scope |
|---|---|
| P2a: `EnvelopeParquetWriter` (`LogEnvelope` → Parquet, ZSTD, fixed schema) | OpenSearch hot tier (P5) |
| P2a: `ObjectStore` port; `FileSystemObjectStore` (PVC), `S3ObjectStore` (AWS S3, MinIO) | Athena / BigQuery table definitions, lifecycle rules, Object Lock (deployment configuration, documented only) |
| P2a: `Archiver`: scan the WAL, convert, upload, ack, delete; retry with backoff | Push ingest and its 429 backpressure (P3) |
| P2a: per-segment canonical log line; T9 measured | Multiple archive destinations at once, or a multi-sink router (P5, with OpenSearch) |
| P2b: `GcsObjectStore` (native client, resumable uploads) | Several Argus instances sharing one WAL volume |
| P2b: WAL watermarks: pause ingest above the high watermark, resume below the low; WAL compression | Metrics (P4) |

## 2. Decisions (STAR, per guardrail 10)

### D1. The local volume's committed segments are the WAL; checkpoints follow the WAL
- **Situation:** `LocalVolumeSink` already writes durable, offset-named segments. They are fsynced and renamed into place before the source checkpoint advances. §5 of the design says the checkpoint advances only after **every sink** acknowledges.
- **Task:** add a remote archive without letting a slow or unavailable object store stall ingest. That is the WAL's whole purpose (§4, §8, T3).
- **Action:** options were:
  - (a) upload synchronously in the writer thread and checkpoint after the upload;
  - (b) write Parquet and the WAL side by side in the writer thread;
  - (c) **treat the committed NDJSON segments as the WAL, and archive them asynchronously.**

  Chosen (c).
  - The **source** checkpoint advances when a segment is durable in the WAL, as it does today.
  - Each WAL segment is deleted only after **every destination it routes to has acknowledged it**. In P2 that is the single archive destination.
  - A crash between upload and delete re-uploads the segment to the same key, so the second upload replaces the first.
- **Result:**
  - Ingest speed doesn't depend on the object store; an outage only grows the WAL.
  - Guardrail 3 now reads "checkpoint after the WAL is durable; delete from the WAL after every sink acks", which is how Fluent Bit's filesystem buffer and Vector's disk buffer work.
  - The cost is the RPO: segments not yet uploaded are lost if the node's disk is lost. A PVC keeps them across pod restarts.
  - §5 of the design doc and guardrail 3 are updated to match.
  - (a) was rejected because an S3 outage would stop ingest at once. (b) was rejected because it doubles the hot-path work, and a failed upload would need its Parquet file rebuilt anyway.

### D2. Parquet with `parquet-java` and a hand-written `WriteSupport`
- **Situation:** ADR-3 chose Parquet + ZSTD. The Java writer, `parquet-hadoop`, is usually paired with Avro and the full Hadoop client: well over 100MB of jars and a large dependency tree.
- **Task:** write Parquet with bounded memory and the fewest dependencies possible.
- **Action:** the options were:

  | Option | Dependencies | Notes |
  |---|---|---|
  | `parquet-avro` + `hadoop-common` | Avro, Hadoop and its transitive tree | The common path; heavy and full of CVEs |
  | DuckDB JDBC `COPY TO` | ~70MB native library | Converting needs a temporary table |
  | **`parquet-hadoop` 1.15 + `hadoop-client-api` (shaded) + a `WriteSupport<LogEnvelope>`** | parquet (≈8MB), zstd-jni (7MB), hadoop-client-api (20MB, no transitive deps) | `LocalOutputFile`, `PlainParquetConfiguration`: no Hadoop `FileSystem` or `Configuration` at runtime |

  Chosen: the third row. A spike on this machine wrote 200k rows to 448KB with ZSTD. The class loader needs `hadoop-client-api` (`org.apache.hadoop.conf.Configurable`).
  - Parquet's default codec factory builds a Hadoop `Configuration`, which needs `hadoop-client-runtime` (another 30MB of shaded Hadoop dependencies). The spike first passed only because Maven had put that jar on the class path.
  - So `ZstdCodecFactory` compresses and decompresses pages with zstd-jni directly, in about 50 lines. Parquet's writer and reader both accept it.
- **Result:** about 35MB of new jars and no Avro schema to maintain. Nothing at runtime builds a Hadoop `Configuration`. The schema (§3) lives in one class, next to the code that writes it.

### D3. Archive layout and naming
- **Action:** each object's key is derived from the WAL segment's path, following the layout in §3 of the design doc:
  ```
  <prefix>/tenant=T/dt=YYYY-MM-DD/hour=HH/source=ID/gen=G/FROM-TO.parquet
  ```
  - **One object per hour of data** (changed after review): a 500MB segment covers about 1.2 hours at 10GB/day, so taking `dt`/`hour` from its first record alone would put later rows in the wrong partition, and `WHERE hour='13'` would miss them. The archiver starts a new object whenever a line's UTC hour differs from the current object's.
    - Lines without an event time stay in the current object. An object of only such lines is `dt=unknown/hour=unknown`.
    - `FROM-TO` is the object's byte range: from its first line to the next object's first line. The ranges tile the segment's, so names stay deterministic.
    - Out-of-order data is split into at most 24 objects per segment; the rest of the segment then stays in the last one.
  - A re-upload of the same segment goes to the same keys, so it replaces the earlier objects.
  - **Superseded segments:** a crashed run can commit a segment that the next run re-commits from the same start offset with a different end, because the file grew in between. `LocalVolumeSink` already replaces such a segment in the WAL. The archiver mirrors that:
    - The replacement can land in other partitions (e.g. `dt=unknown` before, a real day after), so the earlier copy's keys can't be found by listing a directory. `ArchiveIndex` records the keys of each source's latest archived segment, before the upload (so a crash mid-upload still leaves them recorded). Archiving the replacement deletes the recorded keys it doesn't overwrite.
    - Only the latest segment of a source needs recording: ingest checkpoints each segment before writing the next, so only the last committed one can be replayed.
    - Before deleting a WAL segment, it checks the segment still exists. If ingest replaced it during the upload, it stops; the replacement's upload cleans up through the index.
- **Result:** at-least-once delivery without duplicate objects. Athena and BigQuery can prune by `tenant`, `dt` and `hour`, using Hive partitioning.

### D4. Retries: the WAL is the retry queue; no Resilience4j
- **Situation:** §4 of the design names Resilience4j retry and a circuit breaker.
- **Action:** the options were:
  - (a) Resilience4j around each upload;
  - (b) **the SDK's own retries for transient errors, plus archiver backoff.**

  Chosen (b).
  - The AWS SDK's standard retry mode already retries throttling and 5xx responses with jitter, three attempts in all.
  - When a segment still fails, it stays in the WAL. The archiver waits with exponential backoff and full jitter, from `retry-initial` (1s) to `retry-max` (5 min), and starts again with the oldest segment. This works like a circuit breaker without needing one: an open circuit just means the next scan waits.
  - Any success resets the backoff.
- **Result:** one fewer library, and nothing is held in memory for a retry: a segment waiting for a retry is just a file in the WAL. Resilience4j stays an option if P5 needs per-sink bulkheads.

### D5. Convert from the WAL file, in a staging file, one segment at a time
- **Action:** the archiver:
  1. streams the NDJSON back with a Jackson `MappingIterator`, one record at a time;
  2. writes Parquet to `staging-dir`;
  3. uploads that file;
  4. deletes it.

  - **Memory:** at most one row group is buffered, on the heap (`row-group-size`, 64MB by default).
  - **Disk:** at most one staging file (≈1/10 of a 500MB source segment).
  - **CPU:** the extra JSON parse costs CPU off the ingest path. At 10GiB/day, the average rate is ≈121KiB/s, far below one thread's throughput.
- **Uploads:** S3 uses the SDK's multipart client, which reads the file part by part (16MB parts). The GCS client (P2b) uses resumable uploads.
- **Result:** bounded heap and disk however far behind the archive is, as guardrails 1 and 5 require. `archive.max-concurrent` is fixed at 1 in P2a; that's enough for 10GB/day.

### D6. Opt-in, with today's behaviour by default
- **Action:** `argus.archive.store=none` (the default) keeps the NDJSON output as the result, as it is today. `filesystem` (a PVC or a local directory) or `s3` turns the local volume into a WAL. `delete-after-upload=true` (the default) then removes each segment once it is uploaded. With `false`, the archiver writes a `START-END.ndjson.archived` marker next to the segment instead, so the NDJSON stays as a local copy and is not uploaded twice.
- **Result:** nothing changes for current users until they configure a store. Running locally needs no cloud credentials.

## 3. Components (P2a)

| Class | Package | Responsibility |
|---|---|---|
| `EnvelopeSchema` | `archive` | The Parquet `MessageType` (below) and the column names |
| `EnvelopeParquetWriter` | `archive` | `WriteSupport<LogEnvelope>` + builder over `LocalOutputFile`; ZSTD; reports the first event time and the row count |
| `ZstdCodecFactory` | `archive` | Parquet `CompressionCodecFactory` on zstd-jni (D2), level 3 |
| `ObjectStore` | `archive` | Port: `put(key, Path file)`, `list(prefix)`, `delete(key)`, `name()`. Keys use `/` and are relative to the store's prefix |
| `FileSystemObjectStore` | `archive` | A directory (PVC): write to a temp file + fsync + atomic rename (`DurableFiles`) |
| `S3ObjectStore` | `archive` | AWS SDK v2 `S3AsyncClient` with multipart enabled. Configurable endpoint and path-style access (MinIO; GCS interop untested). Credentials from the SDK's default chain, never from properties |
| `ArchiveIndex` | `archive` | The keys of each source's latest archived segment (D3), at `root/tenant=T/_archive/source=S/gen=G.json` |
| `WalSegments` | `archive` | Lists committed segments (`tenant=*/dt=*/source=*/gen=*/*.ndjson`, skipping `_staging` and `.archived` ones), oldest first by modification time; maps a segment path to its archive key. Empty directories are left in place: pruning them could race ingest creating the same directory for its next commit |
| `Archiver` | `archive` | One worker thread with lifecycle (`SmartLifecycle`): scan every `scan-interval`, convert → upload → delete superseded siblings → delete or mark the WAL segment; backoff (D4); one canonical line per segment |
| `ArchiveProperties`, `ArchiveConfig` | `config` | `argus.archive.*`; builds the configured `ObjectStore`; refuses a filesystem store inside the WAL root |

Parquet schema (snake_case names, which Athena and BigQuery handle without quoting):
```
message log_envelope {
  required binary tenant_id (STRING);
  optional int64  timestamp (TIMESTAMP(MICROS,true));          // event time; µs precision
  required int64  observed_timestamp (TIMESTAMP(MICROS,true));
  optional binary severity_text (STRING);
  required int32  severity_number;
  optional binary body (STRING);
  optional binary trace_id (STRING);
  optional binary span_id (STRING);
  optional group  resource (MAP)   { repeated group key_value { required binary key (STRING); optional binary value (STRING); } }
  optional group  attributes (MAP) { repeated group key_value { required binary key (STRING); optional binary value (STRING); } }
  required binary source_id (STRING);
  required int32  source_generation;
  required int64  source_offset;
  optional binary source_format (STRING);
}
```
- **No `event_id` column** (decided while measuring T9, §9): `eventId` is `UUIDv3(tenant, source, generation, offset)`, all four of which are columns. As a 36-character random-looking string it was 20.5 of 27.2 bytes per row. Readers re-derive it with `EnvelopeFactory.eventId(tenant, source, generation, offset)`; the four columns are the row's unique key. A column can be added later if a query engine needs it; removing one later would break queries.
- Attribute values that aren't strings (numbers, booleans, nested JSON) are stored as their JSON text, so the column type stays a fixed `map<string,string>`.
- Timestamps are truncated to microseconds: the precision Athena and BigQuery support. CRI timestamps carry nanoseconds; they keep them in the WAL only.

## 4. Configuration

```properties
argus.archive.store=none                 # none | filesystem | s3   (P2b: gcs)
argus.archive.prefix=                    # key prefix inside the store, e.g. argus/prod
argus.archive.delete-after-upload=true   # false: keep the NDJSON and mark it .archived
argus.archive.scan-interval=10s
argus.archive.staging-dir=./data/archive-staging
argus.archive.row-group-size=64MB
argus.archive.retry-initial=1s
argus.archive.retry-max=5m
argus.archive.filesystem.root=./data/archive
argus.archive.s3.bucket=
argus.archive.s3.region=
argus.archive.s3.endpoint=               # MinIO etc.; empty = AWS
argus.archive.s3.path-style=false
```

## 5. Tests (P2a)

| Test | Covers |
|---|---|
| `EnvelopeParquetWriterTest` | Read back with `ParquetFileReader`: every field, nulls, maps, non-string attributes as JSON, µs timestamps, ZSTD codec on every column |
| `ObjectStoreContractTest` (abstract) + `FileSystemObjectStoreTest`, `S3ObjectStoreTest` | `put` replaces, `list` by prefix, `delete` is idempotent, large files go multipart. The S3 test uses S3Mock (`adobe/s3mock`) via Testcontainers, because MinIO no longer publishes images to Docker Hub; it is skipped without Docker |
| `WalSegmentsTest` | Key mapping (hour from the first record, `unknown`), `_staging` and `.archived` skipped, oldest first |
| `ArchiverTest` | Upload then delete; `delete-after-upload=false` marks instead; a failing store keeps the segment and backs off, then recovers; superseded siblings deleted; a segment replaced during upload; tenants stay under their own prefixes; staging cleaned at start |
| `ArchiveConfigTest`, `ArgusWebApplicationTest` | `store=none` starts no worker; bad configuration fails at startup |
| Load (`LargeFileIngestIT`, extended) | 1GB ingest + archive to the filesystem store: compression ratio vs raw input and vs NDJSON (T9: ≥8x vs raw), archive throughput, peak heap |

## 6. Implementation order (P2a, one commit per step)
1. This plan; design doc §5 and guardrail 3 (D1).
2. `EnvelopeSchema` + `EnvelopeParquetWriter` + dependencies.
3. `ObjectStore` port, filesystem and S3 adapters, contract tests (S3Mock).
4. `WalSegments`, `Archiver`, `ArchiveProperties` / `ArchiveConfig`.
5. Docs (CLAUDE.md, design doc) and results (§9).

## 7. Risks
| Risk | Mitigation |
|---|---|
| A WAL segment whose source is still being written | Only committed `.ndjson` files are archived. They appear by atomic rename and change only when a replay supersedes them (D3) |
| The archive is far behind and the disk fills up | `min-free-bytes` still fails new segments fast. P2b replaces that with watermarks that pause ingest |
| AWS SDK 2.30+ sends checksum headers that some S3-compatible stores reject | S3Mock is tested. Others can set `requestChecksumCalculation=WHEN_REQUIRED` (exposed as `argus.archive.s3.checksums`) |
| Hadoop classes on the classpath | Only the shaded `hadoop-client-api`, nothing that starts a Hadoop `FileSystem`. Checked by the dependency tree in the PR |
| Two instances archiving one volume | Out of scope; documented. Uploads are idempotent, so the worst case is a duplicate upload, not data loss |

## 8. P2b: WAL watermarks, WAL compression, GCS (second PR)

Exit criterion: **T3**. A sink stalled for 10 minutes means no OOM, ingest pauses (never fails), the WAL stays under its watermark, and everything is archived once the sink recovers.

### 8.1 Decisions (STAR)

#### D7. Watermarks pause ingest; they don't fail it
- **Situation:** with an archive configured, the WAL drains only as fast as uploads succeed. Today a long outage fills the disk until `min-free-bytes` makes new segments **fail**, which fails the ingest job.
- **Task:** bound the WAL, and turn "sink is down" into backpressure (guardrail 2), not errors.
- **Action:** options were:
  - (a) block inside `LocalVolumeSink.open`;
  - (b) **a `WalCapacity` gate the pipeline's writer thread waits on before opening each segment;**
  - (c) reject new jobs with 429 while the WAL is full.

  Chosen (b), plus (c) for new jobs.
  - The pending (not yet archived) WAL bytes are measured from `WalSegments.pending()`. Above `high-watermark` (80% of `argus.wal.max-bytes`, 30GB), new segments wait. They resume once usage drops below `low-watermark` (60%). The gap stops the gate flapping.
  - The wait sits at a segment boundary in the writer thread, so the bounded queues stop the parser and then the reader. Memory stays at the queues' capacity. The wait polls every second and gives up when the job is cancelled.
  - Job status gains `paused` (`WAL_FULL`) and `pausedMs`. `POST /api/v2/ingest/file` returns 429 + `Retry-After` while the WAL is above the high watermark, rather than accepting a job that will only wait.
  - The gate is active only when an archive store is configured. With `store=none` nothing drains the WAL, so pausing would never end, and `min-free-bytes` stays the only limit, as before.
  - Archived segments kept with `delete-after-upload=false` don't count: they are no longer backlog. Their disk use is still limited by `min-free-bytes`.
- (a) was rejected because the sink has no way to see a job's cancel flag, so a blocked `open` could hang shutdown. (c) alone doesn't stop a job that is already running.
- **Result:** an outage pauses ingest at about 24GB of backlog, and ingest resumes by itself. The file source waits rather than skipping data. P3's push ingest will return 429 in the same state.

#### D8. WAL compression with zstd
- **Situation:** NDJSON envelopes are about 4.1x the raw input (P2a: 1GB of input became 4.45GB of WAL). So a 30GB WAL holds only about 7GB of source, which is 17 hours at 10GB/day.
- **Action:** `argus.sink.local.compression=zstd` writes `START-END.ndjson.zst`, streamed through `ZstdOutputStream` (level 1, the fastest). The archiver reads `.ndjson` and `.ndjson.zst` alike. The zstd frame is closed before the fsync, so a committed file is always complete. Default `none`: the NDJSON output keeps its format for anyone reading it directly. Setting `zstd` is recommended whenever an archive store is set.
- **Result:** the measured ratio and its ingest cost are in §9. On the P2a test data, zstd should make the WAL ≥10x smaller, so 30GB holds days of backlog.

#### D9. GCS: a small JSON-API client, not `google-cloud-storage`
- **Situation:** GCS works today only through its S3 interop API, which needs HMAC keys rather than Workload Identity.
- **Action:** options were:

  | Option | Jars | Notes |
  |---|---|---|
  | `google-cloud-storage` 2.50 | 77 jars, **43.5MB** (gRPC, Guava, Conscrypt, protobuf) | Official; more dependencies than the rest of the archive put together |
  | **`GcsObjectStore` on the JSON API** (`java.net.http`) + `google-auth-library-oauth2-http` for credentials | 19 jars, **6MB** | We own about 200 lines of protocol code: resumable uploads, list, delete |
  | S3 interop only | none | HMAC keys; no Workload Identity |

  Chosen: the JSON-API client.
  - **Upload:** resumable, in 16MB chunks (multiples of 256KB, as GCS requires). A failed chunk asks the session how far it got (`Content-Range: bytes */N` → 308 + `Range`) and resumes from there, up to 3 times. After that the upload fails and the archiver backs off (D4).
  - **Credentials:** Application Default Credentials (Workload Identity, a service account, or `gcloud` locally), scope `devstorage.read_write`.
  - **Testing:** an endpoint override, used for `fake-gcs-server` in tests.
- **Result:** native GCS with Workload Identity for 6MB of jars. The store passes the same `ObjectStoreContractTest` as the filesystem and S3 stores.

### 8.2 Components
| Class | Package | Responsibility |
|---|---|---|
| `WalCapacity` | `archive` | The gate: `awaitRoom(cancel, onPause)`, `isFull()`; refreshes pending bytes at most once a second |
| `WalProperties` | `config` | `argus.wal.max-bytes` (30GB), `high-watermark` (0.8), `low-watermark` (0.6) |
| `LocalVolumeSink` | `sink` | `compression=none|zstd`; `.ndjson.zst` output |
| `WalSegments`, `Archiver` | `archive` | Read both `.ndjson` and `.ndjson.zst` |
| `GcsObjectStore` | `archive` | D9 |
| `IngestPipeline`, `IngestProgress`, `IngestJobService`, `IngestController` | `ingest`, `controller` | Wait on the gate per segment; `paused` / `pausedMs`; 429 for new jobs while the WAL is full |

### 8.3 Configuration
```properties
argus.sink.local.compression=none        # none | zstd (recommended with an archive store)
argus.wal.max-bytes=30GB                 # pending (not yet archived) WAL bytes; only with an archive store
argus.wal.high-watermark=0.8             # above: new segments wait, new jobs get 429
argus.wal.low-watermark=0.6              # below: ingest resumes
argus.archive.store=gcs                  # new value
argus.archive.gcs.bucket=
argus.archive.gcs.endpoint=              # fake-gcs-server etc.; empty = https://storage.googleapis.com
```

### 8.4 Tests
| Test | Covers |
|---|---|
| `WalCapacityTest` | Hysteresis (pause above high, resume only below low), cancel ends the wait, inactive without an archive store |
| `LocalVolumeSinkTest` | zstd segments decompress to the same NDJSON; a replay replaces `.ndjson.zst` too |
| `ArchiverTest`, `WalSegmentsTest` | `.ndjson.zst` segments archived like `.ndjson` |
| `GcsObjectStoreTest` | `ObjectStoreContractTest` against `fake-gcs-server` (Testcontainers; skipped without Docker); chunked resumable upload of a multi-chunk file |
| `IngestPipelineTest`, `IngestControllerTest` | A full WAL pauses a running job (status `paused`), cancel works while paused, and a new job gets 429 |
| **T3** (`WalBackpressureIT`, tag `load`) | A large ingest while the store fails for a while: heap bounded, the job pauses at the watermark instead of failing, pending WAL never above the cap, then everything is archived and the job completes |
| Load | `LargeFileIngestIT` with `compression=zstd`: WAL ratio and ingest throughput against P2a |

### 8.5 Implementation order (one commit per step)
1. This section.
2. WAL compression (`LocalVolumeSink`, `WalSegments`, `Archiver`).
3. `WalCapacity` + `WalProperties`; the pipeline waits; job status; 429 for new jobs.
4. `GcsObjectStore` + `fake-gcs-server` contract test; `store=gcs`.
5. T3 test, load numbers, docs (CLAUDE.md, README/FEATURES/ROADMAP/CHANGELOG, §10 here).

## 9. Results (P2a)
- **Tests:** 255 pass on a clean checkout (`mvn -P ci test`, Docker up), up from 216 after hardening. New: `EnvelopeParquetWriterTest`, `ObjectStoreContractTest` (filesystem + S3 on S3Mock), `WalSegmentsTest`, `ArchiverTest`, `ArchivePropertiesTest`, `ArchiveConfigTest`; `ArgusWebApplicationTest` checks that no archiver runs by default.
- **T9 / load** (`LargeFileIngestIT`, 1GB synthetic access log, one JVM capped at 256MB, filesystem store, `row-group-size=64MB`):

  | Measure | Value |
  |---|---|
  | Lines ingested = rows archived | 11,020,561 |
  | Ingest | 25.0s, 440k lines/s, peak heap 200MB |
  | Archive (convert + upload, one thread) | 51–62s, 177–215k lines/s (≥42x the 4,150 lines/s peak target), peak heap ≤202MB |
  | WAL (NDJSON) | 4.45GB (4.1x the input); 0 bytes left after archiving |
  | Parquet | 73.4MB: **14.6x smaller than the input**, 60x smaller than the WAL |

  - The first run stored `event_id`: 300MB, only 3.6x. Column sizes showed `event_id` at 20.5 of 27.2 bytes per row, so it was dropped (§3).
  - `DataSeeder` draws from a small vocabulary (a few paths, IPs and user agents), so real logs compress less. T9 needs re-measuring on a real sample day.
- **Real app** (one key for tenant `acme`, S3 store on S3Mock, `prefix=argus/dev`):
  - Ingesting `app.json` (3.5MB, 25,000 lines) gave one object, `argus/dev/tenant=acme/dt=2025-09-19/hour=22/source=app/gen=0/0-3488211.parquet` (286KB), and an empty WAL. One canonical line each for ingest and archive.
  - **Outage:** with S3 stopped, a second ingest still completed. Its segment stayed in the WAL and the archiver backed off (3.8s, 7.9s, … 44s). S3Mock lost its bucket on restart (it keeps data in memory), so retries failed with `NoSuchBucket` until the bucket was recreated. The next retry then archived the segment and emptied the WAL.
- **Deviations from §1–§6:**
  - `ZstdCodecFactory` (D2): without it Parquet needs `hadoop-client-runtime`.
  - No `event_id` column (§3).
  - S3Mock instead of MinIO for tests: MinIO's images are no longer on Docker Hub.
  - `WalSegments` doesn't prune empty directories: that could race ingest creating the same directory.

### Review fixes (`/code-review high`)
| # | Finding | Fix |
|---|---|---|
| 1 | An `Error` (e.g. zstd's native library failing to load) ended the archive loop silently while `isRunning()` stayed true | The loop catches `Throwable`, logs it and backs off; the canonical line records it |
| 2 | One event time beyond ±292,000 years overflowed the µs conversion, so its segment failed forever | Archived with a null `timestamp`, the original kept in `argus.timestamp` |
| 3 | `dt`/`hour` came from a segment's first line, though a segment spans ~1.2h | One object per hour of data, at most 24 per segment (D3) |
| 4 | A failing staging disk was reported as unreadable data, and every segment was converted and failed in turn | Only invalid NDJSON counts as unreadable; disk, store and code failures stop the pass and back off |
| 5 | A replay whose partitions changed (e.g. `dt=unknown` → a real day) left the earlier copy's objects in place | `ArchiveIndex` names the earlier copy's keys wherever they landed |
| 6 | `argus.archive.store=` (blank) failed startup | Blank means `none` |
| 7 | The WAL's and the archive's day rule were two copies | `sink.EventTimePartition` |
| 8 | The backoff doc said ±50% jitter | It's 50–100% of the base; doc fixed |
| — | Truly invalid segments were retried forever | Renamed `.unreadable`, kept, not retried |
| — | Replays left orphan `.archived` markers | `LocalVolumeSink` removes the replaced copy's markers too |
| — | Guardrail 4 ("object prefix starts with the tenant") vs `argus.archive.prefix` | Guardrail 4 allows one fixed deployment prefix shared by all tenants |

After the fixes: 261 tests pass on a clean checkout (Docker up), and the 1GB load test still archives every row at 14.6x.

## 10. Results (P2b)
- **Tests:** 288 pass on a clean checkout (`mvn -P ci test`, Docker up), up from 261 after P2a. New: `WatermarkWalCapacityTest`, `GcsObjectStoreTest` (`ObjectStoreContractTest` on fake-gcs-server), `GcsResumableUploadTest`, plus zstd cases in `LocalVolumeSinkTest` / `WalSegmentsTest` / `ArchiverTest`, the pause/cancel cases in `IngestPipelineTest` and the 429 in `IngestControllerTest`.
- **T3** (`WalBackpressureIT`, tag `load`, scaled down): 500MB access log (5,381,072 lines) in one JVM capped at 256MB; zstd WAL; 25MB source segments; WAL capped at 40MB (high 32MiB, low 24MiB); the store failed every upload for the first 30s.

  | Measure | Value |
  |---|---|
  | Outcome | `COMPLETED`: the outage paused ingest instead of failing it |
  | Paused | 49.4s in all (the 30s outage, then until the archive drained the WAL below the low watermark) |
  | WAL peak | 41.9MB pending against a 33.5MB high watermark: under one 10.5MB segment over, as designed |
  | Recovery | All 5,381,072 lines archived; WAL empty; 10 failed uploads retried with backoff |
  | Heap | peak 219MB of 256MB |

  - The first run peaked at 52.4MB, almost two segments over: the gate reused a measurement up to 1s old, which could let a second segment through. `awaitRoom` now measures afresh at each segment boundary.
  - The design's 10-minute outage is `-Dargus.load.outage-seconds` (30 by default). Memory doesn't depend on the outage's length: a paused writer holds only the bounded queues.
- **WAL compression** (`LargeFileIngestIT`, 1GB, the same JVM and data as P2a):

  | `compression` | WAL vs input | Ingest | Archive | Parquet vs input |
  |---|---|---|---|---|
  | `none` | 4.14x (4.45GB) | 419k lines/s | 178k lines/s | 14.6x smaller |
  | `zstd` | **0.40x (428MB)**, 10.4x smaller | 380k lines/s (−9%) | 178k lines/s | 14.6x smaller |

  With zstd a 30GB WAL holds ≈75GB of source, about 7.5 days at 10GB/day, against 17 hours with plain NDJSON.
- **GCS:** the JSON-API store passes the shared contract test (including a 24MB, two-chunk upload) on fake-gcs-server 1.52.2. A protocol stub covers the resume paths: a failed chunk is resent from where the session is; a chunk stored but whose response was lost isn't sent twice; and repeated failures or a session that makes no progress fail instead of looping.
  - The last case was a real bug, found when the first test run hung: a server answering 308 without taking bytes made `put` loop forever.
  - fake-gcs-server finalizes an upload on a status query (`bytes */N`), where GCS answers 308. So the resume path can only be tested against the stub, and the normal path never sends a status query.
- **Deviations from §8:**
  - The GCS store uses `google-auth-library` + `java.net.http` (D9, as planned) and adds `gcs.anonymous` for emulators.
  - The pause shows as `paused` / `pausedMs` fields in job status rather than a new job state, so `RUNNING` / `COMPLETED` keep their meaning.
  - The job-slots 429 keeps its `Retry-After: 30`; the WAL-full 429 sends 60.
- **Not done:** no run against real GCS or AWS S3.

