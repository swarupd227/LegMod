-- Phase-1L migration: Characterization Validation (Track B Stage E).
-- Run = one batch sweep across migrated modules. Case = one synthetic test
-- with a legacy-vs-new output pair and a bucket.

CREATE TABLE IF NOT EXISTS uplift.char_run (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id       UUID NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    status           TEXT NOT NULL DEFAULT 'running'
                       CHECK (status IN ('running','completed','failed')),
    sample_size      INT NOT NULL DEFAULT 0,
    pass_count       INT NOT NULL DEFAULT 0,
    benign_count     INT NOT NULL DEFAULT 0,
    regression_count INT NOT NULL DEFAULT 0,
    error_text       TEXT
);
CREATE INDEX IF NOT EXISTS idx_uplift_charrun_project
    ON uplift.char_run(project_id, started_at DESC);

CREATE TABLE IF NOT EXISTS uplift.char_case (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id        UUID NOT NULL REFERENCES uplift.char_run(id) ON DELETE CASCADE,
    module_id     UUID REFERENCES uplift.module(id) ON DELETE SET NULL,
    test_name     TEXT NOT NULL,
    test_kind     TEXT NOT NULL,
    input_summary TEXT,
    legacy_output TEXT,
    new_output    TEXT,
    bucket        TEXT NOT NULL
                    CHECK (bucket IN ('pass','benign','regression')),
    diff_kind     TEXT,
    ai_recommendation TEXT,
    triage_state  TEXT NOT NULL DEFAULT 'open'
                    CHECK (triage_state IN ('open','accepted','rejected','fixed')),
    triaged_by    TEXT,
    triaged_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_uplift_charcase_run    ON uplift.char_case(run_id);
CREATE INDEX IF NOT EXISTS idx_uplift_charcase_bucket ON uplift.char_case(run_id, bucket);
CREATE INDEX IF NOT EXISTS idx_uplift_charcase_module ON uplift.char_case(run_id, module_id);
