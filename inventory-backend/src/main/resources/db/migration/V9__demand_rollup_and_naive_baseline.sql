-- M7. Two things that had to be settled before any forecast could be written
-- down: what a demand_daily row means when a customer hands stock back, and how
-- the naive baseline is represented so it can be compared against.
--
-- No new tables. demand_daily, forecasts, forecast_accuracy and
-- reorder_recommendations were all created by V1, are all already covered by
-- T11's three sweeps, and none of them needed a column added here.

-- ---------------------------------------------------------------------
-- 1. The naive "same as last period" baseline gets its own method value
--
-- docs/adr/forecasting.md §7 left the representation open and sketched two
-- shapes: (a) a synthetic forecast row per evaluation carrying a new `naive`
-- forecast_method, scored through the existing forecast_accuracy table like any
-- other method, or (b) sibling columns (naive_predicted_qty,
-- naive_abs_pct_error) on forecast_accuracy.
--
-- Resolved in favour of (a), and the ADR has been updated to say so rather than
-- leaving the decision recorded only here.
--
-- The reason for a NEW value rather than reusing moving_average — which is the
-- tempting shortcut, since a 1-period moving average and "same as last period"
-- compute the same number — is that the comparison stops meaning anything the
-- moment the two share a label. The whole point of ADR §7 is to be able to ask
-- "is the chosen method beating the dumbest possible forecast?" and get a
-- mechanical answer. If both sides of that question are stored as
-- moving_average, the query that asks it cannot separate them, and the finding
-- the evaluation exists to surface — a method that beats naive for steady
-- sellers and loses for intermittent ones — becomes unreadable.
--
-- IF NOT EXISTS because this is safe to reapply; PostgreSQL 12+ permits
-- ALTER TYPE ... ADD VALUE inside a transaction provided the new value is not
-- used in that same transaction, which nothing here does.
ALTER TYPE forecast_method ADD VALUE IF NOT EXISTS 'naive';

-- ---------------------------------------------------------------------
-- 2. What units_sold means when stock comes back
--
-- The ADR is silent on this specific case, so the reading is recorded on the
-- column itself. A future reader who checks the ADR will not find it there, and
-- would otherwise assume the ADR covered it.
COMMENT ON COLUMN demand_daily.units_sold IS
$$Net demand for the day: 'sale' movements minus 'sale_return' movements,
floored at zero.

A unit that was sold and then handed back and put on the shelf was never real
demand. Counting it would overstate avg_daily_demand, which feeds the reorder
point directly (docs/adr/forecasting.md §1), so the business would be told to
over-order on the strength of a sale that did not stick.

The ledger draws the right line here without a special case, and the reason is
load-bearing rather than incidental: a damaged-goods return posts NO ledger
movement at all (see V5) — it is refunded but not restocked, and sale_returns is
the only record of it. So netting over stock_movements subtracts restocked
returns and ignores non-restocked ones, which is correct in both directions. A
unit refunded but scrapped genuinely was demanded: the customer wanted it and
the shelf lost it.

Floored at zero because a return is posted at the moment the goods physically
come back, not backdated to the original sale, so a large return can land on a
quiet day and exceed it. Demand is not negative.

docs/adr/forecasting.md §3's had_stockout exclusion is a separate question and
is answered by the had_stockout column, not by this one.$$;

COMMENT ON COLUMN demand_daily.had_stockout IS
$$True when the product's on-hand balance at this location was at or below zero
at any instant during the day, at or after its first ever movement.

Covers both the day that carried in an empty shelf — an outage has no movements
at all, because a refused sale writes nothing (CLAUDE.md T12) — and the day whose
last unit sold at noon and sat empty until closing. Both mean units_sold is a
floor rather than a count, which is what docs/adr/forecasting.md §3 excludes from
the demand average and spread.

Deliberately NOT set for a merely quiet day. A day with stock on the shelf and no
sales is real evidence of low demand and stays in the eligible set; conflating
the two is the censored-demand bug §3 exists to prevent.$$;

COMMENT ON TABLE demand_daily IS
$$Daily demand rollup in the tenant's timezone (tenants.timezone), populated by
DemandRollupJob from stock_movements. Idempotent: re-running over the same range
replaces the rows rather than accumulating.

Zero-demand days are rows, not gaps. The day range is generated over each
product/location's calendar span from its first movement, not derived from the
days that happen to have movements — a GROUP BY over movements silently omits
every zero-demand day and inflates the average badly for slow movers.$$;
