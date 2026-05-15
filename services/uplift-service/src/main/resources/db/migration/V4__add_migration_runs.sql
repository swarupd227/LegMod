-- Phase-1k migration: Module Migration (Track B Stage D).
-- One run per (project, strangler step) execution; one row per file changed.

CREATE TABLE IF NOT EXISTS uplift.migration_run (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID NOT NULL,
    step_id       UUID REFERENCES uplift.strangler_step(id) ON DELETE CASCADE,
    module_id     UUID REFERENCES uplift.module(id) ON DELETE SET NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT NOT NULL DEFAULT 'running'
                    CHECK (status IN ('running','completed','failed')),
    summary       JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_uri    TEXT,
    error_text    TEXT
);
CREATE INDEX IF NOT EXISTS idx_uplift_mig_project ON uplift.migration_run(project_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_uplift_mig_step    ON uplift.migration_run(step_id, started_at DESC);

CREATE TABLE IF NOT EXISTS uplift.migration_change (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id       UUID NOT NULL REFERENCES uplift.migration_run(id) ON DELETE CASCADE,
    file_path    TEXT NOT NULL,
    recipe_id    TEXT NOT NULL,
    changes      INT  NOT NULL DEFAULT 0,
    diff_text    TEXT
);
CREATE INDEX IF NOT EXISTS idx_uplift_mig_change_run    ON uplift.migration_change(run_id);
CREATE INDEX IF NOT EXISTS idx_uplift_mig_change_recipe ON uplift.migration_change(run_id, recipe_id);
