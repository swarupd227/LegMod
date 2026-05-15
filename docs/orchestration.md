# Atlas Migrate · orchestration

This document covers Phase 2I — Temporal-backed durable orchestration
for long-running stage fan-outs. The original Phase 1 implementation
ran every stage trigger synchronously over HTTP, which had three
production-blocking limitations the synchronous code had:

1. **Long-running.** Archaeology on a non-trivial source tree
   routinely takes 30–120 s. Synchronous HTTP times out around the
   same range — the user sees a generic failure and has no way to
   know if the work is still happening server-side.
2. **Partial failure.** If arch-service succeeded but the follow-up
   gate-advance write to Postgres failed, the project ended up in an
   inconsistent state. No automatic retry.
3. **Crash safety.** Restart prj-service mid-archaeology, the in-flight
   HTTP call is lost. The user has no way to recover other than
   re-submitting and hoping the upstream is idempotent.

Temporal solves all three: the workflow's execution history is
checkpointed in the Temporal cluster, activities are independently
retryable per the workflow's policy, and crash recovery is built-in.

## What's shipped (Phase 2I)

| Layer | Where |
|---|---|
| Temporal SDK + Spring Boot starter | `services/prj-service/pom.xml` |
| Worker / connection config | `services/prj-service/src/main/resources/application.yml` |
| Canonical workflow | `services/prj-service/src/main/java/com/envestnet/atlas/prj/orchestration/ArchaeologyWorkflow.java` |
| Workflow implementation | `…/ArchaeologyWorkflowImpl.java` (retry policies, activity stub config) |
| Activity contract | `…/ArchaeologyActivities.java` |
| Activity worker | `…/ArchaeologyActivitiesImpl.java` (the HTTP + JDBC work) |
| Controller refactor | `ProjectController.runArchaeology` submits the workflow + returns 202 |
| Workflow-status probe | `GET /api/v1/projects/{id}/stages/archaeology/workflow/{workflowId}` |
| TestWorkflowEnvironment coverage | `…/test/.../orchestration/ArchaeologyWorkflowTest.java` (4 cases) |
| Existing Temporal in compose | `docker-compose.yml` — temporal:7233, temporal-ui on `${PORT_TEMPORAL_UI:-8088}` |

## Architecture

```
   SPA              prj-service controller          Temporal cluster
    │  POST /run         │                                  │
    │ ──────────────────►│ submit workflow ─────────────────►│
    │                     │   (workflowId)                    │
    │ ◄───── 202 ────────│                                   │
    │  { runId, status:  │                                   │
    │    running }       │                                   │
    │                     │                                   │  schedule
    │                     │                                   │  archaeology
    │                     │                          ┌──────────┐ activity
    │                     │                          │ prj-svc  │
    │                     │                          │ worker   │
    │                     │                          │ (this pod)│
    │                     │                          └─────┬────┘
    │                     │                                │ POST /internal/archaeology/run
    │                     │                                ▼
    │                     │                          arch-service (60s)
    │                     │                          │
    │                     │                          │ run-id back
    │                     │                          ▼
    │                     │                  Temporal records activity
    │                     │                  complete, schedules next
    │                     │                  activity
    │                     │                          │
    │                     │                          ▼
    │                     │                  advanceToStageB activity:
    │                     │                  prj-svc worker writes
    │                     │                  Gate A → passed, B → in_progress
    │                     │                          │
    │                     │                  workflow returns
    │                     │                  ArchaeologyResult { status: completed }
    │  GET status         │                          │
    │ ──────────────────► │ getWorkflowStatus ───────► describeWorkflowExecution
    │ ◄── { completed }── │ ◄────────────────────────│
```

Key points:

- The controller doesn't wait for the workflow to finish. It returns
  202 with the workflow id as soon as Temporal accepts the submission.
- The actor email (gateway-asserted via Phase 2D) is propagated
  through the workflow input and onto the gate row written by
  `advanceToStageB`, so the audit trail and provenance entries stay
  honest.
- Activities live in the same prj-service process today. Migrating
  them out into per-stage worker pods is a values-only change in the
  Helm chart (set replicas on the worker deployment, point it at the
  same task queue).

## Configuration

`application.yml` § temporal:

```yaml
spring:
  temporal:
    connection:
      target: ${TEMPORAL_TARGET:temporal:7233}
      namespace: ${TEMPORAL_NAMESPACE:default}
    workers:
      - task-queue: atlas-prj
        capacity:
          max-concurrent-workflow-task-executors: 10
          max-concurrent-activity-executors: 20
```

The Spring Boot starter scans `@WorkflowImpl` and `@ActivityImpl`
beans and registers them on the worker automatically. Adding a new
workflow / activity is:

1. Define the `@WorkflowInterface` and the `@ActivityInterface`.
2. Mark the impls with `@WorkflowImpl(taskQueues = …)` and
   `@ActivityImpl(taskQueues = …)`.
3. Done — the worker picks them up on next app restart.

## Retry policies

The canonical pattern lives in `ArchaeologyWorkflowImpl`. Two stubs
with different policies:

| Activity stub | Start-to-close | Initial backoff | Max attempts | Use for |
|---|---|---|---|---|
| `longRunningActivities` | 10 min | 5 s | 3 | HTTP fan-outs (arch, gen, diff) that can legitimately take minutes |
| `quickActivities` | 30 s | 200 ms | 5 | DB writes (gate advance, provenance), should be fast and frequent if they retry |

