# Atlas Migrate · multi-tenancy

This document covers Phase 2K — workspace-scoped authorization,
context propagation across the fan-out, and the audit log that
records every state-changing operation.

## The problem 2K solves

Before 2K, the schema had a `workspace_id` column on `project` and
the controllers happily returned any project given its UUID:

```java
@GetMapping("/projects/{id}")
public ResponseEntity<?> getProject(@PathVariable UUID id) {
    return projects.findById(id)              // unscoped: any tenant
            .map(p -> ResponseEntity.ok(...))
            .orElse(ResponseEntity.notFound().build());
}
```

A valid JWT + a guessed UUID = read any tenant's project. Same shape
for `/api/v1/workspaces` (returned the full table), the stage-status
pass-throughs (`/projects/{id}/stages/*/status`), and every mutation
endpoint. The schema had the column; nothing enforced it.

2K closes the gap end-to-end:

1. **Membership table** — explicit `(workspace, user, role)` grants
2. **WorkspaceAccess service** — single authorization check used by
   every controller endpoint
3. **Controller refactor** — every project-touching endpoint runs
   through the access check
4. **Workspace context propagation** — MDC key + downstream header so
   the workspace identity travels with the request across services
5. **Audit log** — append-only table records every state change,
   tagged with workspace + actor + role at time of action

## What's shipped (Phase 2K)

| Layer | Where |
|---|---|
| Membership schema | `services/prj-service/src/main/resources/db/migration/V3__workspace_membership.sql` |
| Audit-log schema | `services/prj-service/src/main/resources/db/migration/V4__audit_log.sql` |
| Domain model | `…/prj/domain/WorkspaceMember.java`, `…/AuditLogEntry.java` |
| Repository | `…/prj/repo/WorkspaceMemberRepository.java` |
| Authorization service | `…/prj/auth/WorkspaceAccess.java` |
| 403 mapper | `…/prj/auth/WorkspaceAccessDeniedException.java` |
| Controller refactor | `…/prj/web/ProjectController.java` (every endpoint) |
| Context propagation | `…/prj/logging/RequestContextFilter.java`, `…/prj/auth/IdentityForwardingInterceptor.java` |
| Audit writer | `…/prj/audit/AuditLogger.java` |
| Tests | `WorkspaceAccessTest` (11), `ProjectControllerAuthorizationTest` (13), `AuditLoggerTest` (7), plus updates to `ProjectControllerTest` (14) |

## Membership model

### The workspace is the tenant boundary

Atlas has one boundary, not two. There's no "org" above the workspace
— each workspace is independent, and a user is granted access by
being added to the workspace's member list. This keeps the model
small and matches how migration engagements actually work in
practice (one workspace per customer-engagement, members invited as
the team forms).

### The `workspace_member` table

```sql
CREATE TABLE prj.workspace_member (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES prj.workspace(id) ON DELETE CASCADE,
    user_email    TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by    TEXT,
    UNIQUE (workspace_id, user_email)
);
```

The `(workspace_id, user_email)` pair is unique — a user has exactly
one role in a workspace, and the same email can be a member of
multiple workspaces.

