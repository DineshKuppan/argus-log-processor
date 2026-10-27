# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Argus Log Processing Service** is a Spring Boot application that provides efficient log processing, parsing, filtering, and aggregation capabilities. The service runs on port 9000 and supports multiple log formats (JSON, Nginx access logs, key-value logs).

## Prerequisites

- Java 17 (the build target). **Build with JDK 17 or 21, not 24+**: the Lombok version managed by Spring Boot 3.3.3 (1.18.34) crashes on JDK 24, and every Lombok-generated getter then fails to compile. If your default JDK is newer, prefix Maven commands with e.g. `JAVA_HOME=~/.sdkman/candidates/java/17.0.14-tem`.
- Maven 3.8+
- Redis (optional today: configured on localhost:6379 but not used by any code yet)
- Docker (only for the 4GB-container load check described under Testing)

## Build and Run Commands

### Build the project
```bash
# Clean build with formatting (uses Google style)
mvn clean package

# Skip tests during build
mvn clean package -DskipTests

# Run with the local profile (includes code formatting)
mvn clean package -P local
```

### Run the application
```bash
# Start via Maven (includes live reload via DevTools)
mvn clean spring-boot:run

# Start via JAR
java -jar target/argus-log-processor-0.1.0.jar

# Production-like JVM flags for a 4GB RAM node (heap ≈2.4GB, bounded off-heap)
java -XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=512m -XX:+ExitOnOutOfMemoryError \
  -jar target/argus-log-processor-0.1.0.jar
```

The application starts on **http://localhost:9000**. Swagger UI is available at **http://localhost:9000/swagger-ui.html**.

### Testing
```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=AggregatorTest

# Run a specific test method
mvn test -Dtest=AggregatorTest#testCountByLevel

# Load test (tag "load", excluded by default): ingests a generated file in a JVM capped at 256MB
mvn test -Dgroups=load -DexcludedGroups=none                              # 1GB input
mvn test -Dgroups=load -DexcludedGroups=none -Dargus.load.input-size=4GB  # needs ~4x input in free disk

# Generate a large Nginx log for manual runs (streams; never builds the file in memory)
java -Dloader.main=dev.dinesh.argus.seeder.DataSeeder -cp target/argus-log-processor-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher data/in/big.log 10GB
```

## Architecture

### Core Components

**Models** (`dev.dinesh.argus.model`)
- `LogRecord`: what a parser returns (timestamp, level, message, metadata map); also the v1 API's record type
- `LogEnvelope`: the canonical v2 record, following the OpenTelemetry log data model (`eventId`, `tenantId`, `timestamp`, `observedTimestamp`, `severityText`/`severityNumber`, `body`, `traceId`/`spanId`, `resource`, `attributes`, `source`). `Severity` maps level spellings to OTel numbers

**Parsers** (`dev.dinesh.argus.parser`)
- `LogParser` port: `name()`, a cheap `canParse()` probe (no false negatives) and `parse()` returning null, never throwing. Must pass `ParserContractTest`
- Built-ins, in priority order: `ServerAccessLogParser` (`nginx_access`), `JsonLogParser` (`json`), `CriLogParser` (`cri`, Kubernetes), `KeyValueLogParser` (`kv`); plus `RawTextParser` (`raw`, last fallback) and `LogbackPatternParser` (`logback`, a layout compiled to a regex; only when requested)
- `ParserRegistry`: resolves a requested format, `detect()`s one from a sample (`FormatDetector`: most parsed lines wins), and builds a `ParserChain` (primary → other parsers → raw). When detection finds no format (`raw`), every line still tries the structured parsers first

**Service Layer** (`dev.dinesh.argus.service`)
- `LogService`: Central service orchestrating parsing, filtering, aggregation, and export operations
  - File-based parsing with 50MB default limit (configurable via `log.max-file-size`)
  - Filter application using composite pattern
  - Multiple aggregation strategies
  - Export to JSON and CSV formats

**Controller** (`dev.dinesh.argus.controller`)
- `LogController` (`/api/v1/logs`): parse, filter, aggregate, export. Synchronous; loads the whole file (50MB cap) and returns results inline
- `IngestController` (`/api/v2/ingest`): streaming ingest of files of any size, as async jobs. `POST /file` with `path`, optional `sourceId`, `fromBeginning`, `format`, `pattern` (202, or 400 / 404 / 409 source busy / 429 + `Retry-After` slots busy), `GET` and `DELETE /jobs/{id}`

