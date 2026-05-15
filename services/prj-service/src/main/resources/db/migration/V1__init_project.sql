-- Project schema baseline. Owns workspaces, projects, and per-stage gates.

CREATE TABLE IF NOT EXISTS prj.workspace (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        TEXT NOT NULL,
    owner_email TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS prj.project (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    workspace_id          UUID NOT NULL REFERENCES prj.workspace(id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,
    description           TEXT,
    mode                  TEXT NOT NULL CHECK (mode IN ('SOAP', 'UPLIFT')),
    source_framework      TEXT,
    target_framework      TEXT,
    target_java_version   TEXT,
    vendor_partner        TEXT,
    owner                 TEXT,
    risk_tier             TEXT NOT NULL DEFAULT 'MEDIUM'
                            CHECK (risk_tier IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    current_stage         TEXT NOT NULL DEFAULT 'A',
    source_path           TEXT,
    metrics               JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS prj.gate (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id      UUID NOT NULL REFERENCES prj.project(id) ON DELETE CASCADE,
    label           TEXT NOT NULL,                -- A, B, C, D, E, F
    state           TEXT NOT NULL DEFAULT 'pending'
                      CHECK (state IN ('pending','in_progress','passed','failed')),
    transitioned_at TIMESTAMPTZ,
    transitioned_by TEXT,
    UNIQUE (project_id, label)
);

CREATE INDEX IF NOT EXISTS idx_project_workspace ON prj.project(workspace_id);
CREATE INDEX IF NOT EXISTS idx_gate_project      ON prj.gate(project_id);
