-- M8: the change feed behind GET /sync/changes.
--
-- =====================================================================
-- THE CHANGE-TOKEN STRATEGY, AND WHY IT IS NOT A TIMESTAMP
-- =====================================================================
--
-- The obvious implementation of "everything that changed since X" is
-- `WHERE updated_at > X`, and it is wrong in three separate ways. All three
-- fail in the direction this system cares about least: silently.
--
-- 1. TWO WRITES IN ONE TICK. `now()` is the TRANSACTION timestamp, so every row
--    a batch touches shares it exactly (this is the same property that made
--    V4 add a version column rather than derive an ETag from updated_at). A
--    client that syncs at exactly that timestamp and stores it must either
--    re-fetch those rows forever (`>=`) or skip the ones it has not seen (`>`).
--
-- 2. IT CAN LOOP FOREVER. With `>=` and a page limit, a tenant whose writes are
--    dense enough that one timestamp fills a page never advances: every sync
--    returns the same page, the watermark never moves, and the client burns
--    battery re-reading the same rows. Nothing errors.
--
-- 3. CLOCKS MOVE BACKWARDS. NTP corrections, leap smearing and a restored
--    replica all rewind `now()`. Rows written during the rewound interval sort
--    before a watermark the client already holds and are never delivered.
--
-- So the token is NOT wall-clock time.
--
-- ---------------------------------------------------------------------
-- What it is instead: transaction ids, with a snapshot boundary
-- ---------------------------------------------------------------------
--
-- Every logged change records `pg_current_xact_id()` — a 64-bit, monotonic,
-- non-wrapping transaction id. That fixes (1) and (3): it is a logical clock,
-- it never ties, and it never goes backwards.
--
-- It does NOT by itself fix the subtler problem, and this is the one worth
-- understanding because a plain sequence has it too:
--
--    GAP: transaction A takes id 100. Transaction B takes id 101. B COMMITS
--    FIRST. A sync running in between sees max(id) = 101, hands the client a
--    token of 101, and returns B's row. Then A commits. A's row has id 100,
--    which is BELOW the client's watermark, so it is never sent. The row is
--    lost forever, and nothing anywhere reports an error.
--
-- This is the real hazard, it is entirely invisible, and it is why "use a
-- monotonic sequence" is necessary but not sufficient.
--
-- The fix is to refuse to hand out a watermark past any transaction that might
-- still be in flight. `pg_snapshot_xmin(pg_current_snapshot())` is exactly that
-- number: the oldest transaction still running. Every transaction below it has
-- already committed or aborted, so its rows are final and visible.
--
--    boundary := pg_snapshot_xmin(pg_current_snapshot())
--    return rows where since <= xact_id < boundary
--    new token := boundary
--
-- In the gap scenario the boundary is 100 (A is still running), so the sync
-- returns nothing above 100, hands back a token of 100, and B's row waits. When
-- A commits, the next sync's boundary rises past both and delivers them in id
-- order. Nothing is skipped and nothing is duplicated.
--
-- The interval is HALF-OPEN — [since, boundary) — and the next `since` is
-- exactly the previous `boundary`. That is what makes two sequential syncs
-- never return the same row twice: the ranges tile the id space without
-- overlapping.
--
-- COST OF THE GUARANTEE, stated so nobody is surprised by it: a long-running
-- transaction holds xmin down and stalls the feed for every tenant until it
-- ends. Sync returns fewer rows rather than wrong ones, and catches up
-- afterwards. That is the safe direction, and it is the same trade a
-- long-running transaction already imposes on autovacuum.
--
-- ---------------------------------------------------------------------
-- Why this table holds the LATEST change per entity, not every change
-- ---------------------------------------------------------------------
--
-- One row per (tenant, entity_type, entity_id), upserted. A client
-- synchronising a cache wants the current state of what changed, never the
-- history of how it got there — so a product edited fifty times is one row.
-- The alternative grows without bound and needs a retention job that would
-- silently break the feed for anyone offline longer than the retention window.
--
-- The consequence to be aware of: this table is a CURRENT-STATE index, so a
-- client that has been offline for a year and one offline for a minute are
-- served by the same mechanism, and neither can be given a change that has
-- since been superseded. That is correct for a cache and would be wrong for an
-- audit trail. `audit_log` is the audit trail; this is not.

CREATE SEQUENCE change_log_seq;

CREATE TABLE change_log (
    tenant_id     uuid    NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    entity_type   text    NOT NULL,
    entity_id     uuid    NOT NULL,

    -- The logical clock. bigint rather than xid8 so JDBC reads it as a number
    -- without a type mapping; the cast through text is the supported route.
    xact_id       bigint  NOT NULL,

    -- Orders changes made by the SAME transaction, which all share xact_id.
    -- Without it a page boundary inside one transaction's changes could not be
    -- resumed: the cursor is (xact_id, seq), and (xact_id) alone is not unique.
    seq           bigint  NOT NULL DEFAULT nextval('change_log_seq'),

    -- A tombstone. Covers both a hard DELETE and a soft one (deleted_at set),
    -- because a client cannot tell the difference and should not have to.
    deleted       boolean NOT NULL DEFAULT false,

    changed_at    timestamptz NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, entity_type, entity_id)
);

-- The feed's only access path: one tenant, an id range, in order.
CREATE INDEX change_log_feed_idx ON change_log (tenant_id, xact_id, seq);