### Streaming Ingest (P0)

Read → parse → write stages in separate threads, connected by bounded queues (raw 2, parsed 4 batches). A full queue blocks the stage before it, so heap use is set by queue capacity, not file size.
- **Batch** (≤10,000 lines / ≤8MB) is the in-memory unit; **segment** (~500MB of source) is the durability unit: one output file and one checkpoint.
- **Commit protocol:** a segment is fsynced and renamed into place (and its directory fsynced) *before* its end offset is checkpointed. A crash or power loss replays at most the open segment, and its output is replaced, not duplicated. Use `utils.DurableFiles` for any rename that must survive power loss.
- `source.FileSource`: `FileChannel` reader with exact byte offsets; batches end only on line boundaries. A run stops at the last `\n`: a trailing line without one may still be being written, so it waits for the next run (as Filebeat / Fluent Bit do).
- `checkpoint.FileCheckpointStore`: one JSON file per source (temp file + fsync + atomic rename + directory fsync). The checkpoint stores the inode, a SHA-256 of the first 1KB and a **generation**. `ResumePoint` compares them with the *opened* file and restarts from 0 in a new generation if it was replaced (inode), truncated (size) or truncated-and-regrown (head hash, e.g. `copytruncate`).
- `sink.LocalVolumeSink`: NDJSON at `tenant=default/dt=YYYY-MM-DD/source=ID/gen=G/START-END.ndjson`. `dt` comes from the first event time in the segment (`unknown` if none) and the name from offsets, so replays land on the same path; `gen` keeps a rotated file's output from replacing the previous file's.
- `ingest.IngestPipeline` runs one job; `ingest.IngestJobService` runs jobs on `max-concurrent-jobs` slots with no queue (rejects instead of buffering), and only one job per source at a time.
- **Formats (P1):** the primary parser is detected from the file's first 100 lines (or requested with `format` / `pattern`). Each line tries the primary, then the other parsers, then `raw`; job status reports `format`, `formatSource`, `fallbackParsed`, `unparsed` and `rejected`. Lines no parser understands are kept as `raw` records with `attributes["argus.unparsed"]` (`keep-unparsed=false` drops them instead).
- `pipeline.EnvelopeFactory` turns parsed lines into `LogEnvelope`s: `eventId = UUIDv3(tenant, source, generation, line offset)`, so replays reproduce the same ids; trace/span ids and `service.name` are lifted out of the attributes.
- Source ids become directory names, so `SourceOffset.requireValidSourceId` limits them to `[A-Za-z0-9._-]`. Ingest paths must resolve (after symlinks) under `argus.ingest.allowed-roots`.

### Filter Architecture

**Interfaces** (`dev.dinesh.argus.filter`)
- `LogFilter`: Single responsibility interface for filtering log records
- `FilterSpec`: Combines multiple filter criteria (time range, levels, regex patterns)

**Implementations** (`dev.dinesh.argus.filter.impl`)
- `TimeRangeFilter`: Filters logs by timestamp range
- `LevelFilter`: Filters by log level (INFO, ERROR, DEBUG, etc.)
- `RegexFilter`: Pattern matching against log messages

### Aggregation Architecture

**Interface** (`dev.dinesh.argus.aggregate`)
- `Aggregator<T>`: Generic interface for computing statistics over log collections

**Implementations** (`dev.dinesh.argus.aggregate.impl`)
- `CountByLevelAggregator`: Groups logs by severity level and counts
- `TopNEndpointsAggregator`: Extracts endpoints from HTTP request logs and ranks by frequency
- `ErrorRateAggregator`: Calculates error rate over time windows
- `DurationAggregator`: Computes time duration statistics (configurable time unit)

### Code Formatting

The project uses the **Google style** code formatter via the `fmt-maven-plugin`. The `local` profile is **active by default**, so *any* Maven build (`mvn compile`, `mvn test`, …) rewrites Java files in `src/main/java` and `src/test/java`, including untracked ones. Use `-P ci` (or `-P '!local'`) to build without reformatting.

To manually format:
```bash
mvn fmt:format -P local
```

## Current State & Known Gaps

