-- Idempotent Phase-1b migration: capture (Stage B) tables.

CREATE SCHEMA IF NOT EXISTS cap;

CREATE TABLE IF NOT EXISTS cap.deployment (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id      UUID NOT NULL,
    environment     TEXT NOT NULL,           -- QUAT-1, QUAT-2, UAT-1, …
    method          TEXT NOT NULL,           -- jvm-agent | servlet-filter | sidecar | network-tap | demo
    status          TEXT NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending','live','paused','stopped','failed')),
    sample_rate     INT NOT NULL DEFAULT 100,
    max_payload     INT NOT NULL DEFAULT 65536,
    sanitization_v  TEXT NOT NULL DEFAULT 'demo-v1',
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ,
    last_seen       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, environment)
);
CREATE INDEX IF NOT EXISTS idx_cap_deploy_project ON cap.deployment(project_id);

CREATE TABLE IF NOT EXISTS cap.envelope (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id        UUID NOT NULL,
    deployment_id    UUID REFERENCES cap.deployment(id) ON DELETE SET NULL,
    operation_name    TEXT NOT NULL,
    direction         TEXT NOT NULL CHECK (direction IN ('REQUEST','RESPONSE','FAULT')),
    captured_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    partner           TEXT,
    environment       TEXT,
    size_bytes        INT NOT NULL DEFAULT 0,
    storage_uri       TEXT NOT NULL,         -- s3://bucket/key
    sanitization_hits INT NOT NULL DEFAULT 0,
    correlation_id    TEXT
);
CREATE INDEX IF NOT EXISTS idx_cap_env_project_op ON cap.envelope(project_id, operation_name, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_cap_env_recent ON cap.envelope(project_id, captured_at DESC);

CREATE TABLE IF NOT EXISTS cap.sanitization_rule (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID,                      -- nullable for global default rules
    name          TEXT NOT NULL,
    pattern       TEXT NOT NULL,             -- regex
    strategy      TEXT NOT NULL CHECK (strategy IN ('hash','redact','tokenize','leave')),
    hits          BIGINT NOT NULL DEFAULT 0,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (project_id, name)
);

-- Seed minimal default rule set so the sanitization audit pane has rows.
INSERT INTO cap.sanitization_rule (project_id, name, pattern, strategy, hits) VALUES
  (NULL, 'account_number', '\b[A-Z]{3}-?\d{5,}\b',                          'hash',     0),
  (NULL, 'ssn',            '\b\d{3}-\d{2}-\d{4}\b',                          'redact',   0),
  (NULL, 'email',          '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}', 'tokenize', 0)
ON CONFLICT (project_id, name) DO NOTHING;
