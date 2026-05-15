-- V3__build_run_sandbox_status.sql
--
-- Expand the build_run.status check constraint to include
-- 'sandbox_limited' - the new status BuildTestRunner emits when
-- Maven cannot reach its dependency repository. Keeps the existing
-- rows valid (none used the new value yet).

ALTER TABLE gen.build_run DROP CONSTRAINT IF EXISTS build_run_status_check;
ALTER TABLE gen.build_run
    ADD CONSTRAINT build_run_status_check
    CHECK (status IN ('running','passed','compile_failed','tests_failed','sandbox_limited','error'));
