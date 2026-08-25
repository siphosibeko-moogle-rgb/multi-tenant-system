package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.reporting.ReportDtos.DashboardSummary;
import com.example.inventory.reporting.ReportDtos.InventoryValuation;
import com.example.inventory.reporting.ReportDtos.SalesBucket;
import com.example.inventory.reporting.ReportDtos.TopProduct;
import com.example.inventory.reporting.ReportDtos.TopProductSummary;
import com.example.inventory.reporting.ReportDtos.TrendPoint;

/**
 * The read side of M8's four report endpoints.
 *
 * <p>Hand-written SQL over the RLS-bound pool, per CLAUDE.md §4 — "reporting and
 * sync use hand-written SQL, not JPA. Ledger aggregates through an ORM are slow
 * and unreadable." Every statement here is a {@code SELECT}. Nothing in this
 * package writes, and in particular nothing goes near {@code stock_movements}:
 * T5's single writer is still {@code StockLedgerService}.
 *
 * <p>No {@code WHERE tenant_id = ?} appears anywhere below. RLS already applies
 * one on this connection, and a second is the convenience clause T2 warns about
 * being mistaken for the defence.
 *
 * <h2>One definition of revenue, spliced into every query</h2>
 *
 * <p>{@link #NET_LINES} is the single SQL definition of a net sale line, and
 * every money figure the four endpoints report is derived from it. This is
 * deliberate structure rather than tidiness: M7 shipped two bugs where per
 * component tests each passed and the components disagreed with each other
 * (CLAUDE.md §5), the second being two endpoints answering "what is this
 * product's stock state" differently because their *inputs* had diverged. The
 * dashboard's {@code salesTotal} and a sales-summary bucket's {@code revenue}
 * are the same question asked twice, so they are computed from the same text.
 * {@code ReportAgreementHttpTest} still compares the live endpoints, because a
 * shared definition is a reason to expect agreement and not a proof of it.
 *
 * <p>Reasoning for what a "net sale line" includes and excludes — voids,
 * returns, tax, the cost snapshot — is in {@code docs/adr/reporting.md} §0 and
 * §1, not repeated here.
 *
 * <h2>Why the window bounds are converted back to timestamptz</h2>
 *
 * <p>Every window is expressed in the tenant's timezone, the same way
 * {@code DemandRollupJob} does it. But the bounds are turned back into
 * {@code timestamptz} before they are compared against {@code sales.sold_at}:
 * {@code WHERE (sold_at AT TIME ZONE z)::date >= …} is not sargable and cannot
 * use {@code sales_tenant_time_idx}, so it degrades to a full scan of the
 * tenant's entire sales history. The local date is still computed, but only for
 * the rows the index has already narrowed to. M8's own acceptance criterion is
 * "under ~500 ms against a tenant with 100k movements"; this is the difference.
 */
@Service
public class ReportQueries {

    /** The dashboard's trend window. Fixed, and deliberately not {@code period}. See ADR §3. */
    static final int TREND_DAYS = 14;

    /** How many top sellers the dashboard carries. See ADR §4. */
    static final int DASHBOARD_TOP_PRODUCTS = 5;

    /** {@code GET /reports/top-products} when the caller names no dates. */
    static final int TOP_PRODUCTS_DEFAULT_DAYS = 30;

    private final JdbcTemplate jdbc;

