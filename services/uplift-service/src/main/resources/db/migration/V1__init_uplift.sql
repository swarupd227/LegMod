-- Idempotent Phase-1h migration: framework uplift (Track B Stage A) tables.

CREATE SCHEMA IF NOT EXISTS uplift;

CREATE TABLE IF NOT EXISTS uplift.scan_run (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT NOT NULL DEFAULT 'running'
                    CHECK (status IN ('running','completed','failed')),
    source_path   TEXT NOT NULL,
    summary       JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_uplift_run_project ON uplift.scan_run(project_id, started_at DESC);

CREATE TABLE IF NOT EXISTS uplift.module (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id   UUID NOT NULL,
    run_id       UUID REFERENCES uplift.scan_run(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,           -- e.g. "web", "domain", "service"
    package_name TEXT NOT NULL,           -- com.envestnet.legacy.order.web
    file_count   INT NOT NULL DEFAULT 0,
    loc          INT NOT NULL DEFAULT 0,  -- non-blank/comment lines
    difficulty   INT NOT NULL DEFAULT 0,  -- 0-10 derived score
    finding_count INT NOT NULL DEFAULT 0,
    UNIQUE (project_id, run_id, name)
);
CREATE INDEX IF NOT EXISTS idx_uplift_module_run ON uplift.module(run_id);

CREATE TABLE IF NOT EXISTS uplift.finding (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id   UUID NOT NULL,
    run_id       UUID REFERENCES uplift.scan_run(id) ON DELETE CASCADE,
    module_id    UUID REFERENCES uplift.module(id) ON DELETE CASCADE,
    rule_id      TEXT NOT NULL,           -- javax_persistence | ibm_websphere | …
    rule_label   TEXT NOT NULL,
    severity     TEXT NOT NULL CHECK (severity IN ('low','medium','high')),
    file_path    TEXT NOT NULL,
    line_start   INT,
    line_end     INT,
    snippet      TEXT,
    suggested_recipe TEXT                 -- e.g. "org.openrewrite.java.migrate.JavaxToJakarta"
);
CREATE INDEX IF NOT EXISTS idx_uplift_finding_run    ON uplift.finding(run_id);
CREATE INDEX IF NOT EXISTS idx_uplift_finding_module ON uplift.finding(module_id);
CREATE INDEX IF NOT EXISTS idx_uplift_finding_rule   ON uplift.finding(rule_id);
