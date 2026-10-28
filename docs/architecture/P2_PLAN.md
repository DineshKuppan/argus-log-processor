# P2 Plan: Parquet Archive, Object Storage and the Local WAL

> Phase P2 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Builds on P0, P1 and the hardening work ([`P0_PLAN.md`](P0_PLAN.md), [`P1_PLAN.md`](P1_PLAN.md), [`HARDENING_PLAN.md`](HARDENING_PLAN.md)).
> Exit criteria: **T3** (a sink stalled for 10 minutes: no OOM, sources paused, WAL under its watermark, full recovery) and **T9** (cold tier ≤3TiB at 7 years, i.e. ≥8x compression).
> Ships as two PRs, like P1:
> - **P2a** (§2–§7): Parquet + ZSTD archive, the `ObjectStore` port with filesystem (PVC) and S3 adapters, and the archiver that moves committed segments to it. Measures T9.
> - **P2b** (§8): GCS adapter, WAL watermarks that pause ingest instead of failing, WAL compression. Measures T3.

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

  Chosen: the third row. A spike on this machine wrote 200k rows to 448KB with ZSTD, using only `hadoop-client-api`. The class loader needs it (`org.apache.hadoop.conf.Configurable`); `hadoop-client-runtime` isn't needed.
- **Result:** about 35MB of new jars and no Avro schema to maintain. The schema (§3) lives in one class, next to the code that writes it.

### D3. Archive layout and naming
- **Action:** each object's key is derived from the WAL segment's path, following the layout in §3 of the design doc:
  ```
  <prefix>/tenant=T/dt=YYYY-MM-DD/hour=HH/source=ID/gen=G/START-END.parquet
  ```
  - `dt` and `hour` come from the segment's first record with a timestamp. If no record has one, the path is `dt=unknown/hour=unknown`.
  - A re-upload of the same segment goes to the same key, so it replaces the earlier object.
  - **Superseded segments:** a crashed run can commit a segment that the next run re-commits from the same start offset with a different end, because the file grew in between. `LocalVolumeSink` already replaces such a segment in the WAL. The archiver mirrors that:
    - After uploading `START-END`, it deletes any other object under `gen=G/START-*` in the same directory.
    - Before deleting a WAL segment, it checks the segment still exists. If ingest replaced it during the upload, the archiver deletes the object it just uploaded, and the next scan uploads the replacement.
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
- **Action:** `argus.archive.store=none` (the default) keeps the NDJSON output as the result, as it is today. `filesystem` (a PVC or a local directory) or `s3` turns the local volume into a WAL. `delete-after-upload=true` (the default) then removes each segment once it is uploaded. With `false`, the archiver writes a `START-END.archived` marker next to the segment instead, so the NDJSON stays as a local copy and is not uploaded twice.
- **Result:** nothing changes for current users until they configure a store. Running locally needs no cloud credentials.

## 3. Components (P2a)

| Class | Package | Responsibility |
|---|---|---|
| `EnvelopeSchema` | `archive` | The Parquet `MessageType` (below) and the column names |
| `EnvelopeParquetWriter` | `archive` | `WriteSupport<LogEnvelope>` + builder over `LocalOutputFile`; ZSTD; reports the first event time and the row count |
| `ObjectStore` | `archive` | Port: `put(key, Path file)`, `list(prefix)`, `delete(key)`, `name()`. Keys use `/` and are relative to the store's prefix |
| `FileSystemObjectStore` | `archive` | A directory (PVC): write to a temp file + fsync + atomic rename (`DurableFiles`) |
| `S3ObjectStore` | `archive` | AWS SDK v2 `S3AsyncClient` with multipart enabled. Configurable endpoint and path-style access (MinIO; GCS interop untested). Credentials from the SDK's default chain, never from properties |
| `WalSegments` | `archive` | Lists committed segments (`tenant=*/dt=*/source=*/gen=*/*.ndjson`, skipping `_staging` and `.archived` ones), oldest first by modification time; maps a segment path to its archive key; prunes empty directories |
| `Archiver` | `archive` | One worker thread with lifecycle (`SmartLifecycle`): scan every `scan-interval`, convert → upload → delete superseded siblings → delete or mark the WAL segment; backoff (D4); one canonical line per segment |
| `ArchiveProperties`, `ArchiveConfig` | `config` | `argus.archive.*`; builds the configured `ObjectStore`; refuses a filesystem store inside the WAL root |

