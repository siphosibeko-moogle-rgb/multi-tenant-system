-- =====================================================================
-- V3 — the login / tenant-resolution role.
--
-- The problem this solves, stated in section 6 of V2:
--
--   Login happens BEFORE app.tenant_id is known. The user presents an email
--   and a password; which tenant they belong to is the ANSWER, not the input.
--   But V1 put FORCE ROW LEVEL SECURITY on both tenants and users, with
--   policies keyed on current_tenant_id(). On an RLS-bound connection with
--   nothing bound, current_tenant_id() is NULL, `tenant_id = NULL` is NULL,
--   and the lookup sees zero rows. Login can never succeed.
--
-- The tempting fixes are both wrong:
--
--   * BYPASSRLS on the app role — turns every policy in the schema into a
--     no-op for the whole application, not just for login (CLAUDE.md T2).
--   * Relaxing the tenants/users policies to allow unbound reads — every
--     tenant-scoped read in the product then leaks by default.
--
-- Instead: a third role that can do exactly one thing, and per-role policies
-- that grant the unscoped read to THAT ROLE ONLY. PostgreSQL ORs permissive
-- policies together, but only those whose role list matches the current role,
-- so login_read below is invisible to inventory_app and changes nothing about
-- what the application can see.
--
-- Read privileges and policies as two independent gates. A policy cannot grant
-- access to a table the role has no privilege on, and a privilege cannot show
-- rows a policy filters out. This role is deliberately fenced by both.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. The role
--
-- NOINHERIT and no role membership anywhere: inventory_login must not be able
-- to reach inventory_app's privileges by any path. Roles are cluster-wide, so
-- as in V2 this tolerates the role already existing.
-- ---------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_login') THEN
        CREATE ROLE inventory_login
            LOGIN
            PASSWORD '${loginUserPassword}'
            NOSUPERUSER
            NOBYPASSRLS
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT;
    ELSE
        -- Same reasoning as V2: keep an existing role in the intended shape.
        -- NOBYPASSRLS is the attribute that matters most — this role reads
        -- users unscoped, so BYPASSRLS on it would expose every table.
        ALTER ROLE inventory_login
            LOGIN
            PASSWORD '${loginUserPassword}'
            NOSUPERUSER
            NOBYPASSRLS
            NOCREATEDB
            NOCREATEROLE
            NOINHERIT;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 2. Connect and schema usage
-- ---------------------------------------------------------------------

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO inventory_login', current_database());
END $$;

GRANT USAGE ON SCHEMA public TO inventory_login;

-- ---------------------------------------------------------------------
-- 3. Privileges — SELECT on exactly two tables
--
-- No INSERT, UPDATE or DELETE anywhere. No grants on any other table. No
-- sequence grants: this role never inserts, so it never needs a nextval, and
-- a sequence grant would be a quiet signal that someone expected it to write.
--
-- Note what is NOT here: V2's ALTER DEFAULT PRIVILEGES names inventory_app
-- only, so tables created by future migrations are unreachable to this role
-- by default. That default is the safe direction — a new table is invisible to
-- login until someone deliberately grants it, rather than the reverse.
-- ---------------------------------------------------------------------

GRANT SELECT ON tenants TO inventory_login;
GRANT SELECT ON users   TO inventory_login;

-- ---------------------------------------------------------------------
-- 4. Policies — FOR SELECT, TO inventory_login, and nothing wider
--
-- FOR SELECT is load-bearing twice over. It scopes the policy to reads, and
-- it means the policy contributes no WITH CHECK clause at all, so it cannot
-- be used to satisfy the write side of anything. Even if this role were
-- granted INSERT by mistake in some later migration, the only applicable
-- WITH CHECK would still be tenant_isolation / tenant_self, i.e.
-- tenant_id = current_tenant_id(), which is NULL on an unbound connection and
-- therefore rejects the row.
--
-- USING (true) is intentional and is the whole point: the lookup must see all
-- tenants and all users in order to answer "which tenant does this email
-- belong to?". The containment comes from the role list and from section 3,
-- not from the predicate.
-- ---------------------------------------------------------------------

CREATE POLICY login_read ON tenants
    FOR SELECT
    TO inventory_login
    USING (true);

CREATE POLICY login_read ON users
    FOR SELECT
    TO inventory_login
    USING (true);

-- ---------------------------------------------------------------------
-- 5. Registration is NOT this role's job
--
-- POST /auth/register-tenant writes a tenants row and its first users row.
-- That does not run here, and it does not run as the owner either. It runs on
-- the ordinary application pool with app.tenant_id bound to the UUID being
-- created, which the service generates before opening the transaction.
--
-- Both policies then accept the insert on their own terms:
--     tenants.tenant_self     WITH CHECK (id        = current_tenant_id())
--     users.tenant_isolation  WITH CHECK (tenant_id = current_tenant_id())
--
-- So a registration transaction can create the tenant it declared and users
-- inside that tenant, and is refused anything else — by RLS, not by review.
-- Running it as the owner would mean a superuser-capable connection in the
-- request path with every policy disabled, which is the one thing T2 forbids;
-- a fourth write-capable role would need those same WITH CHECK clauses
-- satisfied anyway, so it would add a credential without adding a guarantee.
--
-- The consequence to remember: registration cannot read tenants to pre-check
-- whether a slug is taken — bound to a brand-new tenant id, it sees nothing.
-- The UNIQUE constraint on tenants.slug is enforced physically, independent of
-- RLS, so a duplicate surfaces as a unique violation to be mapped to 409.
-- ---------------------------------------------------------------------
