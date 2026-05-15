-- Default workspace so the dashboard isn't empty on first boot. Idempotent —
-- ON CONFLICT keeps re-applies safe when this migration is replayed against
-- a postgres that already has the row.

INSERT INTO prj.workspace (id, name, owner_email)
VALUES ('00000000-0000-0000-0000-000000000001', 'Envestnet', 'dev@envestnet.local')
ON CONFLICT (id) DO NOTHING;
