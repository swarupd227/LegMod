-- Test postgres bootstrap. Mirrors the production init.sql for the bits that
-- the uplift schema actually needs: uuid_generate_v4() defaults and the
-- per-service schema. Flyway creates the uplift schema on its own, but tables
-- in V1+ depend on these extensions.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
