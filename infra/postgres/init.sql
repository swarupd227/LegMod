-- Postgres bootstrap. Runs once on the first container start (init scripts in
-- /docker-entrypoint-initdb.d/ only execute when the data directory is empty).
--
-- Responsibilities are intentionally narrow:
--   1. Create the application + Temporal databases.
--   2. Inside the application database, create the per-service schemas
--      (each Spring service owns its own schema) and shared extensions.
--   3. Seed the default workspace so the dashboard is non-empty on first boot.
--
-- All table DDL lives in per-service Flyway migrations under
-- services/<name>/src/main/resources/db/migration/. Spring services run
-- Flyway against their schema on startup.

CREATE DATABASE temporal;
CREATE DATABASE temporal_visibility;

\c atlas

CREATE SCHEMA IF NOT EXISTS prj;
CREATE SCHEMA IF NOT EXISTS prov;
CREATE SCHEMA IF NOT EXISTS arch;
CREATE SCHEMA IF NOT EXISTS recon;
CREATE SCHEMA IF NOT EXISTS gen;
CREATE SCHEMA IF NOT EXISTS diff;
CREATE SCHEMA IF NOT EXISTS cap;
CREATE SCHEMA IF NOT EXISTS reports;
CREATE SCHEMA IF NOT EXISTS uplift;

-- Shared extensions — used by uuid_generate_v4() defaults across services.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Note: per-service tables and the default workspace seed row are owned by
-- the corresponding Spring service's Flyway migrations under
--   services/<name>/src/main/resources/db/migration/
-- which run automatically on service startup.
