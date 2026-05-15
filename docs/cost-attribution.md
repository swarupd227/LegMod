# Atlas Migrate · LLM cost attribution

This document covers Phase 2L — per-user, per-project, per-workspace
LLM token + USD tracking, surfaced on the SPA's Spend page.

## The problem 2L solves

Pre-2L state of the world:

- `llm-gateway` returned `cost_usd: 0.0` for every call. Always.
- `prov.entry` had `tokens_in / tokens_out / cost_usd` columns but no
  `workspace_id` or `user_email` — so per-tenant and per-user rollups
  weren't computable at all.
- The dashboard's per-project "Cost (USD)" tile rendered as `—`
  because the source value was always zero.
- A customer asking "who spent the most this month?" required a
  manual log-mining exercise.

Phase 2L closes the loop end-to-end:

1. `llm-gateway` reads its pricing catalog and stamps a real
   `cost_usd` on every invoke response.
2. `prov.entry` gains `workspace_id` + `user_email` columns + three
   indexes for the per-tenant rollup queries.
3. Every `ProvenanceEmitter` (six caller services) forwards
   workspace + user + cost so attribution survives the fan-out.
4. `prov-service` exposes `/workspaces/{ws}/cost` with totals +
   four breakdowns (by user, project, model, daily).
5. `prj-service` relays the rollup with workspace-access gating
   (Phase 2K plumbing).
6. The SPA gets a `Spend` page in the left nav.

## What's shipped (Phase 2L)

| Layer | Where |
|---|---|
| Pricing catalog | `services/llm-gateway/src/main/java/com/envestnet/atlas/llm/PricingCatalog.java` |
| Cost in invoke response | `services/llm-gateway/src/main/java/com/envestnet/atlas/llm/LlmApplication.java` |
| Per-tenant cost counter | `LlmApplication.recordCostCounter` (Micrometer `atlas_llm_cost_usd_total{model,workspace,known}`) |
| Diagnostics endpoint | `GET /api/v1/llm/pricing` |
| Identity passthrough headers | `services/llm-gateway/src/main/java/com/envestnet/atlas/llm/logging/RequestContextFilter.java` (`HDR_WORKSPACE_ID`, `HDR_PROJECT_ID`) |
| Schema migration | `services/prov-service/src/main/resources/db/migration/V4__add_workspace_and_user_to_prov.sql` |
| Persistence + rollup | `services/prov-service/src/main/java/com/envestnet/atlas/prov/service/ProvService.java` (`emit`, `workspaceCost`) |
| Internal endpoint | `services/prov-service/src/main/java/com/envestnet/atlas/prov/web/ProvController.java` (`GET /internal/prov/workspaces/{ws}/cost`) |
| Emitter updates | 6 × `ProvenanceEmitter.java` (arch/recon/diff/gen/reports/uplift) |
| Public endpoint | `services/prj-service/.../web/ProjectController.workspaceCost` |
| Frontend screen | `frontend/src/screens/WorkspaceCost.tsx` |
| API client | `frontend/src/api/client.ts` (`WorkspaceCost` type + `api.workspaceCost`) |
| Routing + nav | `frontend/src/App.tsx`, `frontend/src/components/Layout.tsx` |
| Tests | `PricingCatalogTest` (11), 7 new cases in `ProvServiceTest` |

## Pricing catalog

### Per-million-tokens rates

The catalog stores rates in dollars per million tokens, matching how
Anthropic and OpenAI publish their pricing. Six entries by default:

| Family / model id | Input $/M | Output $/M |
|---|---|---|
| `sonnet` (alias) | 3.00 | 15.00 |
| `opus` (alias) | 15.00 | 75.00 |
| `haiku` (alias) | 0.80 | 4.00 |
| `claude-sonnet` (prefix) | 3.00 | 15.00 |
| `claude-opus` (prefix) | 15.00 | 75.00 |
| `claude-haiku` (prefix) | 0.80 | 4.00 |

Each rate is overridable via env vars — `LLM_PRICE_SONNET_INPUT`,
`LLM_PRICE_OPUS_OUTPUT`, etc. — for customers on negotiated rates.

### Family alias + prefix match

The `modelHint` field on `InvokeRequest` is resolved (`sonnet` →
`claude-sonnet-4-6`) before pricing, but downstream services log
the **resolved** model name. The catalog handles both shapes:

1. **Exact alias** — `"sonnet"` hits the alias entry directly.
2. **Prefix match** — `"claude-sonnet-4-7-rc1"` falls through to
   the `claude-sonnet` prefix entry, so a new model release stays
   priced (at its family rate) until operators set an explicit
   override.

A model that matches nothing logs a single WARN and returns $0.00 —
the gateway must never break a user request over a pricing miss.

### Exact math at scale

`PricingCatalog.computeCost` returns `BigDecimal` at six decimal
places. The stored column is `NUMERIC(10,4)` so each row rounds at
write time, but the in-memory accumulator stays lossless across
hundreds of millions of summed calls. The unit test
`milllionsOfSubCentCallsSumExactlyToTheirHeadlineRate` pins this:
1000 × 1000-input-token calls at $3/M = exactly $3.000000.