Inside the workflow `run(...)`, an `ActivityFailure` from any stub is
caught and converted to a structured `ArchaeologyResult` with
`status: "failed"` + `errorText`. Workflows don't auto-retry; the SPA
can re-submit a workflow (with a fresh id) if it wants.

## Activity idempotency

Activities are retried on transient failure. The implementations MUST
be idempotent. The two activities in `ArchaeologyActivitiesImpl` are:

- `runArchaeology(projectId, sourcePath)` — calls
  `arch-service`'s `/internal/archaeology/run`. arch-service is keyed
  by project id; running it twice produces the same artifact set.
  Safe.
- `advanceToStageB(projectId, actorEmail)` — upserts the project's
  gate rows by `(projectId, label)`. Re-running it on a project
  already at Stage B is a no-op. Safe.

When adding new activities, double-check the upstream's idempotency
shape before wiring it in. The cheapest pattern is a server-side
unique key on the destructive operation; second-cheapest is the
`Idempotency-Key` middleware from Phase 2G if the upstream is also
an Atlas service.

## Testing

`ArchaeologyWorkflowTest` uses Temporal's `TestWorkflowEnvironment`
to drive the workflow in-memory. No Temporal cluster required. Four
cases:

| Case | What it pins |
|---|---|
| Happy path | Both activities invoked, in order, with the right inputs; `ArchaeologyResult` returns `completed` + the upstream's runId |
| Retry on transient failure | First call to `runArchaeology` throws; activity retries per policy; workflow returns `completed` after the retry succeeds |
| Exhausted retries | All `runArchaeology` attempts fail; workflow returns `failed`; `advanceToStageB` is NEVER invoked (gate stays at A) |
| Actor propagation | The gateway-asserted `actorEmail` from `ArchaeologyInput` flows through to the activity unchanged |

The `ProjectControllerTest` retains a single async-contract test
(`runArchaeologySubmitsWorkflowAndReturns202`) that asserts the
controller hands off to Temporal cleanly and doesn't touch gate /
project tables.

## Operations

### Temporal UI

The docker-compose stack ships `temporalio/ui:2.31.2` on
`http://localhost:${PORT_TEMPORAL_UI:-8088}`. Useful for:

- Browsing workflow history (every signal, every activity, every retry)
- Drilling into a failing workflow's stack trace
- Manually completing / failing stuck workflows during incident response

### Crash recovery

If prj-service crashes mid-workflow, the workflow is checkpointed at
the last completed activity. When prj-service comes back up, the
Temporal worker registers on the `atlas-prj` task queue and picks
up the in-flight workflow from the last checkpoint. No human
intervention required.

### Metrics

The Temporal SDK auto-publishes Micrometer metrics:

- `temporal_workflow_task_completed_total` — counter, tagged with
  `workflow_type`
- `temporal_activity_task_completed_total` — counter, tagged with
  `activity_type` and `status` (success / failure)
- `temporal_workflow_task_execution_latency_seconds` — histogram

These land at `/actuator/prometheus` via the Phase 2C.2 wiring.

## Phased roadmap

- **2I.1 (shipped)** — Temporal SDK + Spring Boot starter wired into
  prj-service; worker config pointed at the compose-bundled cluster
- **2I.2 (shipped)** — `ArchaeologyWorkflow` end-to-end: two
  activities, retry policy, idempotent activity implementations
- **2I.3 (shipped)** — Controller submits async + workflow-status
  probe endpoint
- **2I.4 (shipped)** — `TestWorkflowEnvironment` coverage (4 cases)
- **2I.5 (shipped)** — this document

### Open follow-ups

- **Migrate the other 6 stage fan-outs** — generation, diff, capture,
  reconciliation, reports, characterize-run — to the same workflow
  shape. The pattern is established; each is a values-only addition.
- **Workflow signal for cancellation** — today there's no way for the
  SPA to ask Temporal "cancel that archaeology run." Add a `@Signal`
  method on the workflow interface and an HTTP endpoint that sends
  it.
- **Push-based status to the SPA** — the SPA polls today. SSE or
  WebSocket subscription on the workflow-status endpoint would
  collapse the poll loop. Defer until customer-visible value
  warrants it.
- **Workflow versioning** — Temporal's `Workflow.getVersion(...)` API
  is the right tool when we refactor the workflow body. Today's
  single-version workflow doesn't need it; document the pattern when
  the second activity sequence change lands.
- **Activity sweeper / DLQ** — for activities that fail
  permanently (e.g. malformed source path), surface them as
  triageable items in the audit browser rather than silent
  workflow-failure rows.

## Related docs

- [reliability.md](reliability.md) — the synchronous fan-outs that
  haven't migrated to Temporal yet still get circuit-breaker + retry
  protection via Resilience4j
- [observability.md](observability.md) — `traceparent` propagates
  through Temporal activities automatically (the SDK is OTel-aware);
  workflows show up as a parent span with their activities as
  children in Jaeger
- [deployment.md](deployment.md) — splitting workers into a separate
  Helm-managed Deployment is a values-only change (point them at the
  same task queue)
