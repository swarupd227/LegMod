-- V6__forecast.sql
-- Migration Forecast: a pre-Stage-A effort estimate. Populated by
-- prj-service via a structural probe of the cloned source tree
-- (arch-service /internal/archaeology/peek) plus an LLM grounding call.
-- The result is one row per project; re-running the forecast overwrites.

CREATE TABLE prj.forecast (
    project_id        UUID PRIMARY KEY REFERENCES prj.project(id) ON DELETE CASCADE,
    generated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    estimated_weeks   INT NOT NULL,
    confidence        VARCHAR(16) NOT NULL CHECK (confidence IN ('LOW','MEDIUM','HIGH')),
    top_risks         JSONB NOT NULL DEFAULT '[]'::jsonb,
    rationale         TEXT NOT NULL DEFAULT '',
    -- Structural facts captured from the AST peek, so the UI can show
    -- "32 Java files, 7 operations, 2 adapters" alongside the LLM-derived
    -- estimate. JSONB keeps it forward-compatible.
    structural_facts  JSONB NOT NULL DEFAULT '{}'::jsonb,
    model             VARCHAR(64),
    cost_usd          NUMERIC(10, 4),
    latency_ms        BIGINT
);

CREATE INDEX idx_forecast_generated_at ON prj.forecast (generated_at DESC);
