-- M8: idempotency for stock adjustments.
--
-- =====================================================================
-- WHY THIS EXISTS
-- =====================================================================
--
-- CLAUDE.md §4: "any endpoint that moves stock or money accepts
-- Idempotency-Key / clientRequestId. A replay returns the original result."
--
-- POST /sales has honoured that since M4, backed by V1's sales_idempotency_uq.
-- POST /inventory/adjustments never has. It is the other endpoint that moves
-- stock, and it had no replay protection at all — the convention was stated and
-- half-implemented, which is worse than either alternative because the stated
-- convention is what a reader checks instead of the code.
--
-- It did not matter while adjustments were made by a person tapping a button on
-- a connected phone. It matters the moment M8's offline outbox can replay one:
-- a queued adjustment whose response is lost to a dropped connection gets sent
-- again, and without a key the second attempt is a SECOND stock movement. The
-- ledger is append-only (T4), so the correction is another compensating row
-- somebody must first notice is needed — exactly the "corrupting stock" outcome
-- the offline work exists to prevent.
--
-- The client must NOT solve this with a second mechanism of its own. There is
-- one idempotency story in this system and this migration extends it to the
-- endpoint that was missing it.
--
-- ---------------------------------------------------------------------
-- Why the column goes on stock_movements
-- ---------------------------------------------------------------------
--
-- Because that is the row an adjustment creates, and the guarantee wanted is
-- "this movement exists at most once". Putting it anywhere else would mean a
-- key that is unique in one table while the row it guards lives in another,
-- which is the shape that admits a window where one exists without the other.
--
-- Adding a column is not editing an applied migration (T9) and does not weaken
-- T4: the ledger stays append-only, and the immutability trigger still refuses
-- UPDATE and DELETE on every row including these.

ALTER TABLE stock_movements
    ADD COLUMN client_request_id uuid;

COMMENT ON COLUMN stock_movements.client_request_id IS
    'Idempotency key, supplied by the client at capture time. Partial-unique '
    'per tenant. Null for movements with no client behind them (sales lines, '
    'receipts, transfers), which are made idempotent by their own parent row.';

-- Partial, so the overwhelming majority of movements — which carry no key —
-- cost nothing and do not collide with each other on NULL.
--
-- Per tenant, not global: two businesses can independently generate the same
-- UUID, and a global index would make one of them fail a legitimate write. The
-- same reasoning as sales_idempotency_uq, and as the per-tenant SKU indexes.
CREATE UNIQUE INDEX stock_movements_idempotency_uq
    ON stock_movements (tenant_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