Verified against the code; fix these before or alongside scaling work:
- **v1 still reads whole files:** `LogService.readLines` uses `Files.readAllLines` behind a 50MB guard. Use `/api/v2/ingest` for large files; v1 moves onto the pipeline once aggregation streams (P1).
- **v1 has no path restriction:** `?file=` / `"file"` accepts any readable path on the host (arbitrary file read). v2 enforces `allowed-roots`.
- **Access-log dates use `Locale.ROOT`** (English `Sep`, as Nginx writes). Local `nginx.log` files generated before this change on an `en_GB` / `en_IN` JVM contain `Sept` and are now rejected; regenerate them with `DataSeeder`.
- **No multiline framing:** a Java stack trace becomes one record per line (each continuation line is `raw` / `unparsed` unless it matches a format). Logback `%ex` is ignored for the same reason.
- **Records without an event time** (JSON / kv lines with no timestamp field, or a Logback layout whose `%d` has no date, e.g. `%d{HH:mm:ss.SSS}`, whose time is kept as the `timestamp` attribute) land in `dt=unknown`, and v1's time filters and time buckets skip them.
- **Non-streaming aggregation:** `Aggregator.compute(List<LogRecord>)` requires every record in memory.
- **Durability is local only:** v2 checkpoints and writes NDJSON to a local/PVC volume. No object storage, WAL watermarks or retries yet (P2).
- **Redis unused:** configured, but there is no `@EnableCaching` / `@Cacheable` anywhere.
- **No tenancy:** no tenant id, quotas, or isolation.

## Target Architecture & Guardrails

Full decomposition, capacity maths, STAR-framed ADRs and roadmap: **[`docs/architecture/SCALING_DESIGN.md`](docs/architecture/SCALING_DESIGN.md)**. Inputs: `PLAN.md` (distributed target) and `SKILL.md` (format matrix).

**Sizing target:** 10GB/day (~36M lines, ~4k lines/s peak) from files, streams and centralized logging exports (Coralogix / ELK), on one node with **4GB RAM / 100GB SSD**. Output goes to S3, GCS or a Kubernetes PVC. Shape: **modular monolith first** (ports and adapters), so stage boundaries can later become Kafka topics.

**Pipeline (each stage is a port with pluggable adapters, extending the existing Strategy pattern):**
```
source → framing → parser (ParserRegistry/FormatDetector) → pipeline (normalize → PII mask → suppress 10s → enrich)
       → WAL → sink (OpenSearch hot 1-14d | Parquet+ZSTD on S3/GCS/PVC cold 1-7y)
                 ↳ tail (SSE live tail, after suppression)      checkpoint ← sink acks
```
Planned packages: `source`, `framing`, `pipeline`, `sink`, `checkpoint`, `tail`, `tenant`, alongside the existing `parser`, `model` (`LogEnvelope`), `filter` and `aggregate` (`StreamingAggregator`).

**Guardrails (every change must follow these):**
1. **Never load unbounded data.** Stream sources in ≤500MB segments and process ≤10,000-line / ≤8MB micro-batches. No `readAllLines`, no `List` of a whole file.
2. **Every inter-stage queue is bounded** and declares its overflow policy (block, 429 + `Retry-After`, spill to WAL, or DLQ). Pull sources pause; push sources return 429.
3. **At-least-once delivery with idempotent sinks:** advance a checkpoint only after every routed sink acks. Writes are keyed by a deterministic `eventId = hash(tenantId, sourceId, offset)` or offset-derived object names.
4. **Tenant isolation:** every record carries `tenantId`, which is resolved from credentials, never from the body. Every cache key, Redis key, index and object prefix starts with it.
5. **Memory budget (4GB container):** `-XX:MaxRAMPercentage=60` (≈2.4GB heap), `-XX:MaxDirectMemorySize=512m` for Parquet / ZSTD / upload buffers.
6. **Local SSD is a working area, not the hot tier.** 14 days of raw logs (140GB) doesn't fit, so the WAL is capped at 30GB with high/low watermarks, and segments are deleted only after all sinks ack.
7. **Live tail never backpressures ingest:** per-connection bounded buffer, drop oldest, `{"dropped":N}` marker.
8. **Suppression uses ingest time** and **fails open** (duplicates are better than lost logs). Replay/backfill bypasses suppression and runs on a lower-priority lane.
9. **Emit one canonical log line per batch/request** (Stripe-style) with offsets, counts and latencies, in a `finally` block.
10. **Decide with STAR before building:** any new stage, store or format gets a short STAR ADR in the design doc first.

## Key Design Patterns

