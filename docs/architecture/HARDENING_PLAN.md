# Hardening Plan: v1 path restriction and API-key tenancy

> Between P1 and P2 of [`SCALING_DESIGN.md`](SCALING_DESIGN.md) §12. Closes the two security gaps left after P1 (see CLAUDE.md "Current State & Known Gaps"): v1 reads any file on the host, and tenants come from a header the caller chooses. It pulls the `TenantResolver` piece forward from P3; quotas and per-tenant indices stay in P3.

## 1. Decisions (STAR, per guardrail 10)

### H1. v1 reads only files under `argus.ingest.allowed-roots`
- **Situation:** every v1 endpoint takes `file` and reads any path the process can read. Since P1b the aggregation endpoints also have no size cap, so this is an arbitrary file read and an unbounded scan. v2 already checks `allowed-roots` (before existence, and again after resolving symlinks).
- **Task:** close the hole without forking the path rules, and give v1 clients that pass other paths a way to migrate.
- **Action:** options were (a) a separate v1 allow-list, (b) **the same roots and the same check as v2**, (c) deprecate v1 file paths entirely. Chosen (b): `source.AllowedPaths` holds v2's check, and `IngestJobService` and `LogService` both use it. `log.enforce-allowed-roots` (default **true**) can be set to `false` for one release: then v1 logs a warning per request outside the roots instead of refusing it.
- **Result:** one rule for both APIs. Outside the roots → 400 (as v2). A missing file inside them → 404. The path check runs before the existence check, so a 404 never reveals that a path outside the roots exists.

### H2. Tenants come from API keys
- **Situation:** `X-Argus-Tenant` is caller-chosen, so any caller can probe or pre-open another tenant's suppression windows. Ingest writes every tenant's data under `tenant=default`, with checkpoints and job ids shared across tenants (guardrail 4).
- **Task:** resolve the tenant from a credential, and carry it everywhere data is keyed: suppression keys, checkpoints, output paths, `eventId`, job lookup.
- **Action:** options were (a) OAuth2/JWT via Spring Security, (b) **static API keys** mapped to tenants in configuration, (c) mTLS. Chosen (b) for now: there is no identity provider in the deployment yet, and a resolver port lets (a) replace it later without touching the controllers.
  - `tenant.TenantResolver` port, with `ApiKeyTenantResolver`: `X-Argus-Api-Key: <key>` or `Authorization: Bearer <key>`. The configuration stores only the **SHA-256** of each key (`argus.tenancy.api-keys[i].tenant`, `.key-sha256`), so config files and dumps hold no usable secret. A missing or unknown key → **401** with `WWW-Authenticate: ApiKey`.
  - `HeaderTenantResolver` (`argus.tenancy.mode=header`): today's `X-Argus-Tenant` behaviour, for local development only. It logs a warning at startup.
  - Controllers take the tenant as a `@CurrentTenant String tenantId` argument, resolved per request by a `HandlerMethodArgumentResolver`.
  - The default mode is `api-key`. With no keys configured the app still starts, logs a warning, and answers 401 on the v2 endpoints. That is safe by default, and doesn't break a local `spring-boot:run`.
  - Ingest is tenant-scoped end to end:
    - checkpoints at `checkpoint-dir/tenant=T/<source>.json`;
    - output at `root/tenant=T/...`;
    - `eventId = UUIDv3(T, source, generation, offset)`;
    - one active job per `(T, source)`;
    - `GET` / `DELETE /jobs/{id}` return 404 for another tenant's job.
- **Result:** guardrail 4 holds for v2. The tenant is never read from the body or from a caller-chosen header (except in the explicit dev mode). v1 has no tenancy (it doesn't persist anything) and stays as it is, apart from H1.

## 2. Compatibility
- **v1 callers** passing paths outside the roots now get 400. Set `log.enforce-allowed-roots=false` for a migration window.
- **v1 errors:** v1 answered 500 for a missing file and for a file over the size cap; it now answers 404 and 400 (P1b).
- **v2 callers** must send an API key. To keep the old header behaviour locally, set `argus.tenancy.mode=header`.
- **Existing checkpoints** at `checkpoint-dir/<source>.json` (pre-tenancy) are not migrated: they're dev data on a git-ignored volume, and a fresh run re-ingests from offset 0 into `tenant=<T>/`. Output paths for tenant `default` are unchanged.

## 3. Tests
| Test | Covers |
|---|---|
| `AllowedPathsTest` | Inside / outside / `..` / symlink escaping a root / missing file inside vs outside |
| `LogServiceTest`, `LogControllerTest` | v1 refuses outside paths (400), warn-only mode reads them |
| `ApiKeyTenantResolverTest` | Both headers, unknown and missing key, hashes only in config, invalid tenant ids rejected at startup |
| `SuppressionControllerTest` | 401 without a key; a key's tenant is used and a header can't override it |
| `IngestControllerTest`, `IngestJobServiceTest` | 401 without a key; two tenants ingest the same `sourceId` independently; another tenant's job is a 404 |
| `FileCheckpointStoreTest`, `LocalVolumeSinkTest`, `IngestPipelineTest` | Tenant directories; eventIds differ by tenant |

## 4. Implementation order (one commit per step)
1. This plan.
2. `AllowedPaths` shared by v1 and v2; `LogProperties`; v1 enforcement.
3. `tenant` package: resolver port, API-key and header resolvers, `@CurrentTenant`, 401 handling; suppression uses it.
4. Tenant through ingest: checkpoints, sink, pipeline, job service, controller.
5. Docs (CLAUDE.md, SCALING_DESIGN.md) and results (§5 here).

## 5. Results
- **Tests:** 207 pass (`mvn -P ci test`), up from 193 at the end of P1b.
- **T1:** the 1GB ingest load test runs at 379,337 lines/s with a 201MB peak heap.
- **Real app** (one key for tenant `acme`):
  - Suppression: no key → 401; `X-Argus-Tenant: acme` alone → 401; `Authorization: Bearer acme-key` → ALLOW.
  - Ingest: no key → 401. With the key, `app.json` was ingested as tenant `acme`: 25,000 lines, checkpoint at `data/checkpoints/tenant=acme/app.json`, output under `data/out/tenant=acme/…`, and every envelope carries `"tenantId":"acme"`.
  - v1: `/parse?file=/etc/hosts` and `/aggregate` on `/etc/hosts` → 400 (outside the roots); a file under the roots → 200; a missing file under the roots → 404.
- **Deviations:** none from §1. The tenant-id rule also forbids a leading `.` (a tenant becomes a directory name), which `X-Argus-Tenant` used to allow.
