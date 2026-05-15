# Atlas Migrate · performance

This document covers Phase 2F — load-test harness, baseline-capture
runbook, audit findings, and the preventive fixes that landed.

## What's shipped (Phase 2F)

| Layer | Where |
|---|---|
| **k6 load-test harness** | `loadtest/scenarios/{dashboard,project-detail,audit-browser}.js` |
| **Auth helper** (full PKCE round-trip per VU) | `loadtest/helpers/auth.js` |
| **Runner** (PowerShell, k6-in-docker) | `loadtest/run.ps1` |
| **Preventive index migration** | `services/prov-service/src/main/resources/db/migration/V3__index_prov_query_paths.sql` |

## Running the harness

The runner spins up a containerised k6 (`grafana/k6:0.54.0`) — no host
install needed. The scripts target the local docker-compose stack by
default and reach the host network via `host.docker.internal`.

Prerequisites:

```powershell
.\up.ps1                                          # bring the stack up
# (optional: seed sample provenance data — see "Seeding the audit table" below)
```

Run a single scenario:

```powershell
.\loadtest\run.ps1 -Scenario dashboard
.\loadtest\run.ps1 -Scenario project-detail   -ProjectId <uuid>
.\loadtest\run.ps1 -Scenario audit-browser    -ProjectId <uuid>
```

Run all three back-to-back:

```powershell
.\loadtest\run.ps1 -Scenario all
```

## Scenarios

| Scenario | Hot endpoints exercised | Shape |
|---|---|---|
| `dashboard`        | `GET /api/v1/me`, `GET /workspaces`, `GET /workspaces/{id}/projects` | 1 → 10 VUs, 30 s ramp + 60 s soak |
| `project-detail`   | `GET /api/v1/projects/{id}`, `GET /projects/{id}/operations`         | 1 → 8 VUs, 20 s ramp + 60 s soak |
| `audit-browser`    | `GET /projects/{id}/provenance?limit=500`, `…/provenance/aggregate` | 1 → 5 VUs, 15 s ramp + 45 s soak |

Each scenario records per-endpoint `Trend` metrics and a `Counter` of
5xxs. The summary k6 prints at the end carries p50/p95/p99 latencies
and the threshold pass/fail line.

## Per-scenario thresholds (initial)

These are starting-point SLOs. Tune after the first baseline run.

```
atlas_dashboard_me_ms             p95 <  200ms
atlas_dashboard_workspaces_ms     p95 <  250ms
atlas_dashboard_projects_ms       p95 <  400ms

atlas_project_detail_ms           p95 <  300ms
atlas_project_ops_ms              p95 <  600ms

atlas_audit_provenance_ms         p95 <  800ms
atlas_audit_aggregate_ms          p95 <  400ms

http_req_failed                   rate <  0.01
*_5xx_total                       count <  5
```

## Capturing a baseline (runbook)

```powershell
# 1. Bring the stack up (idempotent).
.\up.ps1

# 2. Wait for the SPA to be reachable. Up to ~90 seconds on first boot.
$ok = $false
for ($i=0; $i -lt 30; $i++) {
  try {
    if ((Invoke-WebRequest http://localhost:3000 -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) {
      $ok = $true; break
    }
  } catch { Start-Sleep 5 }
}
if (-not $ok) { Write-Error "frontend not reachable"; exit 1 }

# 3. (Optional) seed enough provenance data for the audit-browser
#    scenario to be representative. See "Seeding the audit table" below.

# 4. Run all scenarios.
.\loadtest\run.ps1 -Scenario all  2>&1 | Tee-Object -FilePath loadtest\baseline-$(Get-Date -Format yyyyMMdd-HHmm).txt
```

The k6 summary lands in the captured file. Compare per-endpoint p95
against the thresholds above; the diff is the optimisation target.

### Seeding the audit table

The audit-browser scenario is most representative on a project with at
least a few thousand provenance entries. To seed:

