-- Idempotent Phase-1e migration: differential validation (Stage E) tables.
-- Schema 'diff' already exists from the bootstrap; add tables.

CREATE TABLE IF NOT EXISTS diff.run (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT NOT NULL DEFAULT 'running'
                    CHECK (status IN ('running','completed','failed')),
    envelopes_replayed INT NOT NULL DEFAULT 0,
    pass_count    INT NOT NULL DEFAULT 0,
    benign_count  INT NOT NULL DEFAULT 0,
    amber_count   INT NOT NULL DEFAULT 0,
    red_count     INT NOT NULL DEFAULT 0,
    is_promoted   BOOLEAN NOT NULL DEFAULT FALSE,
    summary       JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_diff_run_project ON diff.run(project_id, started_at DESC);

-- One row per replayed envelope.
CREATE TABLE IF NOT EXISTS diff.replay (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id          UUID NOT NULL REFERENCES diff.run(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL,
    envelope_id     UUID,                      -- cap.envelope.id (FK by reference)
    operation_name  TEXT NOT NULL,
    direction       TEXT,
    bucket          TEXT NOT NULL DEFAULT 'pass'
                      CHECK (bucket IN ('pass','benign','amber','red')),
    diff_count      INT NOT NULL DEFAULT 0,
    first_kind      TEXT,                      -- e.g. namespace_prefix | date_format | missing_element
    summary         TEXT
);
CREATE INDEX IF NOT EXISTS idx_diff_replay_run ON diff.replay(run_id, bucket);

-- One row per emitted divergence within a replay.
CREATE TABLE IF NOT EXISTS diff.divergence (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    replay_id       UUID NOT NULL REFERENCES diff.replay(id) ON DELETE CASCADE,
    project_id      UUID NOT NULL,
    operation_name  TEXT,
    kind            TEXT NOT NULL,             -- namespace_prefix | element_ordering | date_format | type_precision | missing_element | extra_element | enum_miss | value_diff | fault_diff
    bucket          TEXT NOT NULL
                      CHECK (bucket IN ('benign','amber','red')),
    xpath           TEXT,
    legacy_value    TEXT,
    new_value       TEXT,
    human_summary   TEXT,
    agent_action    TEXT,                      -- e.g. fix_binding | accept_benign | escalate
    agent_rationale TEXT,
    agent_model     TEXT,
    agent_stub      BOOLEAN DEFAULT FALSE,
    autofix_proposal JSONB,
    resolved        BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_by     TEXT,
    resolved_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_diff_div_replay ON diff.divergence(replay_id);
CREATE INDEX IF NOT EXISTS idx_diff_div_bucket ON diff.divergence(project_id, bucket);
