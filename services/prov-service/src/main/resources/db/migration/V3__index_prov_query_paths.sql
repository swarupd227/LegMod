-- Phase 2F.3 preventive performance fixes for prov-service.
--
-- ProvService.query() is the heaviest read in the system; it's hit by
-- the audit browser's filter UI on every keystroke (debounced) and by
-- agent-by-agent queries from operations. The V1 schema covers the
-- (project_id, ts DESC) and (action) access paths, but two common
-- filter patterns and the free-text search were uncovered:
--
--   1. (project_id, action)     — "what `archaeology_run` events
--                                 happened on this project?"
--   2. (project_id, actor_id)   — "what did Alice do on this project?"
--   3. ILIKE on actor_id/action — the SPA's `q=` query string drives
--                                 a substring search across all 3
--                                 columns. Without pg_trgm + GIN, the
--                                 planner does a sequential scan of
--                                 every row matching the project_id.
--
-- We don't add a trigram index on `output::text` — the JSONB blob is
-- large and trigram-indexing it would balloon storage. The audit
-- browser's `q=` filter on `output` falls back to the sequential scan;
-- if/when that becomes a hot spot, the right fix is full-text search
-- via `to_tsvector(... output::text ...)` on a stored generated column.

-- Composite indexes for the two project-scoped filter patterns.
CREATE INDEX IF NOT EXISTS idx_prov_project_action
    ON prov.entry (project_id, action);

CREATE INDEX IF NOT EXISTS idx_prov_project_actor
    ON prov.entry (project_id, actor_id);

-- Trigram indexes for the substring search on the smaller text columns.
-- pg_trgm is shared across all schemas; create it idempotently.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GIN-trigram on the two small text columns where ILIKE happens.
-- Index size is bounded by the total distinct values in each column,
-- which for `action` is O(50) (event types) and for `actor_id` is
-- O(num_users + num_agents) — both small.
CREATE INDEX IF NOT EXISTS idx_prov_action_trgm
    ON prov.entry USING gin (action gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_prov_actor_trgm
    ON prov.entry USING gin (actor_id gin_trgm_ops);
