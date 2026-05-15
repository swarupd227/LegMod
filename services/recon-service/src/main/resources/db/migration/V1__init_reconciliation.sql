-- Idempotent Phase-1c migration: reconciliation (Stage C) tables.

CREATE SCHEMA IF NOT EXISTS recon;

CREATE TABLE IF NOT EXISTS recon.run (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id   UUID NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    status       TEXT NOT NULL DEFAULT 'running'
                  CHECK (status IN ('running','completed','failed')),
    summary      JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_recon_run_project ON recon.run(project_id, started_at DESC);

-- Unified element set: each row is one (path) seen across ≥1 of the three sources.
CREATE TABLE IF NOT EXISTS recon.element (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID NOT NULL,
    run_id        UUID REFERENCES recon.run(id) ON DELETE CASCADE,
    path          TEXT NOT NULL,           -- e.g. AllocationRequest.tradeDate
    vendor_view   JSONB NOT NULL DEFAULT '{}'::jsonb,
    code_view     JSONB NOT NULL DEFAULT '{}'::jsonb,
    empiric_view  JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (project_id, run_id, path)
);
CREATE INDEX IF NOT EXISTS idx_recon_elem_run ON recon.element(run_id);

-- A divergence is the surfaced product of the three-way merge.
CREATE TABLE IF NOT EXISTS recon.decision (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id      UUID NOT NULL,
    run_id          UUID REFERENCES recon.run(id) ON DELETE CASCADE,
    element_id      UUID REFERENCES recon.element(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    kind            TEXT NOT NULL,          -- type_lenience | rename | missing_vendor | format_difference | enum_promotion | fault_diff
    impact          TEXT NOT NULL DEFAULT 'medium'
                      CHECK (impact IN ('low','medium','high')),
    agent_action    TEXT,                   -- preserve_legacy | adopt_vendor | escalate | ...
    agent_rationale TEXT,
    agent_alts      TEXT[] DEFAULT '{}',
    agent_model     TEXT,
    agent_stub      BOOLEAN DEFAULT FALSE,
    confidence      TEXT NOT NULL DEFAULT 'medium'
                      CHECK (confidence IN ('low','medium','high')),
    resolution      TEXT NOT NULL DEFAULT 'pending'
                      CHECK (resolution IN ('pending','accepted','overridden','escalated','rejected')),
    chosen_action   TEXT,                   -- mirrors or overrides agent_action when human resolves
    chosen_payload  JSONB,
    note            TEXT,
    resolved_by     TEXT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_recon_dec_project ON recon.decision(project_id, resolution);
CREATE INDEX IF NOT EXISTS idx_recon_dec_run ON recon.decision(run_id);