1. **Strategy Pattern**: Pluggable parsers (`LogParser` interface) and aggregators (`Aggregator<T>` interface)
2. **Composite Pattern**: `FilterSpec` and `LogFilter` allow combining multiple filter conditions
3. **Service Facade**: `LogService` abstracts complexity of multi-step log processing workflows
4. **Repository Pattern**: `DataSeeder` generates synthetic logs for testing and development

## Configuration

Key properties in `application.properties`:
- `server.port=9000`: Service port
- `log.max-file-size=52428800`: Max file size in bytes (50MB default)
- `spring.cache.type=redis` + `spring.data.redis.*`: Redis cache settings (see Known Gaps: caching is not enabled in code yet)
- `spring.devtools.*`: LiveReload and restart configuration for local development
- `argus.ingest.*`: streaming ingest. `batch-max-lines` / `batch-max-bytes` (in-memory unit), `segment-bytes` (commit unit), `raw-queue-capacity` / `parsed-queue-capacity`, `max-line-bytes` (longer lines are truncated and counted), `max-concurrent-jobs`, `allowed-roots`, `checkpoint-dir`, `keep-unparsed` (default `true`)
- `argus.sink.local.*`: `root` (output volume) and `min-free-bytes` (a new segment fails fast below this)
- Runtime data (`./data/` by default) is git-ignored

## Testing Notes

- Unit tests use JUnit 5 and Mockito
- Test files are located in `src/test/java/dev/dinesh/argus/`
- Parser tests: `ParserContractTest` (every parser: never throws, honours `canParse`, thread-safe), `FormatParsersTest` (format-specific cases), `ParserRegistryTest` (detection and fallback order), `LogParserTest`
- Aggregator tests verify statistical calculations
- Filter tests verify filtering conditions
- `ArgusWebApplicationTest` starts the full context, so bean wiring and property binding errors fail `mvn test`
- Ingest tests cover offsets and resume (`FileSourceTest`), crash-safe checkpoints (`FileCheckpointStoreTest`), idempotent segments (`LocalVolumeSinkTest`), backpressure / crash-rerun / cancel (`IngestPipelineTest`), and job slots and path checks (`IngestJobServiceTest`, `IngestControllerTest`)

## Common Development Tasks

### Adding a new log format parser
1. Create a class implementing `LogParser` in `dev.dinesh.argus.parser`: a stable `name()`, a cheap `canParse()` with no false negatives, and `parse()` returning null (never throwing) for lines it doesn't own. Put the event time, level and message in `LogRecord`, and everything else in a mutable `meta` map
2. Add it to `ParserRegistry.builtIns` at the right priority (earlier wins detection ties and is tried first as a fallback). A format that needs configuration (like `logback`) is resolved in `ParserRegistry.resolve` instead
3. Add it to `ParserContractTest.parsers()` and add format-specific cases to `FormatParsersTest`
4. Optionally extend `DataSeeder` to generate sample data in the new format

### Adding a new aggregation function
1. Create a class implementing `Aggregator<T>` in `dev.dinesh.argus.aggregate.impl`
2. Implement `compute(List<LogRecord>)` method
3. Add unit test in `AggregatorTest`
4. Integrate in `LogService.runAggregations()`

### Adding a new filter type
1. Create a class implementing `LogFilter` in `dev.dinesh.argus.filter.impl`
2. Implement `matches(LogRecord)` method
3. Update `LogController.aggregateLogsV2()` to accept the new filter type if needed

### Adding a Source, Sink or pipeline stage (target architecture)
1. Implement the port (`LogSource`, `LogSink`, or `PipelineStage`) in its package (`source`, `sink`, `pipeline`)
2. Sources must emit `LogBatch` with a resumable `SourceOffset` and respect backpressure (block / pause / 429)
3. Sinks must be idempotent (offset-derived object names or `_id = eventId`), and `SegmentWriter.commit` must return only once the segment is durable
4. Run the shared port contract test so the adapter is substitutable (LSP)
5. Check it against the Guardrails above and the ADRs in `docs/architecture/SCALING_DESIGN.md` §11

## External Dependencies

- **Spring Boot 3.3.3**: Web framework and dependency injection
- **Jackson**: JSON serialization/deserialization
- **OpenCSV**: CSV export functionality
- **SpringDoc OpenAPI 2.5.0**: Swagger/OpenAPI documentation
- **Lombok**: Reduces boilerplate (annotations like `@Data`, `@Slf4j`)
- **Spring Data Redis**: Caching layer
- **Spring DevTools**: Live reload and restart during development

