-- M4: the record of what has been returned against a sale.
--
-- ---------------------------------------------------------------------
-- Why this table has to exist, when the ledger already records movements
-- ---------------------------------------------------------------------
--
-- The obvious design is to have no table at all: a return posts a `sale_return`
-- movement referencing the sale, so "how much of this sale has come back" is a
-- SUM over `stock_movements`. That is how the rest of this system answers stock
-- questions, and it would be the right answer but for one requirement.
--
-- A return of DAMAGED goods posts no movement. The goods are not going back on
-- the shelf, so there is nothing for the ledger to say — and a zero-quantity row
-- is not an option either: `stock_movements` has CHECK (quantity_delta <> 0),
-- and a zero row would also pollute the demand history M7 trains on with
-- transactions that never moved anything.
--
-- So for damaged returns the ledger is deliberately silent, and a ledger-derived
-- cap would not see them. The consequence is not a missing report — it is a
-- double refund: return two units as damaged (no movement), then return the same
-- two again asking for restock, and a ledger-derived check finds nothing already
-- returned and allows it. The customer is paid twice and two units appear on the
-- shelf that were never there.
--
-- This table is therefore the record of the COMMERCIAL event, and the ledger
-- stays the record of the PHYSICAL one. They deliberately disagree for damaged
-- goods, because the two things genuinely differ: money came back, stock did
-- not.
--
-- A void is recorded here too. A void is a return of everything outstanding, so
-- giving it a different representation would mean two ways of asking the same
-- question and two chances to get it wrong.

CREATE TABLE sale_returns (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sale_id           uuid NOT NULL,
    product_id        uuid NOT NULL,

    -- Positive, always. Direction is carried by the movement type the ledger
    -- writes, not by a sign here — the same reasoning as transfers, where there
    -- is no such thing as a negative transfer, only one the other way.
    quantity          numeric(14,3) NOT NULL CHECK (quantity > 0),

    -- false = damaged goods: refunded but not restocked, and NO ledger movement.
    -- Recorded here precisely because the ledger will not record it.
    restocked         boolean NOT NULL,

    -- Ties the lines of one request together, and distinguishes a void from an
    -- ordinary return. A void is one group covering everything outstanding.
    return_group_id   uuid NOT NULL,
    is_void           boolean NOT NULL DEFAULT false,

    reason            text,
    created_by        uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),

    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id)    REFERENCES sales    (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES users    (tenant_id, id)
);

-- The hot query: "how much of this sale, per product, has already come back?"
CREATE INDEX sale_returns_sale_idx  ON sale_returns (tenant_id, sale_id, product_id);
CREATE INDEX sale_returns_group_idx ON sale_returns (tenant_id, return_group_id);

-- ---------------------------------------------------------------------
-- RLS — same shape as every other tenant-scoped table (T2, T11)
-- ---------------------------------------------------------------------
--
-- FORCE as well as ENABLE: without FORCE the table's owner bypasses the policy
-- silently, and an isolation failure does not throw, it returns rows.
ALTER TABLE sale_returns ENABLE ROW LEVEL SECURITY;
ALTER TABLE sale_returns FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON sale_returns
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- V2 granted the app role on ALL TABLES at the time it ran, and its ALTER
-- DEFAULT PRIVILEGES covers tables created later by the same role. This grant is
-- explicit anyway: relying on a default privilege to have been set by an earlier
-- migration is the kind of assumption that is true until someone runs a
-- migration as a different role.
GRANT SELECT, INSERT, UPDATE, DELETE ON sale_returns TO inventory_app;

-- Deliberately NOT granted to inventory_login. That role reaches `tenants` and
-- `users` and nothing else, and a new tenant-scoped table must stay invisible to
-- it by default (CLAUDE.md §11). LoginRoleTest asserts it cannot read this.