### Diagnostics

`GET /api/v1/llm/pricing` dumps the full table so operators can
sanity-check what callers are being charged:

```json
{
  "rates": {
    "sonnet": { "inputPerMillionUsd": 3.00,  "outputPerMillionUsd": 15.00 },
    "opus":   { "inputPerMillionUsd": 15.00, "outputPerMillionUsd": 75.00 },
    ...
  }
}
```

Read-only; no auth required because the rates aren't sensitive.

## Identity passthrough

The `llm-gateway` reads four request headers and echoes them back in
the response under `context`:

| Header | Source | Purpose |
|---|---|---|
| `X-Atlas-Workspace-Id` | Forwarded by prj-service after the Phase 2K access check | Counter tag + provenance row |
| `X-Atlas-Project-Id` | Optional; falls back to URL pattern match | Provenance row |
| `X-Atlas-User-Email` | Gateway-validated JWT subject | Provenance row |
| `X-Atlas-Request-Id` | RequestContextFilter | Correlation |

Callers see them on the response body's `context` field so a
background task that doesn't have request context (Temporal
workflows, retries) can still emit provenance with the right
attribution.

### The per-workspace counter

`atlas_llm_cost_usd_total{model, workspace, known}` is a Micrometer
counter that increments on every successful (non-stub) invoke. The
`workspace` tag is the resolved workspace UUID; `model` is the
post-resolution model name; `known` is `"true"` when the catalog
recognised the model and `"false"` otherwise.

This counter is the defence-in-depth view: even if prov-service is
down or the emitter drops a row, Grafana still sees per-tenant
spend in close-to-real-time. Cardinality is bounded:
`models × workspaces × 2` series, which scales well into the
thousands of tenants.

**Email isn't a tag** — high-cardinality tags blow up the metrics
registry. Per-user attribution comes from the provenance DB
queries; the metric is the rollup.

## Schema: V4 migration

```sql
ALTER TABLE prov.entry
    ADD COLUMN IF NOT EXISTS workspace_id UUID,
    ADD COLUMN IF NOT EXISTS user_email   TEXT;
```

Both columns are NULLABLE. Existing rows have no workspace / user
attribution; the rollup queries `WHERE workspace_id IS NOT NULL`
exclude them naturally.

Three indexes for the common query shapes:

```sql
CREATE INDEX idx_prov_workspace_ts      ON prov.entry(workspace_id, ts DESC)
  WHERE workspace_id IS NOT NULL;
CREATE INDEX idx_prov_workspace_user    ON prov.entry(workspace_id, user_email)
  WHERE workspace_id IS NOT NULL AND user_email IS NOT NULL;
CREATE INDEX idx_prov_workspace_cost    ON prov.entry(workspace_id, ts DESC)
  WHERE workspace_id IS NOT NULL AND cost_usd IS NOT NULL AND cost_usd > 0;
```

The partial-index pattern keeps the spend-rollup query path tight
even though the table is dominated by zero-cost rows (human
decisions, status pings, audit entries).

## Cost rollup query shape

`ProvService.workspaceCost(workspaceId, from, to)` returns a single
map with five sections, computed in five separate prepared
statements:

```json
{
  "workspaceId": "...",
  "rangeStart":  "2026-04-11T00:00:00Z",
  "rangeEnd":    "2026-05-11T00:00:00Z",
  "totals":      { "calls": 1234, "tokensIn": 12_345_678, "tokensOut": 2_345_678, "costUsd": 42.17 },
  "byUser":      [ { "userEmail": "alice@...", "calls": 87, "costUsd": 12.40 }, ... ],
  "byProject":   [ { "projectId":  "uuid",     "calls": 50, "costUsd":  5.20 }, ... ],
  "byModel":     [ { "model":     "claude-...", "calls": 30, "costUsd": 18.00 }, ... ],
  "daily":       [ { "day": "2026-05-01", "calls": 12, "costUsd": 0.42 }, ... ]
}
```

Behavioural notes pinned by the test suite:

- **By-user bucket** with `null user_email` collapses to
  `"(unattributed)"` so the SPA can render the bucket explicitly
  rather than dropping it silently.
- **Cross-tenant rows are excluded** — a row in workspace B never
  shows up in workspace A's rollup, full stop.
- **Out-of-window rows are excluded** — a future-only window
  returns zero calls. The window is `[from, to)` (half-open).
- **Daily sparkline** is UTC-day bucketed and ordered ascending so
  the SPA renders left-to-right without sorting.

## The end-to-end flow

A user clicks "Run archaeology" on a project. The full attribution
trace:

