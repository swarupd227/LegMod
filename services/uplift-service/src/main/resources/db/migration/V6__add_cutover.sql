-- Phase-1M migration: Cutover & Decommission (Track B Stage F).
-- One cutover row per strangler step. The decommission checklist lives on
-- the row as JSONB (small, scoped, no joins).

CREATE TABLE IF NOT EXISTS uplift.cutover (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id      UUID NOT NULL,
    step_id         UUID REFERENCES uplift.strangler_step(id) ON DELETE CASCADE,
    module_id       UUID REFERENCES uplift.module(id) ON DELETE SET NULL,
    state           TEXT NOT NULL DEFAULT 'planned'
                      CHECK (state IN ('planned','shadow','canary','live',
                                       'rolled_back','decommissioned')),
    traffic_percent INT NOT NULL DEFAULT 0
                      CHECK (traffic_percent BETWEEN 0 AND 100),
    cutover_date    TIMESTAMPTZ,
    rollback_plan   TEXT,
    notes           TEXT,
    checklist       JSONB NOT NULL DEFAULT '[]'::jsonb,
    approved_by     TEXT,
    approved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, step_id)
);
CREATE INDEX IF NOT EXISTS idx_uplift_cutover_project ON uplift.cutover(project_id);
CREATE INDEX IF NOT EXISTS idx_uplift_cutover_state   ON uplift.cutover(project_id, state);
