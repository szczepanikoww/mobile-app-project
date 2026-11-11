-- The API connects as a DML-only role (database-design §4): it can read and write rows, but cannot drop the
-- forbid_update triggers, constraints or tables. Flyway runs as the owner role. Re-applied whenever this file changes;
-- tables added by later migrations are covered because the grants are on ALL TABLES of each schema.
DO $$
DECLARE s text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') THEN
        RAISE NOTICE 'role ${appRole} does not exist; skipping grants';
        RETURN;
    END IF;
    FOREACH s IN ARRAY ARRAY['identity', 'catalogue', 'coaching', 'scheduling', 'training', 'messaging', 'media', 'billing', 'platform']
    LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', s, '${appRole}');
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', s, '${appRole}');
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', s, '${appRole}');
    END LOOP;
END $$;
