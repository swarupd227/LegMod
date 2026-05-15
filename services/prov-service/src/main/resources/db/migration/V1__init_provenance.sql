-- Provenance schema baseline. Append-only ledger of agent + human decisions
-- and artifact transformations across the migration pipeline.

CREATE TABLE IF NOT EXISTS prov.entry (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id   UUID NOT NULL,
    ts           TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_kind   TEXT NOT NULL CHECK (actor_kind IN ('agent','human','system')),
    actor_id     TEXT NOT NULL,
    action       TEXT NOT NULL,
    prompt       JSONB,
    model        TEXT,
    output       JSONB,
    tool_calls   JSONB,
    tokens_in    INT,
    tokens_out   INT,
    cost_usd     NUMERIC(10,4),
    latency_ms   INT,
    links        TEXT[],
    human_review JSONB,
    signature    BYTEA
);
CREATE INDEX IF NOT EXISTS idx_prov_project_ts ON prov.entry(project_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_prov_action     ON prov.entry(action);
