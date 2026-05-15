-- Idempotent Phase-1d migration: code generation (Stage D) tables.
-- Schema 'gen' already exists from the bootstrap; add tables.

CREATE TABLE IF NOT EXISTS gen.run (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id    UUID NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        TEXT NOT NULL DEFAULT 'running'
                    CHECK (status IN ('running','completed','failed')),
    a_wsdl_uri    TEXT,                    -- s3://atlas-artifacts/.../authoritative.wsdl
    bindings_uri  TEXT,                    -- s3://atlas-artifacts/.../bindings.xjb
    output_uri    TEXT,                    -- s3://atlas-artifacts/.../jaxws-source.zip
    file_count    INT,
    error_count   INT NOT NULL DEFAULT 0,
    warning_count INT NOT NULL DEFAULT 0,
    log_text      TEXT,
    summary       JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_gen_run_project ON gen.run(project_id, started_at DESC);

CREATE TABLE IF NOT EXISTS gen.output_file (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id      UUID NOT NULL REFERENCES gen.run(id) ON DELETE CASCADE,
    project_id  UUID NOT NULL,
    path        TEXT NOT NULL,           -- e.g. com/envestnet/broadridge/AllocationService.java
    size_bytes  INT NOT NULL,
    storage_uri TEXT,                    -- s3://... per-file uri (optional)
    UNIQUE (run_id, path)
);
CREATE INDEX IF NOT EXISTS idx_gen_file_run ON gen.output_file(run_id);
