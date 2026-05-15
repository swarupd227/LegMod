-- ----------------------------------------------------------------------------
-- Phase 2K follow-up: seed workspace memberships for the fake-idp demo
-- personas.
--
-- The Envestnet workspace seeded in V2 is owned by dev@envestnet.local.
-- V3's backfill added dev@envestnet.local as the OWNER member. But the
-- fake-idp instance shipped in docker-compose only offers three
-- personas to pick from at /login:
--     alice@envestnet.local   (ENGINEER)
--     bob@envestnet.local     (ENGINEER, TECH_LEAD)
--     carol@envestnet.local   (ENGINEER, TECH_LEAD, ADMIN)
--
-- None of those has a membership row, so a freshly-installed demo
-- stack lands the user on an empty workspace list with a 403 on every
-- write — Phase 2K's hide-existence semantics doing exactly what
-- they're designed to do, but for the wrong demographic.
--
-- This migration grants all three personas OWNER on the Envestnet
-- workspace so the demo runs without manual SQL. The JWT-level role
-- tier (ENGINEER / TECH_LEAD / ADMIN) still gates the finalize
-- endpoints in SecurityConfig — workspace-level OWNER just means
-- "can see and mutate projects in this workspace."
--
-- For production deployments these rows are no-ops (the personas
-- don't exist in real IdPs). The ON CONFLICT clause keeps the
-- migration idempotent in case it ever runs against a database that
-- already has some of them.
-- ----------------------------------------------------------------------------

INSERT INTO prj.workspace_member (workspace_id, user_email, role, granted_by)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'alice@envestnet.local', 'OWNER', 'migration:V5'),
    ('00000000-0000-0000-0000-000000000001', 'bob@envestnet.local',   'OWNER', 'migration:V5'),
    ('00000000-0000-0000-0000-000000000001', 'carol@envestnet.local', 'OWNER', 'migration:V5')
ON CONFLICT (workspace_id, user_email) DO NOTHING;
