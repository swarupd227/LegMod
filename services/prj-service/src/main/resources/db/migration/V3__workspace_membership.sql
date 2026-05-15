-- ----------------------------------------------------------------------------
-- Phase 2K — workspace membership.
--
-- Before this migration, `prj.workspace` carried a single `owner_email`
-- column. Any user with a valid JWT could read or mutate any project
-- by guessing the UUID — there was no per-workspace access control.
--
-- `prj.workspace_member` makes the per-tenant grant explicit and
-- multi-user:
--   * OWNER  — full control including invite/remove members, delete workspace
--   * EDITOR — read/write projects, advance gates, run stages
--   * VIEWER — read-only access to projects and gate history
--
-- The (workspace_id, user_email) pair is UNIQUE: a user has exactly one
-- role per workspace, and the same user can be a member of multiple
-- workspaces. The user_email column is the primary join key because
-- it's what the gateway-asserted identity headers carry — no separate
-- "user" table to reconcile, and SSO email changes are an explicit
-- rebind operation we handle in the workspace admin UI (deferred).
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS prj.workspace_member (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id  UUID NOT NULL REFERENCES prj.workspace(id) ON DELETE CASCADE,
    user_email    TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by    TEXT,
    UNIQUE (workspace_id, user_email)
);

-- Two-column index so the `findByUserEmail` listing path (used to render
-- the workspace switcher in the SPA chrome) hits an index, and the
-- per-workspace member-roster lookups don't need a full table scan.
CREATE INDEX IF NOT EXISTS idx_workspace_member_user
    ON prj.workspace_member(user_email);
CREATE INDEX IF NOT EXISTS idx_workspace_member_workspace
    ON prj.workspace_member(workspace_id);

-- Backfill: every existing workspace's owner_email becomes the implicit
-- OWNER member. Without this, applying 2K to an existing prj database
-- would lock everyone out of their own workspaces on the next deploy.
-- ON CONFLICT keeps re-applies idempotent if the migration runs against
-- a database that already has the row (e.g. a snapshot taken mid-rollout).
INSERT INTO prj.workspace_member (workspace_id, user_email, role, granted_by)
SELECT w.id, w.owner_email, 'OWNER', 'migration:V3'
FROM prj.workspace w
WHERE w.owner_email IS NOT NULL
  AND w.owner_email <> ''
ON CONFLICT (workspace_id, user_email) DO NOTHING;
