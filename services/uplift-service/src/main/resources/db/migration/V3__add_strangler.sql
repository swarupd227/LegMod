-- Phase-1j migration: Strangler Designer (Track B Stage C).
-- One step per module being extracted from the legacy system.

CREATE TABLE IF NOT EXISTS uplift.strangler_step (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID NOT NULL,
    module_id     UUID REFERENCES uplift.module(id) ON DELETE CASCADE,
    sequence_no   INT  NOT NULL,
    status        TEXT NOT NULL DEFAULT 'planned'
                    CHECK (status IN ('planned','ready','extracted')),
    facade_notes  TEXT,
    recipe_ids    JSONB NOT NULL DEFAULT '[]'::jsonb,   -- accepted recipes at seed time
    decided_by    TEXT,
    decided_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, module_id)
);
CREATE INDEX IF NOT EXISTS idx_uplift_strangler_project   ON uplift.strangler_step(project_id);
CREATE INDEX IF NOT EXISTS idx_uplift_strangler_seq       ON uplift.strangler_step(project_id, sequence_no);
