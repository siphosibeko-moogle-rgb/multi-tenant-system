-- Repeatable migration: views, replaced in place.
--
-- CLAUDE.md T9 says views live here precisely so they can be changed without a
-- new versioned migration each time. `v_stock_status` was defined in V1 instead,
-- which meant the first change to it had nowhere to go: V1 is applied and must
-- not be edited. This file takes ownership of the view. Flyway runs repeatable
-- migrations after the versioned ones and re-runs this whenever its checksum
-- changes, so V1's definition is created first and then replaced by this one.
--
-- CREATE OR REPLACE VIEW is safe here because the column list, their order and
-- their types are unchanged — only the CASE expression's output differs. It also
-- preserves the grants V2 made, which DROP + CREATE would silently discard.

-- ---------------------------------------------------------------------
-- v_stock_status
-- ---------------------------------------------------------------------
--
-- WHAT CHANGED: a product with no reorder point is now `ok`, not `unknown`.
--
-- The old CASE asked "is there a threshold?" before asking "how are we doing
-- against it?", so a missing threshold produced `unknown`. Nothing sets a
-- reorder point today — `products.reorder_point` is null by default and the
-- forecaster that would supply `f.reorder_point` is M7 — which made `unknown`
-- the state of essentially every product in every new business. Emulator
-- verification found a product sitting at quantity 10 labelled "unknown", which
-- is not an edge case: it is what every shop owner sees on day one.
--
-- The distinction the old version was reaching for is real but it was reported
-- in the wrong direction. Not knowing whether stock is LOW is not the same as
-- not knowing what the stock IS. We know exactly what it is: ten, on the shelf,
-- and nobody has said what "low" means for this product. That is `ok` — there is
-- no evidence of a problem — and it is the honest answer rather than a hedge.
--
-- `unknown` is deliberately left in the contract's enum and is now unreachable
-- from this view. That is the point: it is reserved for a state we genuinely
-- cannot determine, so that if something ever does produce it, it means what it
-- says. A value that fires for "nobody configured this yet" is a value a client
-- learns to ignore, and then it cannot warn about anything.
--
-- Mirrored by ProductCatalog.stockState for the single-product reads, which does
-- not go through this view. The two must agree; StockStateTest asserts they do.
CREATE OR REPLACE VIEW v_stock_status AS
SELECT
    ps.tenant_id,
    ps.product_id,
    ps.location_id,
    p.sku,
    p.name,
    ps.quantity_on_hand,
    ps.quantity_available,
    COALESCE(p.reorder_point, f.reorder_point)          AS effective_reorder_point,
    f.avg_daily_demand,
    f.days_of_cover,
    f.projected_stockout_on,
    CASE
        -- Nothing on the shelf outranks every other consideration.
        WHEN ps.quantity_on_hand <= 0                                        THEN 'out_of_stock'
        -- No threshold set: there is stock and no rule it is failing.
        WHEN COALESCE(p.reorder_point, f.reorder_point) IS NULL              THEN 'ok'
        WHEN ps.quantity_available <= COALESCE(p.reorder_point, f.reorder_point) THEN 'reorder'
        ELSE 'ok'
    END AS stock_state
FROM product_stock ps
JOIN products p
     ON p.tenant_id = ps.tenant_id AND p.id = ps.product_id
LEFT JOIN forecasts f
     ON f.tenant_id = ps.tenant_id
    AND f.product_id = ps.product_id
    AND f.location_id = ps.location_id
    AND f.is_current
WHERE p.deleted_at IS NULL AND p.is_tracked;