-- ---------------------------------------------------------------------
-- RLS — same shape as every other tenant-scoped table (T2, T11)
-- ---------------------------------------------------------------------
--
-- FORCE as well as ENABLE: without FORCE the owner bypasses the policy
-- silently, and an isolation failure does not throw, it returns rows.
--
-- This table is added to all three of T11's sweeps in the same change that
-- creates it — TenantIsolationTest, SchemaSmokeTest and LoginRoleTest — because
-- a table that misses them breaks nothing and stays uncovered until someone
-- reads another tenant's row in production.
ALTER TABLE change_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE change_log FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON change_log
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ---------------------------------------------------------------------
-- The trigger that maintains it
-- ---------------------------------------------------------------------
--
-- One generic function, parameterised by entity type and id column, so adding
-- a synced table is one CREATE TRIGGER and not a new copy of this logic.
--
-- AFTER, and RETURN NULL: this must never influence the write it observes. A
-- BEFORE trigger returning the wrong thing would silently cancel a row.
CREATE OR REPLACE FUNCTION record_change() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_entity_type text := TG_ARGV[0];
    v_id_column   text := TG_ARGV[1];
    v_row         jsonb;
    v_deleted     boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_row := to_jsonb(OLD);
        v_deleted := true;
    ELSE
        v_row := to_jsonb(NEW);
        -- Soft delete reads as a delete to a client. `->>` yields NULL both for
        -- a column that does not exist on this table and for one that is NULL,
        -- so this is safe on tables with no deleted_at at all.
        v_deleted := COALESCE(v_row ->> 'deleted_at', '') <> '';
    END IF;

    INSERT INTO change_log (tenant_id, entity_type, entity_id, xact_id, seq, deleted)
    VALUES ((v_row ->> 'tenant_id')::uuid,
            v_entity_type,
            (v_row ->> v_id_column)::uuid,
            pg_current_xact_id()::text::bigint,
            nextval('change_log_seq'),
            v_deleted)
    ON CONFLICT (tenant_id, entity_type, entity_id) DO UPDATE
        SET xact_id    = EXCLUDED.xact_id,
            seq        = EXCLUDED.seq,
            deleted    = EXCLUDED.deleted,
            changed_at = now();

    RETURN NULL;
END $$;

-- The five entity types GET /sync/changes carries.
--
-- `stock` is keyed on product_id rather than on the (product, location) pair
-- that product_stock actually has, because the contract's `deleted` tombstone
-- carries a single uuid and there is nowhere to put the second half. The feed
-- therefore returns every location's row for a product whose stock moved
-- anywhere. That over-fetches for a multi-location tenant and is correct;
-- keying on half a composite and hoping would not be.
CREATE TRIGGER trg_products_changed
    AFTER INSERT OR UPDATE OR DELETE ON products
    FOR EACH ROW EXECUTE FUNCTION record_change('product', 'id');

CREATE TRIGGER trg_categories_changed
    AFTER INSERT OR UPDATE OR DELETE ON categories
    FOR EACH ROW EXECUTE FUNCTION record_change('category', 'id');

CREATE TRIGGER trg_locations_changed
    AFTER INSERT OR UPDATE OR DELETE ON locations
    FOR EACH ROW EXECUTE FUNCTION record_change('location', 'id');

CREATE TRIGGER trg_suppliers_changed
    AFTER INSERT OR UPDATE OR DELETE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION record_change('supplier', 'id');

CREATE TRIGGER trg_product_stock_changed
    AFTER INSERT OR UPDATE OR DELETE ON product_stock
    FOR EACH ROW EXECUTE FUNCTION record_change('stock', 'product_id');

-- ---------------------------------------------------------------------
-- Backfill
-- ---------------------------------------------------------------------
--
-- Without this, a first sync (`since` omitted) would return only what has
-- changed since the migration ran, and every tenant created before it would
-- appear to have an empty catalogue. A client cannot detect that: an empty
-- feed and an empty business look identical.
--
-- Backfilled rows all share one xact_id — this migration's — which is exactly
-- right. They became visible to the feed at the same instant, and any client
-- syncing afterwards is above that id and unaffected.
INSERT INTO change_log (tenant_id, entity_type, entity_id, xact_id, deleted)
SELECT tenant_id, 'product', id, pg_current_xact_id()::text::bigint,
       deleted_at IS NOT NULL
FROM products
ON CONFLICT DO NOTHING;

INSERT INTO change_log (tenant_id, entity_type, entity_id, xact_id, deleted)
SELECT tenant_id, 'category', id, pg_current_xact_id()::text::bigint, false
FROM categories
ON CONFLICT DO NOTHING;

INSERT INTO change_log (tenant_id, entity_type, entity_id, xact_id, deleted)
SELECT tenant_id, 'location', id, pg_current_xact_id()::text::bigint, false
FROM locations
ON CONFLICT DO NOTHING;

INSERT INTO change_log (tenant_id, entity_type, entity_id, xact_id, deleted)
SELECT tenant_id, 'supplier', id, pg_current_xact_id()::text::bigint, false
FROM suppliers
ON CONFLICT DO NOTHING;

INSERT INTO change_log (tenant_id, entity_type, entity_id, xact_id, deleted)
SELECT DISTINCT tenant_id, 'stock', product_id, pg_current_xact_id()::text::bigint, false
FROM product_stock
ON CONFLICT DO NOTHING;
