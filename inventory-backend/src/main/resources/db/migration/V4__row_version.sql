-- =====================================================================
-- V4 — a row version for optimistic locking.
--
-- M2 gave products ETag / If-Match, deriving the version from updated_at
-- because there was no version column. That works for one endpoint and stops
-- working the moment anything writes several rows in one transaction:
-- PostgreSQL's now() is the TRANSACTION start time, so a batch touching ten
-- products stamps all ten with an identical updated_at and therefore an
-- identical ETag. Two different rows would then share a version, and a client
-- holding one could satisfy If-Match for the other.
--
-- M4's sales and M7's forecast jobs both write across the catalogue in single
-- transactions, so this is a "when", not an "if". Doing it now, while exactly
-- one endpoint consumes it, costs one migration and one WHERE clause.
--
-- Which tables get a version, and why only these:
--
--   products   — PATCH /products/{productId}, the endpoint that has If-Match
--   suppliers  — PATCH /suppliers/{supplierId} (M5)
--   users      — PATCH /users/{userId}, already live in M1
--
-- These are the three row-level entities the contract lets a person edit in
-- place. Deliberately NOT included:
--
--   product_stock          already has a version, maintained by
--                          apply_stock_movement; a second increment here would
--                          double-count it
--   stock_movements        append-only (T4). A row that can never be updated
--                          cannot have a stale update
--   categories, locations  no edit endpoint exists yet. Add the column in the
--                          same change that adds the endpoint, rather than
--                          speculating about a shape nobody has designed
--   sales, purchase_orders their concurrency control is a status transition
--                          guarded by its own preconditions (M4/M5), which is a
--                          different mechanism from a row version. Adding a
--                          column now would prejudge that design
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. The bump
--
-- A trigger rather than leaving it to each UPDATE statement. The application
-- compare-and-swaps on the version; if it also had to remember to increment it,
-- a writer that forgot would leave the version frozen and every subsequent
-- If-Match would pass forever — a failure that looks exactly like working
-- software. Here, any UPDATE through any code path advances it.
--
-- Same reasoning as T12: put the guarantee where it cannot be bypassed by
-- someone who did not know it was there.
-- ---------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bump_row_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.version := OLD.version + 1;
    RETURN NEW;
END $$;

-- ---------------------------------------------------------------------
-- 2. The columns and their triggers
--
-- DEFAULT 0 and NOT NULL, so existing rows get a usable version without a
-- backfill and no reader has to handle null.
--
-- Trigger naming matters more than it looks: PostgreSQL fires BEFORE ROW
-- triggers in alphabetical order, so trg_<table>_updated runs before
-- trg_<table>_version. They touch different columns of NEW, so the order is
-- harmless — but it is the kind of thing that stops being harmless silently, so
-- it is worth having written down.
-- ---------------------------------------------------------------------

ALTER TABLE products  ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE suppliers ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE users     ADD COLUMN version bigint NOT NULL DEFAULT 0;

CREATE TRIGGER trg_products_version BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION bump_row_version();

CREATE TRIGGER trg_suppliers_version BEFORE UPDATE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION bump_row_version();

CREATE TRIGGER trg_users_version BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION bump_row_version();

-- ---------------------------------------------------------------------
-- 3. No new tables
--
-- T11 concerns tables, and this migration adds none — it adds a column to three
-- tables that V1 created and that are already in TENANT_SCOPED_TABLES in both
-- TenantIsolationTest and SchemaSmokeTest, and in FORBIDDEN_TABLES in
-- LoginRoleTest (except users, which the login role is deliberately granted
-- SELECT on). Nothing to add to those lists.
--
-- Stated explicitly because "no entries needed" and "T11 was skipped" look
-- identical in a diff.
-- ---------------------------------------------------------------------
