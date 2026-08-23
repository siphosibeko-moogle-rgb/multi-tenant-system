package com.example.inventory.forecasting;

import java.time.LocalDate;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.inventory.tenancy.TenantContext;

/**
 * Rolls {@code stock_movements} up into {@code demand_daily} — the training set
 * every forecast in M7 reads.
 *
 * <p>This is the first thing that has ever populated that table. M6 seeded ~7
 * months of real history and deliberately verified its two demand claims one
 * level lower, against the ledger, because nothing existed to compute
 * {@code units_sold} or {@code had_stockout} from it. This is that thing.
 *
 * <h2>Scope: the caller's tenant, and only the caller's tenant</h2>
 *
 * <p>This class never enumerates tenants and never binds one. It reads and
 * writes through the ordinary RLS-bound application pool, so it sees exactly
 * what {@code app.tenant_id} allows and nothing else — CLAUDE.md §12's rule
 * that no code path may bind a tenant from a non-token source applies to
 * background work too, and a job that looped over {@code tenants} would be
 * exactly the violation that rule names.
 *
 * <p>The consequence is deliberate: <strong>whoever calls this must already be
 * bound</strong>, which today means a request thread ({@code POST
 * /forecasts/recompute}) or a test. Scheduling a rollup across every tenant
 * without a request is a real design question — it needs either a per-tenant
 * service credential or a second, documented exception to T1 — and it is not
 * answered here. Raising it beats quietly widening T1 to make a cron job
 * convenient.
 *
 * <h2>The day boundary is the tenant's, not the server's</h2>
 *
 * <p>{@code tenants.timezone} exists for this and V1's own comment says so
 * ("daily rollups use this"). A movement at 23:00 in Johannesburg belongs to
 * that shop's Tuesday, not to UTC's Wednesday, and getting this wrong shifts a
 * slice of every evening's demand onto the following day — which looks like
 * noise rather than like a bug.
 *
 * <h2>What counts as demand</h2>
 *
 * <p>{@code units_sold} is <strong>{@code sale} movements minus {@code
 * sale_return} movements, floored at zero per day</strong>.
 *
 * <p>The ADR is silent on how a return nets out, so the reading is stated here
 * and in {@code V9}'s comment on the column rather than left to be rediscovered:
 * a unit that was sold and then handed back and put on the shelf was never real
 * demand. Counting it would overstate {@code avg_daily_demand}, which walks
 * straight into the reorder point (ADR §1) and makes the business over-order.
 *
 * <p>The ledger gets this right almost by accident, and the accident is worth
 * naming because it is load-bearing: <strong>a damaged-goods return posts no
 * movement at all</strong> (V5 — "refunded but not restocked, and NO ledger
 * movement"). So reading the ledger nets restocked returns and ignores
 * non-restocked ones, which is precisely the wanted behaviour in both
 * directions. A unit refunded but scrapped really was sold; the customer wanted
 * it, the shelf lost it, and demand should still count it.
 *
 * <p>The floor at zero matters because a return carries no backdated
 * {@code occurred_at} — {@code SaleService} posts it at {@code now()}, because
 * that is when the goods physically came back. So a large return can land on a
 * quiet day and exceed it. Demand is not negative; the day is a zero.
 *
 * <h2>Zero-demand days are rows, not gaps</h2>
 *
 * <p>A plain {@code GROUP BY} over movements emits a row only for days that had
 * one, which silently deletes every zero-demand day. That inflates the average
 * for exactly the slow movers whose averages matter most: a product selling on
 * 1 day in 10 would report an average of its selling days and read as ten times
 * more popular than it is. V1's own comment on the table warns about it and M6
 * hit the same class of bug twice from the other direction.
 *
 * <p>So the day range comes from {@code generate_series} over the product's
 * whole calendar span, not from the movements. The span starts at the
 * product/location's <em>first movement of any kind</em> — the day it was first
 * stocked. Before that there is no shelf to sell from, and a zero row would
 * assert an absence of demand for a product nobody could have bought.
 *
 * <h2>{@code had_stockout}: at zero for any part of the day</h2>
 *
 * <p>Flagged when the on-hand balance was at or below zero at any instant during
 * the day, at or after that first movement. Two cases, and the second is the one
 * a movements-only query cannot see:
 *
 * <ul>
 *   <li>the balance touched zero <em>within</em> the day — the last unit sold at
 *       noon, and the shelf sat empty until closing;</li>
 *   <li>the day <em>carried in</em> an empty shelf and had no movements at all.
 *       A refused sale writes nothing (T12 — the refusal is the answer), so an
 *       outage is an absence of rows. Reconstructing the running balance across
 *       the day series is the only way to see it.</li>
 * </ul>
 *
 * <p>This is distinct from a merely quiet day, which is the distinction ADR §3
 * rests on: a flagged day's {@code units_sold} is a floor, not a count, and gets
 * excluded from both the average and the spread. A quiet day is real evidence of
 * low demand and stays in.
 */
@Service
public class DemandRollupJob {

    private final JdbcTemplate jdbc;

    public DemandRollupJob(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /** What one rollup run did, for logging and for tests to assert on. */
    public record RollupResult(int daysWritten) {
    }

    /**
     * Rolls up every product/location the bound tenant can see, from each one's
     * first movement through today in the tenant's own timezone.
     */
    public RollupResult rollUp() {
        return rollUp(null);
    }

    /**
     * @param through last day to roll up, inclusive; {@code null} means today in
     *                the tenant's timezone. Passing it explicitly is what lets a
     *                test assert over a fixed range instead of racing the clock.
     */
    @Transactional
    public RollupResult rollUp(LocalDate through) {
        requireBoundTenant();
        int written = jdbc.update(ROLLUP_SQL, through);
        return new RollupResult(written);
    }

    private void requireBoundTenant() {
        TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException(
                "DemandRollupJob requires a bound tenant. It deliberately does not bind one "
                        + "itself — see the class Javadoc and CLAUDE.md section 12."));
    }