```powershell
$pid = "<your-project-uuid>"
1..2000 | ForEach-Object {
  Invoke-RestMethod -Method Post `
    -Uri "http://localhost:8091/internal/provenance" `
    -ContentType "application/json" `
    -Body (@{
      projectId = $pid
      actorKind = (Get-Random -InputObject @("agent","human","system"))
      actorId   = (Get-Random -InputObject @("alice@envestnet.local","bob@envestnet.local","archaeology-agent"))
      action    = (Get-Random -InputObject @("agent_narrative","decision_resolved","stage_b_finalized"))
      latencyMs = (Get-Random -Minimum 50 -Maximum 4000)
    } | ConvertTo-Json)
}
```

(prov-service exposes the emit endpoint internally — only do this in
local-Docker, not against a customer engagement DB.)

## Audit findings

### prov-service · `query()`

The audit browser's filter UI hits `ProvService.query()` on every
filter change. The original schema (V1) covered `(project_id, ts
DESC)` and `(action)`, leaving three common access paths uncovered:

| Filter pattern                              | V1 plan                | V3 plan                                      |
|---------------------------------------------|------------------------|----------------------------------------------|
| `project_id = ? AND action = ?`             | seq scan from `(action)` index → re-filter project_id | direct index scan via new `(project_id, action)` |
| `project_id = ? AND actor_id = ?`           | seq scan from `(project_id, ts)` index | direct index scan via new `(project_id, actor_id)` |
| `q=...` → ILIKE on action / actor_id        | row-by-row substring match | GIN-trigram lookup, then row fetch |
| `q=...` → ILIKE on `cast(output as text)`   | sequential scan of JSONB blobs | **unchanged — still a hot spot** |

**Migration shipped**: `V3__index_prov_query_paths.sql`. Adds:

- `idx_prov_project_action  (project_id, action)`
- `idx_prov_project_actor   (project_id, actor_id)`
- `pg_trgm` extension + `idx_prov_action_trgm` and `idx_prov_actor_trgm`
  GIN-trigram indexes for the substring search

**Known unfixed**: ILIKE on the `output` JSONB column (the third disjunct
in the `q=` clause) still does a sequential scan. The right fix is
full-text search via a stored `tsvector` generated column. Left for a
follow-up because the ILIKE hits sequentially-scan rows already
narrowed by `project_id`, so the cost scales with per-project entries
(typically thousands), not the global table size.

### prj-service · pagination

The `GET /workspaces/{id}/projects` and `GET /projects/{id}/operations`
endpoints don't paginate. For demo-scale data (10s of projects, 100s of
operations) this is fine; for customer-scale audits (1000s of operations
per project) the scenarios will surface this as a hot spot. Add a
`limit`/`offset` (or cursor-based) once the baseline confirms it.

### gateway · JWKS cache

The OAuth2 resource server caches JWKS for the issuer URL. With the
default Spring auto-config the cache TTL is 5 minutes — sufficient for
demo and production. Verified by inspection; no change needed.

## Phased roadmap

- **2F.1 (shipped)** — k6 harness, three scenarios, runbook
- **2F.2 (deferred)** — capture concrete baseline numbers; the harness
  exists, the stack just wasn't running when 2F was authored. Run
  `.\loadtest\run.ps1 -Scenario all` against your local stack and
  paste the summary into the **Baseline** section below.
- **2F.3 (shipped)** — preventive index migration for prov-service's
  uncovered query paths
- **2F.4 (shipped)** — this document

### Open follow-ups

- **Full-text search on `prov.entry.output`** to fix the JSONB ILIKE
  hot spot when the q= filter searches output text.
- **Pagination** on `prj-service` list endpoints once baseline
  confirms they're a problem at scale.
- **CI integration** — wire `loadtest:smoke` (a 30-second 5-VU run) as
  a non-blocking GitHub Actions job that posts the summary as a
  comment on PRs.

## Baseline

> **Pending capture.** Run the runbook above and paste the k6 summary
> here. Format suggestion:
>
> ```
> Date: 2026-05-XX
> Stack: docker-compose default, single-node Docker Desktop on Windows
> Seeded provenance entries: <count>
>
> Scenario       | p50 ms | p95 ms | p99 ms | 5xx | http_req_failed
> dashboard      |    XX  |    XX  |    XX  |  0  |    X.XX %
> project-detail |    XX  |    XX  |    XX  |  0  |    X.XX %
> audit-browser  |    XX  |    XX  |    XX  |  0  |    X.XX %
> ```