`user_email` is the join key (not a foreign key to a `user` table,
which Atlas doesn't have). It matches the JWT-asserted email the
gateway puts in `X-Atlas-User-Email`. SSO email changes are a rare
operation that the workspace admin UI will eventually handle as an
explicit rebind; in the meantime, a customer who renames a user
needs to update the membership row.

### Roles

Three workspace-level roles, strictly ordered:

| Role | What you can do |
|---|---|
| `OWNER` | Everything an EDITOR can do, plus invite/remove members and delete the workspace |
| `EDITOR` | Read all projects, mutate them (attach source, run stages, advance gates) |
| `VIEWER` | Read all projects in the workspace; no writes |

The role hierarchy is honored by `WorkspaceAccess.Level.covers` — an
OWNER passes every `canRead` / `canWrite` / `canAdmin` check.

These are distinct from the **JWT-level** roles (ENGINEER, TECH_LEAD,
ADMIN) that the IdP issues. JWT roles gate platform-wide capabilities
(can-create-workspace, can-administer-system); workspace roles gate
access to a specific workspace's data. The two layers are AND-ed
except for the deliberate ADMIN bypass below.

### ADMIN bypass

A user whose JWT carries the platform-level **ADMIN** role bypasses
the membership check entirely — they can read and mutate every
workspace. This exists for support engineers who need to triage
customer issues without being invited to every workspace.

The bypass is auditable: `AuditLogger` records `user_role =
ADMIN_BYPASS` distinct from a real membership role, so a
post-incident investigation can tell apart "support engineer doing
triage" from "this user was a real member of the workspace at the
time."

### Backfill

The V3 migration backfills every existing workspace's `owner_email`
as the OWNER member of that workspace. Without the backfill,
applying 2K to a populated database would lock everyone out of their
own workspaces on the next deploy.

The `/internal/seed` endpoint also inserts the OWNER member as a
defensive net for "deleted the table, re-seeded" cycles in dev.

## Authorization model

### The two predicates

`WorkspaceAccess` exposes a small surface:

```java
boolean canRead(String email, UUID workspaceId);   // VIEWER+
boolean canWrite(String email, UUID workspaceId);  // EDITOR+
boolean canAdmin(String email, UUID workspaceId);  // OWNER
Set<UUID> accessibleWorkspaceIds(String email);    // for listings
boolean isAdmin(String email);                     // JWT ADMIN role
Optional<Level> levelOf(String email, UUID ws);    // audit-only lookup
```

`levelOf` is deliberately separate from `has` — it returns the
**stored** role, ignoring the ADMIN bypass. `AuditLogger` uses it to
record what role the user actually had, even when an ADMIN bypass is
in effect.

### Controller helpers

Every project-touching endpoint goes through one of four helpers in
`ProjectController`:

| Helper | Returns | On project miss | On read-deny | On write-deny |
|---|---|---|---|---|
| `findReadable(id)` | `Optional<Project>` | empty | empty | n/a |
| `findWritable(id)` | `Optional<Project>` | empty | empty | throws (→ 403) |
| `canReadProject(id)` | boolean | false | false | n/a |
| `canWriteProject(id)` | boolean | false | false | throws (→ 403) |

The hide-existence-vs-403 distinction is deliberate:

- **Reads on a workspace the caller can't see → 404.** A non-member
  must not be able to tell "no such project" apart from "you don't
  have access" — that would let them enumerate project UUIDs across
  tenants. So we collapse both into 404 and the caller can't
  distinguish.
- **Writes on a workspace the caller can see but not modify → 403.**
  By the time we're 403-ing, the caller is already a member of the
  workspace, so existence isn't a secret. 403 is the more
  informative status code and lets the SPA render a clearer error.

### What got refactored

Concrete diff in `ProjectController.java`:

- `listWorkspaces` — filtered to `access.accessibleWorkspaceIds`;
  ADMINs see everything via the unfiltered `findAll`.
- `listProjects(wsId)` — 404 on deny.
- `createProject(req)` — 403 if caller isn't EDITOR+ on target workspace.
- `getProject(id)` / `cutoverClosure(id)` — `findReadable(id)`.
- Every mutation (`attachSource`, `run*`, `*Finalize`, gate
  transitions, recipe decisions, etc.) — `findWritable(id)` or
  `canWriteProject(id)`.
- Every status fetcher (`*Status`, `provenance`, etc.) —
  `canReadProject(id)`.

That's about 40 endpoints refactored. Every project-touching path
now passes through the access check exactly once at the top.

## Workspace context propagation

The audit log + per-tenant logging only works if the workspace
identity travels with the request. 2K adds two propagation paths:

### Server-side: MDC

`RequestContextFilter` already populated MDC keys for `requestId`,
`userEmail`, `userRoles`, `projectId`. 2K adds:

```java
public static final String MDC_WORKSPACE_ID = "workspaceId";
public static final String ATTR_WORKSPACE_ID = "atlas.workspaceId";
```

`MDC_WORKSPACE_ID` is populated three ways:
1. URL pattern match for `/workspaces/{uuid}/...` — set by
   `RequestContextFilter` at filter entry.
2. Controller stamp — `ProjectController.stashWorkspaceContext`
   stamps it after `findReadable`/`findWritable` resolves the
   project's workspace.
3. Background tasks — never automatically; tasks that want
   workspace-tagged logs should put it in MDC themselves.

The MDC is cleared in `finally` so it doesn't leak across requests
on a reused worker thread.

### Client-side: downstream header

`IdentityForwardingInterceptor` reads `MDC_WORKSPACE_ID` and sets
the `X-Atlas-Workspace-Id` header on outbound RestTemplate calls.
Downstream services (arch / cap / recon / gen / diff / reports /
prov / uplift) can:

- log the workspace alongside their own structured-log entries
- partition per-tenant cache keys or rate-limit buckets without
  duplicating the access check (prj-service is the authoritative
  authz tier)
- emit per-tenant metrics tags

Downstream services do NOT re-check the authorization — they trust
the prj-service-asserted header because prj-service has already
proven the caller is a member. This is the same trust model as the
existing `X-Atlas-User-Email` / `X-Atlas-User-Roles` headers.

## Audit log

### Schema

```sql
CREATE TABLE prj.audit_log (
    id            UUID PRIMARY KEY,
    ts            TIMESTAMPTZ NOT NULL DEFAULT now(),
    request_id    TEXT,
    workspace_id  UUID REFERENCES prj.workspace(id) ON DELETE SET NULL,
    project_id    UUID REFERENCES prj.project(id)   ON DELETE SET NULL,
    user_email    TEXT,
    user_role     TEXT,
    action        TEXT NOT NULL,
    entity_type   TEXT,
    entity_id     TEXT,
    outcome       TEXT NOT NULL DEFAULT 'success',
    payload       JSONB NOT NULL DEFAULT '{}'::jsonb
);
```

Three indexes cover the common query shapes (per-workspace newest
first, per-actor newest first, per-project newest first). Append-only
— no UPDATE / DELETE today; the retention sweep is Phase 2M.

### What's instrumented

Phase 2K wires `AuditLogger.record` at three high-stakes call sites:

| Call site | `action` | Payload |
|---|---|---|
| `POST /projects` | `project.create` | `{name, mode, riskTier, vendorPartner}` |
| `POST /projects/{id}/sources` | `project.attach_source` | `{previousPath, newPath}` |
| Every gate transition (via `advanceGate`) | `gate.advance` | `{state, actor}` |

The other ~50 state-changing endpoints aren't instrumented yet —
adding them is mechanical, but doing it as a single sweep would have
bloated the 2K diff. The `AuditLogger` is fully wired into the
controller so adding more call sites is a one-line change.

### user_role: three values

The `user_role` column captures the actor's effective role at time
of action:

| Value | When |
|---|---|
| `OWNER` / `EDITOR` / `VIEWER` | Real workspace membership |
| `ADMIN_BYPASS` | Caller used the JWT-level ADMIN bypass |
| `unauthenticated` | No gateway identity present (rare; `/internal/seed` etc.) |

This is critical for investigations: "show me every action a support
engineer took via bypass on this customer's workspace" is one query
against `user_role = 'ADMIN_BYPASS'`.

### Failure tolerance

The audit write is **best-effort**. If the JDBC call throws (audit
table unavailable, connection pool exhausted, etc.) `AuditLogger`
logs at WARN and returns normally. The request being audited
succeeds regardless — losing audit rows is bad, but breaking the
user's actual work is worse. Downstream observability is responsible
for alerting if the audit row rate drops to zero.

The `payload` column is JSONB — a payload Jackson can't serialise
(e.g. a self-referential object) falls back to a stub payload like
`{"_serialization_error": "JsonMappingException"}` so the audit row
isn't lost.

## Test coverage

| Suite | Cases | What it pins |
|---|---|---|
| `WorkspaceAccessTest` | 11 | Role hierarchy (OWNER covers EDITOR covers VIEWER), ADMIN bypass, case-insensitive role match, `levelOf` vs `has` distinction, defensive null/blank/unknown-role behaviour |
| `ProjectControllerAuthorizationTest` | 13 | Cross-tenant read → 404, cross-tenant write → 404, VIEWER → 403 on write, ADMIN bypasses membership, `listWorkspaces` is per-user filtered, `createProject` requires EDITOR on target, mutations with no access don't touch the downstream |
| `AuditLoggerTest` | 7 | Every column populated, JSONB payload serialised, role resolution (OWNER vs ADMIN_BYPASS vs unauthenticated), JDBC failure swallowed (audit can't break the request), unserialisable payload falls back to stub, null payload → `{}` |
| `ProjectControllerTest` | 14 | All existing stage/gate state-machine tests continue to pass against the new authz layer (the default test identity is OWNER of the seed workspace) |

74/74 prj-service tests green.

## Phased roadmap

- **2K.1 (shipped)** — `workspace_member` table + owner-as-OWNER backfill
- **2K.2 (shipped)** — `WorkspaceMember` domain + repository
- **2K.3 (shipped)** — `WorkspaceAccess` service with role hierarchy + ADMIN bypass
- **2K.4 (shipped)** — `ProjectController` refactor; every endpoint workspace-checked
- **2K.5 (shipped)** — Workspace context propagation (MDC + `X-Atlas-Workspace-Id` header)
- **2K.6 (shipped)** — `audit_log` table + `AuditLogger` + initial instrumentation
- **2K.7 (shipped)** — Test coverage (31 new cases)
- **2K.8 (shipped)** — This document

### Open follow-ups

- **Workspace member admin UI** — invite/remove members, change role.
  The SPA today shows workspaces but has no member-management surface.
- **Membership grant endpoint** — `POST /workspaces/{ws}/members`
  with OWNER-only authorization. The repository + access checks are
  already in place; the controller endpoint is the missing piece.
- **Per-IdP group sync** — for customers running Keycloak / Okta /
  Entra with group claims, sync workspace membership from a
  `workspace:<id>:editor` group claim shape rather than maintaining
  the membership table manually. Spec lives in
  docs/auth-production.md; needs a customer commitment before we
  build it.
- **Full audit-log instrumentation** — wire `AuditLogger.record` /
  `recordFailure` at the remaining ~50 state-changing endpoints
  (recipe decisions, strangler edits, cutover transitions, etc.).
  Mechanical; deferred to keep 2K reviewable.
- **Audit-log read API** — `GET /api/v1/workspaces/{ws}/audit` with
  per-workspace filter, paginated. The schema is indexed for this
  exact query.
- **Retention sweep** — Phase 2M will prune audit rows older than a
  configurable retention window per tenant.
- **Downstream re-validation** — some customers will want the
  downstream services to verify the `X-Atlas-Workspace-Id` header
  against their own copy of the membership table (defence-in-depth
  for a prj-service compromise scenario). Deferred until a customer
  asks; the propagation layer is in place.
