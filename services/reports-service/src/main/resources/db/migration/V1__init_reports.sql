-- Idempotent Phase-1f migration: reports / deliverables (Stage F) tables.

CREATE SCHEMA IF NOT EXISTS reports;

CREATE TABLE IF NOT EXISTS reports.bundle (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id   UUID NOT NULL,
    built_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    status       TEXT NOT NULL DEFAULT 'building'
                  CHECK (status IN ('building','completed','failed')),
    bundle_uri   TEXT,                        -- s3://atlas-artifacts/{pid}/migration-package.zip
    closure_uri  TEXT,                        -- s3://atlas-artifacts/{pid}/closure.md
    size_bytes   INT,
    file_count   INT,
    closure_text TEXT,                        -- duplicate of the doc for fast read-back
    closure_stub BOOLEAN NOT NULL DEFAULT FALSE,
    summary      JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_reports_bundle_project ON reports.bundle(project_id, built_at DESC);