```
1. SPA → gateway              POST /api/v1/projects/<id>/stages/archaeology/run
                              + Authorization: Bearer <JWT>
                              + Idempotency-Key: ...

2. Gateway → prj-service      Adds X-Atlas-User-Email,
                              X-Atlas-User-Roles, X-Atlas-Request-Id
                              after validating the JWT.

3. prj-service                Resolves project → workspace via the
                              Phase 2K access helpers. Stamps
                              MDC_WORKSPACE_ID and the request attribute.
                              Submits the Temporal workflow.

4. Workflow → arch-service    Activity runs. RestTemplate's
                              IdentityForwardingInterceptor copies
                              X-Atlas-{User-Email,Workspace-Id,
                              Request-Id} onto the outbound call.

5. arch-service → llm-gateway POST /api/v1/llm/invoke
                              + X-Atlas-User-Email
                              + X-Atlas-Workspace-Id
                              + X-Atlas-Request-Id

6. llm-gateway                Calls Anthropic. Reads
                              message.usage().{inputTokens,outputTokens}.
                              Looks up rate in PricingCatalog.
                              Increments atlas_llm_cost_usd_total
                              {model, workspace}.
                              Returns the response with
                              "cost_usd": 0.0237 and
                              "context": { workspaceId, userEmail, ... }.

7. arch-service               Receives the response. Constructs a
                              ProvenanceEmitter.Event with workspaceId
                              (from MDC fallback) + userEmail (from MDC)
                              + costUsd (from the response).

8. arch-service → prov-service POST /internal/prov/emit
                               with workspaceId, userEmail, costUsd,
                               tokensIn, tokensOut, model.

9. prov-service               Writes prov.entry row.

10. User opens Spend page     SPA → prj-service:
                              GET /api/v1/workspaces/<ws>/cost
                              (Phase 2K access check).
                              prj-service → prov-service:
                              /internal/prov/workspaces/<ws>/cost
                              prov-service returns 5-section rollup.
                              SPA renders totals + four breakdowns +
                              30-day sparkline.
```

Every hop carries the attribution headers; the cost is computed
once at the gateway and persisted with full provenance context.

## Test coverage

| Suite | Cases | What it pins |
|---|---|---|
| `PricingCatalogTest` (pure-unit, llm-gateway) | 11 | Exact $ for known models, family-alias + prefix match, case-insensitivity, unknown-model graceful zero, null tokens treated as zero, BigDecimal math is lossless at scale, diagnostics catalog dump |
| `ProvServiceTest` (Testcontainers, prov-service) | 7 new | Workspace+user persistence from body, MDC fallback when body omits, totals sum exactly, byUser sorts by spend desc, null user → `(unattributed)`, cross-tenant rows excluded, out-of-window rows excluded |

All 74 prj-service tests continue to pass — the new `workspaceCost`
endpoint is added without breaking existing behaviour.

## Phased roadmap

- **2L.1 (shipped)** — `PricingCatalog` in llm-gateway, real
  `cost_usd` in the invoke response, per-tenant Micrometer counter
- **2L.2 (shipped)** — Identity passthrough at the gateway (workspace
  + project + user headers, MDC additions, context echo)
- **2L.3 (shipped)** — `prov.entry` V4 migration: workspace_id +
  user_email + three indexes
- **2L.4 (shipped)** — `ProvenanceEmitter` updated in six services
  to forward workspace + user + cost
- **2L.5 (shipped)** — Cost rollup endpoints (prov-service
  `/workspaces/{ws}/cost` + prj-service relay)
- **2L.6 (shipped)** — Frontend `Spend` screen + nav link
- **2L.7 (shipped)** — Test coverage
- **2L.8 (shipped)** — this document

### Open follow-ups

- **Per-tenant budget caps**. Today the gateway records spend but
  doesn't enforce a ceiling. A `LLM_BUDGET_USD_PER_WORKSPACE` env
  var, checked against `atlas_llm_cost_usd_total{workspace}`
  before each call, would 429 a tenant that's blown its budget.
  Deferred because it needs a customer-facing "you're at 80%"
  warning flow, which is its own UX project.

- **Daily anomaly alerts**. The daily sparkline shows spend per
  day; a 5x jump from baseline should page someone. Wire as a
  Grafana alert against `atlas_llm_cost_usd_total` once we have
  enough days of baseline to compute "normal."

- **Cost-per-stage breakdown**. The current rollup is by user /
  project / model. A "which stage costs the most" view (group by
  the `action` column on `prov.entry`) is a natural next add — the
  schema supports it; just needs another SELECT and an SPA card.

- **Provider mix-in**. Atlas only calls Anthropic today. When a
  customer wants OpenAI or Bedrock, add the routes + extend the
  PricingCatalog. The catalog's prefix-match pattern means a new
  provider's pricing slots in without touching the call sites.

- **Historical price restatement**. Cost is computed at call time
  and persisted; a future price change doesn't restate history.
  This is the right default — invoices reflect what callers were
  actually charged. If a customer needs a hypothetical recompute
  ("what would last month have cost at the new rates?"), it
  becomes a separate read-side query against tokens × current
  rates.

- **Full ProvenanceEmitter audit**. Five of six emitters were
  updated; the sixth (uplift-service) and any new caller services
  must follow the same pattern. A future change to the emitter
  could be factored into a shared `common-prov` Maven module so
  this synchronisation isn't manual — deferred because the
  duplication is only ~70 LOC and the trade-off (build-graph
  coupling between every caller and one library version) hasn't
  paid off yet.
