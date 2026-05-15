-- ----------------------------------------------------------------------------
-- Phase 2K — workspace-scoped audit log.
--
-- Atlas already records limited audit fields on individual domain rows
-- (Gate.transitioned_by, Gate.transitioned_at). Phase 2K introduces a
-- proper append-only audit log that:
--
--   * carries the workspace_id so per-tenant audit views are cheap
--     (single index probe, not a join through project)
--   * carries the request_id so an audit entry can be cross-linked to
--     the structured log + tracing span for the same operation
--   * carries the actor's email AND the role at time of action so
--     post-hoc "who could have done this" investigations don't need
--     to time-travel the membership table
--   * is JSONB-flexible on the payload — every state-changing endpoint
--     records what changed in the shape it makes sense for that action
--     (gate transition, recipe decision, member grant, etc.)
--
-- Append-only: no UPDATE / DELETE policy. The retention sweep (Phase
-- 2M, deferred) will eventually prune by ts. Until then the table
-- grows monotonically — at ~1 KB per row and a few hundred rows per
-- engineer-day that's tolerable for years.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS prj.audit_log (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    ts            TIMESTAMPTZ NOT NULL DEFAULT now(),
    request_id    TEXT,
    workspace_id  UUID REFERENCES prj.workspace(id) ON DELETE SET NULL,
    project_id    UUID REFERENCES prj.project(id)   ON DELETE SET NULL,
    user_email    TEXT,
    user_role     TEXT,               -- OWNER | EDITOR | VIEWER | ADMIN_BYPASS | unauthenticated
    action        TEXT NOT NULL,      -- e.g. project.create, gate.advance, recipe.decision
    entity_type   TEXT,               -- e.g. project, gate, recipe
    entity_id     TEXT,               -- not UUID — some entities (gate labels, recipe ids) aren't UUIDs
    outcome       TEXT NOT NULL DEFAULT 'success'
                    CHECK (outcome IN ('success', 'failure')),
    payload       JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- Index the columns that drive the common queries:
--   * "show me everything in workspace W, newest first" → SPA audit tab
--   * "show me everything an actor E did" → security investigations
--   * "show me everything against project P" → per-project history
-- All three want DESC on ts for the "newest first" UI.
CREATE INDEX IF NOT EXISTS idx_audit_log_workspace_ts
    ON prj.audit_log(workspace_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor_ts
    ON prj.audit_log(user_email, ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_project_ts
    ON prj.audit_log(project_id, ts DESC);
