-- ----------------------------------------------------------------------------
-- Phase 2L — add workspace_id + user_email to the provenance ledger so cost
-- attribution can roll up by tenant and by individual engineer.
--
-- Before this migration, prov.entry carried project_id + actor_id but no
-- workspace dimension. That meant "show me total LLM spend in workspace W
-- last week" required joining against prj.project — across services, across
-- databases in some deployments — and was prohibitively slow.
--
-- user_email separates the requesting human from the SERVICE actor_id.
-- Today an "agent_narrative" entry has actor_id='code-archaeology' (the
-- service name); the human who triggered the run is invisible. With
-- user_email, "show me what alice cost the company this month" becomes a
-- single indexed query.
--
-- Both columns are NULLABLE. Existing rows have no workspace / user info
-- and that's fine — the rollup endpoints exclude null-workspace rows
-- from per-tenant queries (a row with no workspace is implicitly cross-
-- tenant overhead, not anyone's spend).
-- ----------------------------------------------------------------------------

ALTER TABLE prov.entry
    ADD COLUMN IF NOT EXISTS workspace_id UUID,
    ADD COLUMN IF NOT EXISTS user_email   TEXT;

-- Indexes for the three rollup query shapes:
--   * per-workspace cost & event totals
--   * per-user cost (often constrained to a single workspace)
--   * per-(workspace, day) sparkline for the SPA's spend chart
CREATE INDEX IF NOT EXISTS idx_prov_workspace_ts
    ON prov.entry(workspace_id, ts DESC)
    WHERE workspace_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_prov_workspace_user
    ON prov.entry(workspace_id, user_email)
    WHERE workspace_id IS NOT NULL AND user_email IS NOT NULL;

-- Partial index over rows that carry real cost — most rows are zero-
-- cost (human decisions, status pings) and skipping them keeps the
-- spend rollup queries tight.
CREATE INDEX IF NOT EXISTS idx_prov_workspace_cost
    ON prov.entry(workspace_id, ts DESC)
    WHERE workspace_id IS NOT NULL AND cost_usd IS NOT NULL AND cost_usd > 0;
