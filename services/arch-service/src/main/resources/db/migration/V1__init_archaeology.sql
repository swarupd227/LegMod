-- Archaeology schema baseline. Stage A artifacts: scan runs, recovered SOAP
-- operations, type mappings, custom adapters, and per-operation narratives.

CREATE TABLE IF NOT EXISTS arch.run (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id   UUID NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    status       TEXT NOT NULL DEFAULT 'running'
                  CHECK (status IN ('running','completed','failed')),
    source_path  TEXT NOT NULL,
    summary      JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_arch_run_project ON arch.run(project_id, started_at DESC);

CREATE TABLE IF NOT EXISTS arch.operation (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id      UUID NOT NULL,
    run_id          UUID REFERENCES arch.run(id) ON DELETE SET NULL,
    namespace       TEXT NOT NULL,
    name            TEXT NOT NULL,
    soap_style      TEXT,                -- DOCUMENT | RPC | WRAPPED
    soap_use        TEXT,                -- LITERAL | ENCODED
    source_class    TEXT,
    source_lines    INT[],
    input_type      TEXT,
    output_type     TEXT,
    fault_types     TEXT[] DEFAULT '{}',
    flags           TEXT[] DEFAULT '{}',
    confidence      TEXT NOT NULL DEFAULT 'medium'
                      CHECK (confidence IN ('low','medium','high')),
    decision_state  TEXT NOT NULL DEFAULT 'pending'
                      CHECK (decision_state IN ('pending','accepted','flagged','modified','rejected')),
    reviewed_by     TEXT,
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, namespace, name)
);
CREATE INDEX IF NOT EXISTS idx_arch_op_project ON arch.operation(project_id);

CREATE TABLE IF NOT EXISTS arch.type_mapping (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    operation_id    UUID NOT NULL REFERENCES arch.operation(id) ON DELETE CASCADE,
    field_name      TEXT,
    java_type       TEXT NOT NULL,
    qname_namespace TEXT,
    qname_local     TEXT,
    adapter_fqn     TEXT,
    notes           TEXT
);
CREATE INDEX IF NOT EXISTS idx_arch_mapping_op ON arch.type_mapping(operation_id);

CREATE TABLE IF NOT EXISTS arch.adapter (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id   UUID NOT NULL,
    fqn          TEXT NOT NULL,
    kind         TEXT NOT NULL,         -- date | enum | numeric | other
    pattern      TEXT,                  -- inferred format e.g. MM/dd/yyyy
    source_lines INT[],
    UNIQUE (project_id, fqn)
);

CREATE TABLE IF NOT EXISTS arch.narrative (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    operation_id  UUID NOT NULL REFERENCES arch.operation(id) ON DELETE CASCADE,
    text          TEXT NOT NULL,
    model         TEXT,
    tokens_in     INT,
    tokens_out    INT,
    latency_ms    INT,
    stub          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_arch_narrative_op ON arch.narrative(operation_id);