---

## Staff Engineer Level Code Review

Comprehensive code reviews following SOLID, Clean Code, DRY, KISS principles and production-grade standards.

### Quick Review Commands

#### Current Branch Changes (Production-Grade Review)
Review the diff on current branch against main with focus on:
- SOLID principles violations
- Clean Code violations
- Performance and security issues
- Design pattern misuse
- Production readiness

```bash
# Run code review on pending changes with effort level set to high (more thorough)
/code-review high

# Run ultra-detailed review in the cloud (multi-agent, industry-standard)
/code-review ultra

# Same but save findings as inline PR comments (requires GitHub PR)
/code-review high --comment
```

#### Full Codebase Architecture & Quality Audit
Comprehensive analysis of the entire codebase for structural issues:

```bash
# Audit entire codebase for architecture, design, and code quality issues
# This explores the full project against Staff Engineer standards
/code-review ultra src/main/java --comment
```

### Review Dimensions & Standards

#### 1. SOLID Principles
**S - Single Responsibility Principle**
- Each class should have one reason to change
- Services should not mix concerns (e.g., `LogService` should focus on log operations, not export formatting)
- Filters, parsers, and aggregators each handle one concern

**O - Open/Closed Principle**
- Classes should be open for extension, closed for modification
- Strategy pattern in `LogParser` and `Aggregator<T>` interfaces
- New parsers/aggregators added without modifying existing code

**L - Liskov Substitution Principle**
- Implementations must be substitutable for their interfaces
- All `LogParser` implementations must work interchangeably
- All `Aggregator<T>` implementations must return compatible results

**I - Interface Segregation Principle**
- Clients should not depend on interfaces they don't use
- `LogFilter` is minimal (single `matches()` method)
- `LogParser` provides only essential parsing operations

**D - Dependency Inversion Principle**
- Depend on abstractions, not concrete classes
- `LogController` depends on `LogService` interface/abstraction
- Aggregators and filters are injected/composed as needed

#### 2. Clean Code Standards
- **Meaningful Names**: Classes (`CountByLevelAggregator`, `TimeRangeFilter`) clearly express intent
- **Small Functions**: Methods should fit on one screen, single purpose
- **Comments**: Only WHY, not WHAT—well-named code is self-documenting
- **Error Handling**: Graceful degradation (e.g., `parseLineSafely()` filters nulls)
- **No Code Duplication**: Reusable components via Strategy pattern
- **Formatting**: Google style enforced via Maven plugin

#### 3. DRY (Don't Repeat Yourself)
- **Reusable Parsers**: `LogParser` interface eliminates duplicate parsing logic
- **Reusable Filters**: `FilterSpec` and `LogFilter` allow composable filtering
- **Reusable Aggregators**: `Aggregator<T>` interface enables new aggregations without code duplication
- **Shared Utilities**: `JsonUtils`, `Waits` for common operations

#### 4. KISS (Keep It Simple, Stupid)
- **Simple Abstractions**: Three-layer architecture (controller → service → model)
- **Straightforward APIs**: REST endpoints map clearly to operations
- **Minimal Configuration**: Redis cache and file size limits only
- **Predictable Behavior**: Composable filters follow single concern principle

#### 5. Production Standards
- **Error Handling**: Safe parsing with null handling, file size validation
- **Logging**: SLF4J with appropriate levels (info, debug, error)
- **Testing**: Unit tests for core logic (parsers, aggregators, filters)
- **Configuration Management**: Properties-based config, no hardcoding
- **Performance**: Streaming logs, lazy evaluation where applicable
- **Security**: Input validation (regex patterns), file size limits
- **Monitoring**: Actuator endpoints available for health checks

### Review Checklists

#### Architecture Review
- [ ] Layering: Controller → Service → Model clearly separated
- [ ] Strategy pattern correctly applied to parsers/aggregators
- [ ] Composite pattern for filter composition
- [ ] No circular dependencies between layers
- [ ] Service layer doesn't expose internal details
- [ ] Controllers are thin (business logic in service)
- [ ] Proper abstraction levels (interfaces, concrete classes)

#### SOLID Review
- [ ] No god objects (methods doing multiple concerns)
- [ ] Each class has one reason to change
- [ ] Can extend functionality without modifying existing code
- [ ] Implementations are substitutable for interfaces
- [ ] Classes don't depend on unused interface methods
- [ ] Abstractions, not concrete implementations are dependencies

