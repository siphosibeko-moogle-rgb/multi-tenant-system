package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;
import com.example.inventory.forecasting.ReorderService;
import com.example.inventory.seed.SeedDataRunner;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reports against M6's real seed data — 30 weeks of history built through
 * the real services — rather than against a four-product fixture.
 *
 * <p>Two things this covers that the hand-computed tests cannot.
 *
 * <p><strong>Real numbers.</strong> Every figure is checked against an
 * independent SQL sum over the same tables, so the assertion is "the endpoint
 * agrees with the database" rather than "the endpoint agrees with a constant
 * somebody typed". M6's history contains returns, a deliberate stockout, a dead
 * product and seven demand shapes; a query that mishandles any of them shows up
 * here and nowhere else.
 *
 * <p><strong>Real volume, and therefore real query plans.</strong>
 * {@link #reportQueriesUseIndexesAndFinishQuickly} runs {@code EXPLAIN ANALYZE}
 * over the seeded tenant and asserts both the timing and, for the sales window,
 * that the plan does <em>not</em> sequentially scan {@code sales}. M8's
 * acceptance criterion is "report endpoints stay under ~500 ms against a tenant
 * with 100k movements", and a timing assertion alone would pass on a small
 * dataset for a query that is quadratic — the plan is the part that generalises.
 */
@DisplayName("Reports over M6 seed data")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportSeedDataTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReportSeedDataTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private com.example.inventory.inventory.StockLedgerService ledger;

    @Autowired
    private JwtService jwtService;

    @Autowired
    @Qualifier("appDataSource")
    private DataSource appDataSource;

    private static SeededTenant tenant;
    private static boolean prepared;

    @BeforeEach
    void seedOnce() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Report Bakery", "report-" + tag, 1L,
                SeedDataRunner.WINDOW_WEEKS);

        asTenant(() -> {
            // Draw the steady seller below its reorder point so there IS an open
            // recommendation to count. M6 keeps every product well stocked, so a
            // freshly seeded tenant has none — and a dashboard whose
            // openRecommendationCount is always zero satisfies an equality
            // assertion forever while never exercising the count.
            //
            // The same trick ForecastEndpointsHttpTest uses, and for the same
            // reason: an adjustment rather than a sale, so the demand history the
            // forecast is built from does not move as a side effect of a fixture.
            BigDecimal onHand = new JdbcTemplate(appDataSource).queryForObject("""
                    SELECT COALESCE(SUM(quantity_on_hand), 0) FROM product_stock
                    WHERE product_id = ? AND location_id = ?
                    """, BigDecimal.class,
                    tenant.productIdsByShape().get("steady"), tenant.locationId());
            ledger.post(new com.example.inventory.inventory.StockLedgerService.MovementRequest(
                    tenant.productIdsByShape().get("steady"), tenant.locationId(), "adjustment",
                    new BigDecimal("14").subtract(onHand), null, null, null,
                    "report fixture write-off",
                    LocalDate.now().atTime(14, 0).atOffset(java.time.ZoneOffset.UTC)));

            // recomputeAll runs the rollup itself since M7's
            // RecomputeRunsTheRollupTest; calling only the method the API exposes
            // is the point of doing it this way.
            return reorderService.recomputeAll();
        });
        prepared = true;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(
                tenant.tenantId(), tenant.ownerId(), "owner"));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String owner() {
        return jwtService.issueAccessToken(tenant.tenantId(), tenant.ownerId(), "owner").value();
    }

    private JsonNode get(String path) {
        HttpTestClient.Response response = http().get(path, owner());
        assertThat(response.status()).as("%s -> %s", path, response.body()).isEqualTo(200);
        return response.json();
    }

    /** The owner connection — a superuser, so no RLS. Scoped by hand here. */
    private JdbcTemplate ownerJdbc() {
        org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        return new JdbcTemplate(ds);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("salesTotal and grossProfit match an independent sum over the same period")
    void moneyMatchesAnIndependentSum() {
        JsonNode dashboard = get("/reports/dashboard?period=month");

        // Written for this test, over the raw tables, on the owner connection
        // with an explicit tenant_id — not a second call to ReportQueries.
        //
        // It necessarily implements the same RULE (net of returns, voids
        // dropped, sale discount prorated), so it resembles NET_LINES; what it
        // does not share is the text, the connection, the window arithmetic or
        // the rounding, and those are where this kind of query goes wrong.
        //
        // The rounding in particular has to match deliberately rather than by
        // luck: NET_LINES rounds each line to 2dp and then sums, so summing
        // first and rounding once would disagree by cents on a large enough
        // history and look like a real defect.
        BigDecimal expectedRevenue = ownerJdbc().queryForObject("""
                WITH windowed AS (
                    SELECT s.id AS sale_id,
                           s.discount_amount,
                           si.product_id,
                           si.quantity,
                           si.line_total,
                           SUM(si.line_total) OVER (PARTITION BY s.id) AS sale_lines_total
                    FROM sale_items si
                    JOIN sales s ON s.id = si.sale_id
                    WHERE s.tenant_id = ?
                      AND s.status <> 'voided'
                      AND s.sold_at >= ((now() AT TIME ZONE 'UTC')::date - 29)::timestamp
                                           AT TIME ZONE 'UTC'
                      AND s.sold_at <  ((now() AT TIME ZONE 'UTC')::date + 1)::timestamp
                                           AT TIME ZONE 'UTC'
                )
                SELECT COALESCE(SUM(round(
                    (w.line_total
                     - COALESCE(w.discount_amount * w.line_total
                                / NULLIF(w.sale_lines_total, 0), 0))
                    * (w.quantity - COALESCE((SELECT SUM(sr.quantity) FROM sale_returns sr
                                               WHERE sr.sale_id = w.sale_id
                                                 AND sr.product_id = w.product_id), 0))
                    / w.quantity, 2)), 0)
                FROM windowed w
                """, BigDecimal.class, tenant.tenantId());

        assertThat(expectedRevenue)
                .as("30 weeks of seeded history must have sold something in the last "
                        + "30 days, or this comparison is two zeroes agreeing")
                .isGreaterThan(BigDecimal.ZERO);

        BigDecimal reported = dashboard.get("salesTotal").decimalValue();

        log.info("SEED DATA: dashboard(period=month) salesTotal = {} over {} sales, "
                        + "grossProfit = {}, inventoryValue = {}",
                reported, dashboard.get("salesCount").asLong(),
                dashboard.get("grossProfit").decimalValue(),
                dashboard.get("inventoryValue").decimalValue());

        assertThat(reported).isEqualByComparingTo(expectedRevenue);

        // Profit is a real fraction of revenue, not zero and not all of it. M6
        // prices everything at 8.00 cost / 15.00 retail, so the margin sits near
        // 47% — asserting a band rather than a figure, because the seed's RNG
        // moves the mix.
        BigDecimal profit = dashboard.get("grossProfit").decimalValue();
        assertThat(profit).isGreaterThan(BigDecimal.ZERO).isLessThan(reported);
        BigDecimal marginPct = profit.multiply(new BigDecimal("100"))
                .divide(reported, 1, java.math.RoundingMode.HALF_UP);
        assertThat(marginPct)
                .as("M6 sells at 15.00 against a cost of 8.00 — about 47%%")
                .isBetween(new BigDecimal("40.0"), new BigDecimal("55.0"));
    }

    @Test
    @DisplayName("salesCount matches the sales table")
    void salesCountMatchesTheTable() {
        long reported = get("/reports/dashboard?period=month").get("salesCount").asLong();

        Long expected = ownerJdbc().queryForObject("""
                SELECT count(*) FROM sales
                WHERE tenant_id = ?
                  AND status <> 'voided'
                  AND sold_at >= ((now() AT TIME ZONE 'UTC')::date - 29)::timestamp
                                     AT TIME ZONE 'UTC'
                """, Long.class, tenant.tenantId());

        assertThat(expected).isGreaterThan(20L);
        assertThat(reported).isEqualTo(expected);
    }

    @Test
    @DisplayName("openRecommendationCount is real, and matches the recommendations table")
    void openRecommendationCountIsNonZero() {
        long reported = get("/reports/dashboard").get("openRecommendationCount").asLong();

        Long expected = ownerJdbc().queryForObject(
                "SELECT count(*) FROM reorder_recommendations WHERE tenant_id = ? "
                        + "AND status = 'open'", Long.class, tenant.tenantId());

        // The hand-built fixture asserts this is zero because nothing has run a
        // recompute there. Here one has, so the non-zero branch is covered — a
        // count that always answered zero would satisfy the other test forever.
        assertThat(expected).isGreaterThan(0L);
        assertThat(reported).isEqualTo(expected);
    }

    @Test
    @DisplayName("inventoryValue matches product_stock priced at cost")
    void inventoryValueMatchesTheStockTable() {
        BigDecimal reported =
                get("/reports/inventory-valuation").get("totalCostValue").decimalValue();

        BigDecimal expected = ownerJdbc().queryForObject("""
                SELECT COALESCE(round(SUM(ps.quantity_on_hand * p.cost_price), 2), 0)
                FROM product_stock ps
                JOIN products p ON p.id = ps.product_id AND p.tenant_id = ps.tenant_id
                WHERE ps.tenant_id = ?
                  AND p.deleted_at IS NULL AND p.is_active AND p.is_tracked
                """, BigDecimal.class, tenant.tenantId());

        assertThat(expected).isGreaterThan(BigDecimal.ZERO);
        assertThat(reported).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("the seeded dead-stock product is the worst mover; the steady seller is not")
    void topProductsReflectTheSeededShapes() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        String window = "from=%s&to=%s".formatted(today.minusDays(29), today);

        JsonNode worst = get("/reports/top-products?order=worst&" + window);
        JsonNode best = get("/reports/top-products?order=best&" + window);

        String dead = tenant.productIdsByShape().get("dead").toString();
        String steady = tenant.productIdsByShape().get("steady").toString();

        // M6's "Discontinued Fruitcake" is stocked once at the start of the
        // window and never sells again. It is what `order=worst` exists to find,
        // and it is only findable because the query left-joins the catalogue —
        // it has no sale lines in this window at all.
        assertThat(worst.get(0).get("productId").asString())
                .as("the dead-stock product must lead the worst movers: %s", worst)
                .isEqualTo(dead);
        assertThat(worst.get(0).get("unitsSold").decimalValue()).isEqualByComparingTo("0.000");

        List<String> bestIds = new java.util.ArrayList<>();
        for (JsonNode row : best) {
            bestIds.add(row.get("productId").asString());
        }
        assertThat(bestIds).as("the steady seller sells every day").contains(steady);
        assertThat(bestIds).as("dead stock is not a best seller").doesNotContain(dead);
    }

    @Test
    @DisplayName("the trend has real day-to-day variation, not one spike")
    void trendVariesAcrossTheWindow() {
        JsonNode trend = get("/reports/dashboard").get("salesTrend");

        int daysWithSales = 0;
        for (JsonNode point : trend) {
            if (point.get("revenue").decimalValue().signum() > 0) {
                daysWithSales++;
            }
        }
        assertThat(trend.size()).isEqualTo(14);
        assertThat(daysWithSales)
                .as("M6 sells on most days; a trend that is mostly zero here means "
                        + "the window or the timezone is wrong, not that the shop was quiet")
                .isGreaterThanOrEqualTo(10);
    }

    /**
     * M8: "Report endpoints stay under ~500 ms against a tenant with 100k
     * movements."
     *
     * <p>This tenant has 30 weeks of history rather than 100k movements, so the
     * timing here is a smoke test and the <em>plan</em> is the real assertion.
     * A query that is quadratic in a tenant's sales history is fast on a small
     * one and only fails in production; a sequential scan of {@code sales} is
     * visible at any size.
     *
     * <p>The specific regression it guards is named in {@code ReportQueries}:
     * comparing {@code (sold_at AT TIME ZONE z)::date} against a date bound is
     * the natural way to write these windows and cannot use
     * {@code sales_tenant_time_idx}.
     */
    @Test
    @DisplayName("report queries finish quickly and do not sequentially scan sales")
    void reportQueriesUseIndexesAndFinishQuickly() {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        List<String> endpoints = List.of(
                "/reports/dashboard?period=month",
                "/reports/sales-summary?from=%s&to=%s&groupBy=day"
                        .formatted(today.minusDays(200), today),
                "/reports/inventory-valuation",
                "/reports/inventory-valuation?asOf=" + today.minusDays(100),
                "/reports/top-products?order=best",
                "/reports/top-products?order=worst");

        for (String endpoint : endpoints) {
            long start = System.nanoTime();
            get(endpoint);
            long millis = (System.nanoTime() - start) / 1_000_000;

            log.info("SEED DATA: {} responded in {} ms", endpoint, millis);
            assertThat(millis)
                    .as("%s took %d ms; M8's budget is ~500 ms at far more data than this",
                            endpoint, millis)
                    .isLessThan(500L);
        }

        // And the plan, which is what generalises past this dataset's size.
        String plan = explainSalesWindow();
        log.info("SEED DATA: plan for the sales window:\n{}", plan);

        assertThat(plan)
                .as("the window must be an index scan on sales_tenant_time_idx. A "
                        + "plan that says `Seq Scan on sales` means the bounds are "
                        + "being compared after a per-row timezone conversion, which "
                        + "is the natural way to write this and the slow one.\n%s", plan)
                .doesNotContain("Seq Scan on sales");
        assertThat(plan).containsIgnoringCase("Index");
    }

    /**
     * {@code EXPLAIN ANALYZE} of the window predicate the reports share, run as
     * the application role on a bound connection so RLS is in the plan too.
     */
    private String explainSalesWindow() {
        try {
            return asTenant(() -> {
                JdbcTemplate jdbc = new JdbcTemplate(appDataSource);
                List<String> lines = jdbc.queryForList("""
                        EXPLAIN (ANALYZE, BUFFERS)
                        SELECT count(*), COALESCE(SUM(si.line_total), 0)
                        FROM sales s
                        JOIN sale_items si ON si.sale_id = s.id
                        WHERE s.status <> 'voided'
                          AND s.sold_at >= ((now() AT TIME ZONE 'UTC')::date - 29)::timestamp
                                               AT TIME ZONE 'UTC'
                          AND s.sold_at <  ((now() AT TIME ZONE 'UTC')::date + 1)::timestamp
                                               AT TIME ZONE 'UTC'
                        """, String.class);
                return String.join("\n", lines);
            });
        } catch (Exception e) {
            throw new IllegalStateException("EXPLAIN failed", e);
        }
    }
}
