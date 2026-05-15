-- V2__build_run.sql
--
-- Compile + Test gate for Stage F. After the generated/migrated code
-- is ready, Atlas spawns a sandboxed JVM that compiles the source and
-- runs whatever unit tests exist. The customer sees: "Atlas built
-- your migrated code and ran your tests. 142 pass. 0 fail." This
-- closes the enterprise-readiness gap — a customer can sign off on
-- the migration because Atlas has actually proven the new code works,
-- not just that it generated.

CREATE TABLE IF NOT EXISTS gen.build_run (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id      UUID NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    -- Overall status of the build+test cycle.
    --   running   : sandbox is currently building/testing
    --   passed    : compile clean AND tests all pass (or no tests)
    --   compile_failed : javac/mvn compile produced errors
    --   tests_failed   : compile passed but at least one test failed
    --   error     : sandbox failed to launch / unknown failure
    status          TEXT NOT NULL DEFAULT 'running'
                       CHECK (status IN ('running','passed','compile_failed','tests_failed','error')),
    -- Track for which the gate ran (SOAP javac vs UPLIFT mvn test).
    track           TEXT NOT NULL,
    -- Counts surfaced to the SPA + closure document.
    files_compiled  INT NOT NULL DEFAULT 0,
    compile_errors  INT NOT NULL DEFAULT 0,
    compile_warnings INT NOT NULL DEFAULT 0,
    tests_total     INT NOT NULL DEFAULT 0,
    tests_passed    INT NOT NULL DEFAULT 0,
    tests_failed    INT NOT NULL DEFAULT 0,
    tests_skipped   INT NOT NULL DEFAULT 0,
    duration_ms     BIGINT,
    -- Full compile + test output for the engineer to drill into. Capped
    -- at ~100KB by the service-side truncator to avoid runaway rows.
    log_text        TEXT NOT NULL DEFAULT '',
    -- Structured failures so the SPA can render a list of (test, error)
    -- pairs without parsing raw log text.
    failures        JSONB NOT NULL DEFAULT '[]'::jsonb,
    summary         JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS idx_build_run_project ON gen.build_run(project_id, started_at DESC);