#### Clean Code Review
- [ ] Variable/method/class names clearly express intent
- [ ] No cryptic abbreviations (except standard ones)
- [ ] Methods ≤20 lines, functions single-purpose
- [ ] Comments explain WHY, not WHAT
- [ ] No TODO/FIXME comments without context
- [ ] Consistent indentation and formatting (Google style)
- [ ] Exception handling is specific, not generic
- [ ] No magic numbers (use constants instead)

#### DRY Review
- [ ] No duplicate code across parsers/filters/aggregators
- [ ] Reusable components extracted to shared modules
- [ ] Utility functions (JsonUtils, Waits) centralized
- [ ] Configuration not duplicated in multiple files
- [ ] Test data generation centralized (DataSeeder)

#### KISS Review
- [ ] No premature optimizations
- [ ] Algorithms straightforward and understandable
- [ ] Simple data structures (Map, List, not complex custom)
- [ ] Configuration minimal and clear
- [ ] No unnecessary abstractions
- [ ] Straightforward test cases

#### Production-Grade Review
- [ ] All paths have error handling
- [ ] Input validation at boundaries (controller layer)
- [ ] File size limits enforced
- [ ] Sensitive operations logged
- [ ] Configuration externalized
- [ ] No hardcoded values
- [ ] Thread-safe where needed (LogService uses streams safely)
- [ ] Graceful degradation on failures
- [ ] Performance acceptable for expected load

### Common Issues to Look For

**Architecture**
- ❌ Service layer mixing concerns (parsing + caching + validation all in one class)
- ✅ Focused services with clear responsibilities

**SOLID Violations**
- ❌ Adding new parser requires modifying existing parser classes (violates OCP)
- ✅ New parsers added by implementing interface, no existing code changed

**Clean Code**
- ❌ `processData()`, `doStuff()`, `temp`, `x` as variable names
- ✅ `parseServerAccessLog()`, `countByLevel()`, `errorRateOverTime()`

**DRY**
- ❌ Same regex pattern in multiple parser classes
- ✅ Pattern defined once, reused across parsers

**KISS**
- ❌ Complex custom caching layer implemented from scratch
- ✅ Using Spring's built-in Redis caching

**Production Issues**
- ❌ No file size validation before loading entire file into memory
- ✅ File size checked, exception thrown if >50MB limit

### How to Run Reviews Locally

#### Review Current Changes
```bash
# High-effort review with reasoning
/code-review high

# Medium effort, faster feedback
/code-review medium

# Ultra review (multi-agent, comprehensive)
/code-review ultra

# Save as inline comments (requires GitHub)
/code-review high --comment
```

#### Review Specific File or Module
```bash
# Review a specific package for design issues
/code-review high src/main/java/dev/dinesh/argus/filter

# Review controller layer
/code-review high src/main/java/dev/dinesh/argus/controller

# Review parsers and their SOLID compliance
/code-review high src/main/java/dev/dinesh/argus/parser
```

#### Full Codebase Architecture Review
```bash
# Comprehensive architecture audit
/code-review ultra src/main/java
```

### When to Escalate Issues

**Critical (Fix Before Merge)**
- SOLID principles violated (OCP, SRP)
- Security vulnerabilities (input validation, injection attacks)
- Memory leaks or performance bottlenecks
- Missing error handling in critical paths
- Production standards not met

**Important (Should Fix)**
- Clean Code violations that impact maintainability
- DRY violations causing maintenance burden
- Missing test coverage for critical logic
- Unclear naming or confusing logic

**Nice to Have (Consider)**
- Performance optimizations not critical for current load
- Refactoring for better readability when code is working
- Style improvements beyond Google format

### Integration with Development Workflow

1. **Before Creating PR**: Run `/code-review high` locally
2. **In PR**: Use `/code-review high --comment` to highlight issues for team review
3. **Architecture Reviews**: Quarterly `/code-review ultra` on full codebase
4. **Onboarding**: New developers run `/code-review medium` to understand patterns
5. **Refactor Planning**: Use review findings to prioritize technical debt

### Related Commands

- `/code-review`: Quick-access help for reviews
- `/simplify`: Applies suggested simplifications to code
- `mvn fmt:format -P local`: Auto-format with Google style
- `/verify`: Run app and verify changes work as expected