    /**
     * One statement, deliberately.
     *
     * <p>Hand-written per CLAUDE.md §4: this is a ledger aggregate with a running
     * balance and a generated calendar, and an ORM expresses none of that
     * readably. The {@code ON CONFLICT} makes it idempotent — re-running over the
     * same range must produce the same table, which is what lets a test run it
     * twice and assert nothing moved.
     *
     * <p>{@code current_tenant_id()} appears only in the INSERT's target column.
     * Every read below is already narrowed by RLS on the same connection, so
     * there is no {@code WHERE tenant_id = ?} to forget or to get wrong.
     */
    private static final String ROLLUP_SQL = """
            WITH params AS (
                SELECT COALESCE(
                           (SELECT timezone FROM tenants WHERE id = current_tenant_id()),
                           'UTC') AS tz
            ),
            bounds AS (
                SELECT tz,
                       COALESCE(CAST(? AS date), (now() AT TIME ZONE tz)::date) AS through
                FROM params
            ),
            mv AS (
                SELECT m.product_id,
                       m.location_id,
                       (m.occurred_at AT TIME ZONE b.tz)::date AS day,
                       m.occurred_at,
                       m.id,
                       m.movement_type,
                       m.quantity_delta,
                       m.reference_id
                FROM stock_movements m
                CROSS JOIN bounds b
                WHERE (m.occurred_at AT TIME ZONE b.tz)::date <= b.through
            ),
            -- Balance after each movement, in business-time order. The tiebreak
            -- on id matters: several movements of one sale share an occurred_at,
            -- and an unstable order would make the within-day minimum wobble.
            running AS (
                SELECT mv.*,
                       SUM(quantity_delta) OVER (
                           PARTITION BY product_id, location_id
                           ORDER BY occurred_at, id
                           ROWS UNBOUNDED PRECEDING) AS balance_after
                FROM mv
            ),
            per_day AS (
                SELECT r.product_id,
                       r.location_id,
                       r.day,
                       SUM(r.quantity_delta)  AS delta,
                       MIN(r.balance_after)   AS min_balance_within,
                       -- Net demand: sales out, restocked returns back in. A
                       -- damaged-goods return is absent from the ledger by
                       -- design (V5) and so is correctly absent from both.
                       SUM(CASE WHEN r.movement_type = 'sale'
                                THEN -r.quantity_delta ELSE 0 END)
                     - SUM(CASE WHEN r.movement_type = 'sale_return'
                                THEN  r.quantity_delta ELSE 0 END) AS net_units,
                       COUNT(DISTINCT CASE WHEN r.movement_type = 'sale'
                                           THEN r.reference_id END) AS sales_count,
                       COALESCE(SUM(
                           CASE WHEN r.movement_type = 'sale'
                                THEN -r.quantity_delta * si.unit_price
                                WHEN r.movement_type = 'sale_return'
                                THEN  r.quantity_delta * si.unit_price * -1
                                ELSE 0 END), 0) AS revenue
                FROM running r
                LEFT JOIN sale_items si
                       ON si.sale_id    = r.reference_id
                      AND si.product_id = r.product_id
                GROUP BY r.product_id, r.location_id, r.day
            ),
            span AS (
                SELECT product_id, location_id, MIN(day) AS first_day
                FROM mv
                GROUP BY product_id, location_id
            ),
            -- The calendar, not the movements. This is what keeps zero-demand
            -- days in the table instead of silently dropping them.
            calendar AS (
                SELECT s.product_id,
                       s.location_id,
                       s.first_day,
                       d::date AS day
                FROM span s
                CROSS JOIN bounds b
                CROSS JOIN LATERAL generate_series(s.first_day, b.through, interval '1 day') d
            ),
            filled AS (
                SELECT c.product_id,
                       c.location_id,
                       c.day,
                       c.first_day,
                       COALESCE(p.delta, 0)       AS delta,
                       p.min_balance_within,
                       COALESCE(p.net_units, 0)   AS net_units,
                       COALESCE(p.sales_count, 0) AS sales_count,
                       COALESCE(p.revenue, 0)     AS revenue
                FROM calendar c
                LEFT JOIN per_day p
                       ON p.product_id  = c.product_id
                      AND p.location_id = c.location_id
                      AND p.day         = c.day
            ),
            balanced AS (
                SELECT f.*,
                       COALESCE(SUM(delta) OVER (
                           PARTITION BY product_id, location_id
                           ORDER BY day
                           ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS opening
                FROM filled f
            )
            INSERT INTO demand_daily
                (tenant_id, product_id, location_id, day,
                 units_sold, sales_count, revenue, had_stockout)
            SELECT current_tenant_id(),
                   product_id,
                   location_id,
                   day,
                   -- Floored: a return landing on a quiet day cannot make
                   -- demand negative, and the column's CHECK says so too.
                   GREATEST(net_units, 0),
                   sales_count,
                   revenue,
                   -- Empty for part of the day: either carried in empty (an
                   -- outage day has no movements at all, because a refused sale
                   -- writes nothing) or driven to zero within the day.
                   (day > first_day AND opening <= 0)
                       OR (min_balance_within IS NOT NULL AND min_balance_within <= 0)
            FROM balanced
            ON CONFLICT (tenant_id, product_id, location_id, day) DO UPDATE
                SET units_sold   = EXCLUDED.units_sold,
                    sales_count  = EXCLUDED.sales_count,
                    revenue      = EXCLUDED.revenue,
                    had_stockout = EXCLUDED.had_stockout
            """;
}
