-- Add a trace_id column so audit entries can deep-link to the
-- distributed-tracing UI (Jaeger / Tempo / etc.). Populated from the
-- caller's MDC traceId when present; null for entries written outside
-- a span (e.g. background tasks that pre-date Phase 2C.3).

ALTER TABLE prov.entry ADD COLUMN IF NOT EXISTS trace_id TEXT;
CREATE INDEX IF NOT EXISTS idx_prov_trace_id ON prov.entry(trace_id) WHERE trace_id IS NOT NULL;