Parquet schema (snake_case names, which Athena and BigQuery handle without quoting):
```
message log_envelope {
  required binary event_id (STRING);
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
| `ObjectStoreContractTest` (abstract) + `FileSystemObjectStoreTest`, `S3ObjectStoreTest` | `put` replaces, `list` by prefix, `delete` is idempotent, large files go multipart. The S3 test uses MinIO via Testcontainers and is skipped without Docker |
| `WalSegmentsTest` | Key mapping (hour from the first record, `unknown`), `_staging` and `.archived` skipped, oldest first |
| `ArchiverTest` | Upload then delete; `delete-after-upload=false` marks instead; a failing store keeps the segment and backs off, then recovers; superseded siblings deleted; a segment replaced during upload; tenants stay under their own prefixes; staging cleaned at start |
| `ArchiveConfigTest`, `ArgusWebApplicationTest` | `store=none` starts no worker; bad configuration fails at startup |
| Load (`LargeFileIngestIT`, extended) | 1GB ingest + archive to the filesystem store: compression ratio vs raw input and vs NDJSON (T9: ≥8x vs raw), archive throughput, peak heap |

## 6. Implementation order (P2a, one commit per step)
1. This plan; design doc §5 and guardrail 3 (D1).
2. `EnvelopeSchema` + `EnvelopeParquetWriter` + dependencies.
3. `ObjectStore` port, filesystem and S3 adapters, contract tests (MinIO).
4. `WalSegments`, `Archiver`, `ArchiveProperties` / `ArchiveConfig`.
5. Docs (CLAUDE.md, design doc) and results (§9).

## 7. Risks
| Risk | Mitigation |
|---|---|
| A WAL segment whose source is still being written | Only committed `.ndjson` files are archived. They appear by atomic rename and change only when a replay supersedes them (D3) |
| The archive is far behind and the disk fills up | `min-free-bytes` still fails new segments fast. P2b replaces that with watermarks that pause ingest |
| AWS SDK 2.30+ sends checksum headers that some S3-compatible stores reject | MinIO is tested. Others can set `requestChecksumCalculation=WHEN_REQUIRED` (exposed as `argus.archive.s3.checksums`) |
| Hadoop classes on the classpath | Only the shaded `hadoop-client-api`, nothing that starts a Hadoop `FileSystem`. Checked by the dependency tree in the PR |
| Two instances archiving one volume | Out of scope; documented. Uploads are idempotent, so the worst case is a duplicate upload, not data loss |

## 8. P2b: GCS, watermarks, WAL compression (second PR)
- **D7. GCS native adapter:** `google-cloud-storage` with resumable uploads and Application Default Credentials / Workload Identity. Tested against `fake-gcs-server` (Testcontainers). The S3 interop API is the fallback.
- **D8. Watermarks:** the WAL is capped at `argus.wal.max-bytes` (30GB).
  - Above the high watermark (80%), `LocalVolumeSink.open` **blocks** the writer thread, so the bounded queues pause the reader (guardrail 2). Today it fails instead.
  - Below the low watermark (60%), it resumes.
  - Job status shows `PAUSED_WAL_FULL`. P3's push ingest returns 429 in the same state.
- **D9. WAL compression:** `argus.wal.compression=zstd` writes `.ndjson.zst` segments, about 10x smaller, so the 30GB WAL holds days of backlog rather than hours. `zstd-jni` is already on the classpath through Parquet.
- **Tests:** T3 as a test: a store that fails for N seconds while a large file ingests. Checks: heap stays bounded, ingest pauses at the watermark, and everything is archived once the store recovers.

## 9. Results (P2a)
_Filled in after implementation._