    public ReportQueries(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    // ==================================================================
    // Shared SQL
    // ==================================================================

    /**
     * The tenant's timezone, defaulting to UTC — identical to the rollup's, so
     * a report and the demand history beside it agree about where a day ends.
     */
    private static final String ZONE_CTE = """
            zone AS (
                SELECT COALESCE(
                           (SELECT timezone FROM tenants WHERE id = current_tenant_id()),
                           'UTC') AS tz
            )
            """;

    /**
     * A rolling window of {@code ?} days ending today, in the tenant's zone.
     *
     * <p>Takes one parameter: the number of days, today inclusive. Exposes
     * {@code tz}, {@code first_day}, {@code last_day} (both local dates) and
     * {@code start_ts} / {@code end_ts} (half-open timestamptz bounds).
     */
    private static final String ROLLING_BOUNDS_CTE = """
            bounds AS (
                SELECT z.tz,
                       (now() AT TIME ZONE z.tz)::date - (CAST(? AS int) - 1) AS first_day,
                       (now() AT TIME ZONE z.tz)::date                        AS last_day,
                       (((now() AT TIME ZONE z.tz)::date - (CAST(? AS int) - 1))::timestamp
                            AT TIME ZONE z.tz)                                AS start_ts,
                       (((now() AT TIME ZONE z.tz)::date + 1)::timestamp
                            AT TIME ZONE z.tz)                                AS end_ts
                FROM zone z
            )
            """;

    /** The same, for an explicit inclusive {@code from}/{@code to} pair. */
    private static final String EXPLICIT_BOUNDS_CTE = """
            bounds AS (
                SELECT z.tz,
                       CAST(? AS date)                                        AS first_day,
                       CAST(? AS date)                                        AS last_day,
                       (CAST(? AS date)::timestamp AT TIME ZONE z.tz)         AS start_ts,
                       ((CAST(? AS date) + 1)::timestamp AT TIME ZONE z.tz)   AS end_ts
                FROM zone z
            )
            """;

    /**
     * <strong>The one definition of a sale's contribution to a report.</strong>
     *
     * <p>Reads {@code bounds} and produces {@code net_lines} with one row per
     * {@code sale_items} row inside the window, carrying {@code net_quantity},
     * {@code net_revenue} and {@code net_cost}. Also leaves {@code in_window}
     * available, which is what {@code salesCount} counts.
     *
     * <p>Three things worth reading twice:
     *
     * <ul>
     * <li>{@code s.status <> 'voided'} <em>and</em> the returns netting both
     *     exclude a void, because V5 records a void as a {@code sale_returns}
     *     group covering everything outstanding. They cannot disagree, and the
     *     status filter is the cheaper of the two.
     * <li>The sale-level discount is <strong>prorated across the lines</strong>
     *     by each line's share of the sale. Without that, a sale-level discount
     *     would vanish from any per-product figure, and {@code /reports/top-products}
     *     would report a revenue the dashboard did not agree with.
     * <li>{@code net_quantity} is <strong>not clamped</strong> at zero. Returns
     *     are capped against the sale by {@code SaleService}, so a negative here
     *     means that cap has broken; a {@code GREATEST(…, 0)} would hide it.
     * </ul>
     *
     * <p>{@code unit_cost} is the M4 snapshot, never {@code products.cost_price}
     * — see ADR §1 for why a report that re-reads today's cost is not
     * reproducible. A null snapshot is treated as zero cost; the column is
     * nullable and {@code SaleService} always populates it, so this is only
     * reachable for rows written outside the service.
     */
    private static final String NET_LINES = """
            in_window AS (
                SELECT s.id,
                       s.location_id,
                       s.discount_amount,
                       (s.sold_at AT TIME ZONE b.tz)::date AS local_day
                FROM sales s
                CROSS JOIN bounds b
                WHERE s.status <> 'voided'
                  AND s.sold_at >= b.start_ts
                  AND s.sold_at <  b.end_ts
            ),
            priced AS (
                SELECT w.id AS sale_id,
                       w.local_day,
                       w.location_id,
                       w.discount_amount,
                       si.product_id,
                       si.quantity,
                       si.unit_cost,
                       si.line_total,
                       SUM(si.line_total) OVER (PARTITION BY w.id) AS sale_lines_total,
                       COALESCE((SELECT SUM(sr.quantity)
                                   FROM sale_returns sr
                                  WHERE sr.sale_id = w.id
                                    AND sr.product_id = si.product_id), 0) AS returned_qty
                FROM in_window w
                JOIN sale_items si ON si.sale_id = w.id
            ),
            net_lines AS (
                SELECT p.sale_id,
                       p.local_day,
                       p.location_id,
                       p.product_id,
                       (p.quantity - p.returned_qty) AS net_quantity,
                       round((p.line_total
                              - COALESCE(p.discount_amount * p.line_total
                                         / NULLIF(p.sale_lines_total, 0), 0))
                             * (p.quantity - p.returned_qty) / p.quantity, 2) AS net_revenue,
                       round(COALESCE(p.unit_cost, 0)
                             * (p.quantity - p.returned_qty), 2)              AS net_cost
                FROM priced p
            )
            """;

    // ==================================================================
    // GET /reports/dashboard
    // ==================================================================

    /**
     * @param periodDays the rolling window's length in days, today inclusive —
     *                   1, 7 or 30 (ADR §0)
     * @param period     the label to echo back
     */
    public DashboardSummary dashboard(String period, int periodDays) {
        SalesAggregate sales = salesAggregate(periodDays);

        return new DashboardSummary(
                period,
                sales.revenue(),
                sales.count(),
                sales.grossProfit(),
                currentInventoryCostValue(),
                stockStateCount("reorder"),
                stockStateCount("out_of_stock"),
                openRecommendationCount(),
                salesTrend(),
                dashboardTopProducts(periodDays));
    }

    private record SalesAggregate(BigDecimal revenue, long count, BigDecimal grossProfit) {
    }

    private static final String SALES_AGGREGATE_SQL = "WITH " + ZONE_CTE + ","
            + ROLLING_BOUNDS_CTE + "," + NET_LINES + """
            SELECT COALESCE(SUM(n.net_revenue), 0)                  AS revenue,
                   COALESCE(SUM(n.net_revenue - n.net_cost), 0)     AS gross_profit,
                   (SELECT count(*) FROM in_window)                 AS sales_count
            FROM net_lines n
            """;

    private SalesAggregate salesAggregate(int days) {
        return jdbc.queryForObject(SALES_AGGREGATE_SQL,
                (rs, n) -> new SalesAggregate(
                        money(rs.getBigDecimal("revenue")),
                        rs.getLong("sales_count"),
                        money(rs.getBigDecimal("gross_profit"))),
                days, days);
    }

    /**
     * Current stock at current cost — deliberately <em>not</em> a snapshot.
     *
     * <p>This is a statement about now: what is on the shelf and what it would
     * cost to replace. That makes today's {@code cost_price} the right price to
     * read, for the same reason the sale's snapshot is the right one in
     * {@link #NET_LINES}. They answer different questions. ADR §2.
     */
    private static final String CURRENT_COST_VALUE_SQL = """
            SELECT COALESCE(round(SUM(ps.quantity_on_hand * p.cost_price), 2), 0) AS value
            FROM product_stock ps
            JOIN products p ON p.id = ps.product_id
            WHERE p.deleted_at IS NULL AND p.is_active AND p.is_tracked
            """;

    private BigDecimal currentInventoryCostValue() {
        return money(jdbc.queryForObject(CURRENT_COST_VALUE_SQL, BigDecimal.class));
    }

    /**
     * Counted from {@code v_stock_status}, never from a second copy of its CASE.
     *
     * <p>Duplicating the expression here is exactly the bug M7 shipped: two
     * places computing "is this product low" agreed as expressions and disagreed
     * as endpoints. The dashboard's counts and {@code GET /inventory}'s rows now
     * cannot diverge, because there is one expression.
     */
    private long stockStateCount(String state) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM v_stock_status WHERE stock_state = ?",
                Long.class, state);
        return count == null ? 0L : count;
    }

    private long openRecommendationCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM reorder_recommendations WHERE status = 'open'",
                Long.class);
        return count == null ? 0L : count;
    }

    /**
     * The last {@link #TREND_DAYS} local days, zero-filled.
     *
     * <p>The calendar comes from {@code generate_series} and the sales are left
     * joined onto it, so a day with no sales is a zero rather than an absent
     * point. A chart that closes the gap draws a line between two non-adjacent
     * days and shows a trend that did not happen.
     */
    private static final String TREND_SQL = "WITH " + ZONE_CTE + ","
            + ROLLING_BOUNDS_CTE + "," + NET_LINES + """
            , calendar AS (
                SELECT generate_series(b.first_day, b.last_day, interval '1 day')::date AS day
                FROM bounds b
            )
            SELECT c.day, COALESCE(SUM(n.net_revenue), 0) AS revenue
            FROM calendar c
            LEFT JOIN net_lines n ON n.local_day = c.day
            GROUP BY c.day
            ORDER BY c.day
            """;

    private List<TrendPoint> salesTrend() {
        return jdbc.query(TREND_SQL,
                (rs, n) -> new TrendPoint(
                        rs.getObject("day", LocalDate.class),
                        money(rs.getBigDecimal("revenue"))),
                TREND_DAYS, TREND_DAYS);
    }

    /**
     * Top sellers by units, tie-broken by revenue then id so the order is stable.
     *
     * <p>{@code HAVING SUM(net_quantity) > 0} drops a product whose sales all
     * came back — it did not sell, and a "top products" list that leads with one
     * is worse than a shorter list. ADR §4 has the argument for units over
     * revenue as the ranking key.
     */
    private static final String DASHBOARD_TOP_SQL = "WITH " + ZONE_CTE + ","
            + ROLLING_BOUNDS_CTE + "," + NET_LINES + """
            SELECT n.product_id,
                   p.name,
                   SUM(n.net_quantity) AS units_sold,
                   SUM(n.net_revenue)  AS revenue
            FROM net_lines n
            JOIN products p ON p.id = n.product_id
            GROUP BY n.product_id, p.name
            HAVING SUM(n.net_quantity) > 0
            ORDER BY units_sold DESC, revenue DESC, n.product_id
            LIMIT ?
            """;

    private List<TopProductSummary> dashboardTopProducts(int days) {
        return jdbc.query(DASHBOARD_TOP_SQL,
                (rs, n) -> new TopProductSummary(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("name"),
                        quantity(rs.getBigDecimal("units_sold"))),
                days, days, DASHBOARD_TOP_PRODUCTS);
    }

    // ==================================================================
    // GET /reports/sales-summary
    // ==================================================================

    /**
     * Buckets over an explicit inclusive date range.
     *
     * <p>{@code groupBy} is spliced as a literal rather than bound, because
     * {@code date_trunc}'s unit and {@code generate_series}' step are not
     * parameters a driver can bind. It is validated against a closed set in
     * {@link ReportsController} before it reaches here and re-checked below, so
     * there is no path from a request to this string that is not one of three
     * constants — the same reason {@code normaliseStatus} exists rather than a
     * cast straight from the query parameter.
     *
     * <p>Empty buckets are emitted, for the same reason the trend is zero-filled.
     */
    public List<SalesBucket> salesSummary(LocalDate from, LocalDate to, String groupBy) {
        String unit = switch (groupBy) {
            case "day", "week", "month" -> groupBy;
            default -> throw new IllegalArgumentException("unvalidated groupBy: " + groupBy);
        };

        String sql = "WITH " + ZONE_CTE + "," + EXPLICIT_BOUNDS_CTE + "," + NET_LINES + """
                , calendar AS (
                    SELECT generate_series(
                               date_trunc('%1$s', b.first_day::timestamp),
                               date_trunc('%1$s', b.last_day::timestamp),
                               interval '1 %1$s')::date AS period_start
                    FROM bounds b
                ),
                bucketed AS (
                    SELECT date_trunc('%1$s', n.local_day::timestamp)::date AS period_start,
                           n.net_quantity,
                           n.net_revenue,
                           n.net_cost
                    FROM net_lines n
                )
                SELECT c.period_start,
                       COALESCE(SUM(k.net_quantity), 0)                AS units_sold,
                       COALESCE(SUM(k.net_revenue), 0)                 AS revenue,
                       COALESCE(SUM(k.net_revenue - k.net_cost), 0)    AS gross_profit
                FROM calendar c
                LEFT JOIN bucketed k ON k.period_start = c.period_start
                GROUP BY c.period_start
                ORDER BY c.period_start
                """.formatted(unit);

        return jdbc.query(sql,
                (rs, n) -> new SalesBucket(
                        rs.getObject("period_start", LocalDate.class),
                        quantity(rs.getBigDecimal("units_sold")),
                        money(rs.getBigDecimal("revenue")),
                        money(rs.getBigDecimal("gross_profit"))),
                from, to, from, to);
    }

    // ==================================================================
    // GET /reports/inventory-valuation
    // ==================================================================

    /**
     * What the stock on hand is worth, now or at a past date.
     *
     * <p>Two query shapes, not one. Valuing <em>today</em> reads
     * {@code product_stock}, which is O(products); valuing a <em>past</em> date
     * replays {@code stock_movements}, which is O(movements). Always replaying
     * would be correct and needlessly expensive on the call the dashboard makes
     * on every screen open.
     *
     * <p>They must agree when {@code asOf} is today, and
     * {@code ValuationReplayHttpTest} asserts exactly that — otherwise the
     * branch is two implementations of one number, which is the failure mode
     * CLAUDE.md §5 keeps finding.
     *
     * <p>The replay is real arithmetic, not an approximation: the ledger is
     * append-only (T4) and {@code product_stock} is its running fold (T5), so
     * summing {@code quantity_delta} up to a point <em>is</em> the balance at
     * that point. What is <strong>not</strong> replayed is the price — the
     * schema keeps no price history, so a past valuation prices past quantities
     * at today's cost. ADR §2 says why that was not fixed here, and the
     * endpoint's contract description says it to the caller.
     */
    public InventoryValuation inventoryValuation(UUID locationId, LocalDate asOf, boolean replay) {
        // The location filter is added as a clause rather than bound as a
        // nullable parameter. `WHERE (CAST(? AS uuid) IS NULL OR col = ?)` reads
        // fine and costs an index: the planner cannot use one for a predicate
        // whose truth depends on a parameter it must evaluate per row. Two
        // shapes, one of which is simply absent, keeps both plans clean.
        String filter = locationId == null ? "" : "  AND %s.location_id = ?\n";
        List<Object> args = new ArrayList<>();
        args.add(asOf);

        String perProduct;
        if (replay) {
            perProduct = """
                    per_product AS (
                        SELECT m.product_id, SUM(m.quantity_delta) AS qty
                        FROM stock_movements m
                        CROSS JOIN bounds b
                        WHERE m.occurred_at < b.end_ts
                    """ + filter.formatted("m") + """
                        GROUP BY m.product_id
                    )
                    """;
        } else {
            perProduct = """
                    per_product AS (
                        SELECT ps.product_id, SUM(ps.quantity_on_hand) AS qty
                        FROM product_stock ps
                        WHERE true
                    """ + filter.formatted("ps") + """
                        GROUP BY ps.product_id
                    )
                    """;
        }
        if (locationId != null) {
            args.add(locationId);
        }

        String sql = "WITH " + ZONE_CTE + "," + """
                bounds AS (
                    SELECT z.tz,
                           ((CAST(? AS date) + 1)::timestamp AT TIME ZONE z.tz) AS end_ts
                    FROM zone z
                ),
                """ + perProduct + """
                SELECT COALESCE(round(SUM(pp.qty * p.cost_price), 2), 0)    AS cost_value,
                       COALESCE(round(SUM(pp.qty * p.selling_price), 2), 0) AS retail_value,
                       count(*) FILTER (WHERE pp.qty <> 0)                  AS product_count
                FROM per_product pp
                JOIN products p ON p.id = pp.product_id
                WHERE p.deleted_at IS NULL AND p.is_active AND p.is_tracked
                """;

        return jdbc.queryForObject(sql,
                (rs, n) -> new InventoryValuation(
                        money(rs.getBigDecimal("cost_value")),
                        money(rs.getBigDecimal("retail_value")),
                        rs.getLong("product_count"),
                        asOf),
                args.toArray());
    }

    /** Today, in the tenant's timezone — so the caller never has to guess. */
    public LocalDate todayForTenant() {
        return jdbc.queryForObject("WITH " + ZONE_CTE + """
                SELECT (now() AT TIME ZONE z.tz)::date AS today FROM zone z
                """, LocalDate.class);
    }

    // ==================================================================
    // GET /reports/top-products
    // ==================================================================

    /**
     * Best or worst movers over a window, ranked by units sold.
     *
     * <p>{@code worst} left-joins the catalogue so that a product which sold
     * <strong>nothing</strong> appears with {@code unitsSold: 0}. It is the
     * worst mover, and an aggregate over {@code sale_items} cannot see it —
     * omitting it would mean "worst movers" silently excluded the actual worst,
     * which is the same class of error as a test that passes because it sees no
     * rows. {@code best} filters those out instead: there is no reason to pad a
     * best-sellers list with zeroes.
     *
     * <p>{@code worst} is additionally restricted to tracked products. An
     * untracked product is a service; it has no shelf to sit on, so "not moving"
     * says nothing about it. {@code best} includes them, because a service can
     * genuinely be a top seller.
     *
     * <p>{@code order} reaches the SQL as one of two constants, validated in the
     * controller and re-checked here.
     */
    public List<TopProduct> topProducts(LocalDate from, LocalDate to, String order, int limit) {
        boolean best = switch (order) {
            case "best" -> true;
            case "worst" -> false;
            default -> throw new IllegalArgumentException("unvalidated order: " + order);
        };

        String sql = "WITH " + ZONE_CTE + "," + EXPLICIT_BOUNDS_CTE + "," + NET_LINES + """
                , agg AS (
                    SELECT n.product_id,
                           SUM(n.net_quantity) AS units_sold,
                           SUM(n.net_revenue)  AS revenue,
                           SUM(n.net_cost)     AS cost
                    FROM net_lines n
                    GROUP BY n.product_id
                )
                SELECT p.id AS product_id,
                       p.sku,
                       p.name,
                       COALESCE(a.units_sold, 0) AS units_sold,
                       COALESCE(a.revenue, 0)    AS revenue,
                       CASE WHEN COALESCE(a.revenue, 0) = 0 THEN NULL
                            ELSE round((a.revenue - a.cost) * 100 / a.revenue, 2)
                       END AS gross_margin_pct
                FROM products p
                LEFT JOIN agg a ON a.product_id = p.id
                WHERE p.deleted_at IS NULL AND p.is_active
                """
                + (best
                        ? "  AND COALESCE(a.units_sold, 0) > 0\n"
                          + "ORDER BY units_sold DESC, revenue DESC, p.id\n"
                        : "  AND p.is_tracked\n"
                          + "ORDER BY units_sold ASC, revenue ASC, p.id\n")
                + "LIMIT ?";

        return jdbc.query(sql,
                (rs, n) -> new TopProduct(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        quantity(rs.getBigDecimal("units_sold")),
                        money(rs.getBigDecimal("revenue")),
                        rs.getBigDecimal("gross_margin_pct")),
                from, to, from, to, limit);
    }

    // ==================================================================

    /**
     * Two decimals, always, and never null.
     *
     * <p>A money field that arrives as {@code 12.5} rather than {@code 12.50}
     * deserializes fine and then renders as "R12.5" on a receipt screen; the
     * scale is fixed here rather than in the client. Null cannot reach a caller:
     * every money field on these responses is in the contract's
     * {@code required} list, and §15's whole point is that a required-and-null
     * field breaks a generated client at the constructor.
     */
    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Quantities are {@code numeric(14,3)} throughout the schema; match it. */
    private static BigDecimal quantity(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(3, RoundingMode.HALF_UP);
    }
}
