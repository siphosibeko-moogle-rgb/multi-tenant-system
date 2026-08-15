-- =====================================================================
-- V2 — the application database role.
--
-- V1 turned on FORCE ROW LEVEL SECURITY and installed a tenant_isolation
-- policy on every tenant-scoped table, but section 11 of V1 left the role
-- itself as a comment. This migration creates it for real.
--
-- Two roles, deliberately:
--
--   * the OWNER (POSTGRES_USER / the Testcontainers admin user) owns the
--     schema and is the only role Flyway connects as. It runs DDL.
--   * the APP role below is what the running application connects as. It is
--     NOSUPERUSER and NOBYPASSRLS, because either of those silently turns
--     every policy from V1 into a no-op (CLAUDE.md T2).
--
-- The password is supplied by the Flyway placeholder ${appUserPassword} and is
-- never written into this file. See application.yml.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. The role
--
-- Roles are cluster-wide, not database-wide, so this has to tolerate the
-- role already existing: dropping and recreating the database inside the
-- same cluster would otherwise fail the migration on a plain CREATE ROLE.
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_app') THEN
        CREATE ROLE inventory_app
            LOGIN
            PASSWORD '${appUserPassword}'
            NOSUPERUSER
            NOBYPASSRLS
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT;
    ELSE
        -- Keep an existing role in the intended shape. NOBYPASSRLS is the
        -- line that matters: a role granted BYPASSRLS at any point would see
        -- every tenant's rows.
        ALTER ROLE inventory_app
            LOGIN
            PASSWORD '${appUserPassword}'
            NOSUPERUSER
            NOBYPASSRLS
            NOCREATEDB
            NOCREATEROLE;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 2. Connect and schema usage
-- ---------------------------------------------------------------------

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO inventory_app', current_database());
END $$;

GRANT USAGE ON SCHEMA public TO inventory_app;

-- ---------------------------------------------------------------------
-- 3. Table and sequence privileges
--
-- Broad DML on everything, then the ledger exception below. RLS decides
-- which ROWS are visible; these grants decide which TABLES are reachable.
-- Both are needed — a grant is not a substitute for a policy.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO inventory_app;

-- Identity columns (stock_movements.id, forecasts.id, audit_log.id, ...) need
-- sequence usage or every INSERT fails.
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO inventory_app;

-- Future migrations create tables as the owner. Without this, M1+ would have to
-- remember to re-grant every time, and forgetting fails at runtime rather than
-- at migration time.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO inventory_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO inventory_app;

-- ---------------------------------------------------------------------
-- 4. The append-only ledger
--
-- CLAUDE.md T4: stock_movements takes inserts and nothing else. V1 already
-- installs a trigger that raises on UPDATE/DELETE; this revoke means the
-- application cannot even attempt one. Defence in depth — the trigger is the
-- correctness guarantee, this is the permission guarantee.
-- ---------------------------------------------------------------------

REVOKE UPDATE, DELETE ON stock_movements FROM inventory_app;

-- ---------------------------------------------------------------------
-- 5. Flyway's own bookkeeping
--
-- Step 3 granted DML on ALL TABLES, which swept up flyway_schema_history.
-- The application has no business rewriting migration history, so take the
-- write privileges back and leave SELECT for diagnostics.
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM inventory_app;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 6. Note for M1
--
-- Login and tenant resolution happen BEFORE app.tenant_id is known, so they
-- cannot run on an RLS-bound connection: the tenants and users policies from
-- V1 would hide the very rows the lookup needs. M1 adds a second, narrowly
-- scoped role for that path (SELECT on tenants + users only) rather than
-- relaxing anything here.
-- ---------------------------------------------------------------------
