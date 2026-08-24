package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.forecasting.DemandSeries.Day;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does each method actually beat the naive baseline <em>on the demand shape it
 * was chosen for</em>? — {@code docs/adr/forecasting.md} §7's per-bucket
 * comparison, run for real.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@code ForecastAccuracyTest} proves the scoring <em>mechanism</em>: that a
 * baseline is written, that the arithmetic is right, that nothing is scored
 * twice or early. Its fixtures are hand-picked numbers chosen to be checkable by
 * hand, and the {@code method} column on them is a label rather than a
 * measurement — its "croston" products have perfectly flat demand and predicted
 * values picked to make the percentages come out round.
 *
 * <p>That is fine for testing the mechanism and <strong>useless for judging a
 * method</strong>, and the distinction is easy to lose: a per-method MAPE table
 * built from those fixtures looks exactly like a real result. Croston reading
 * worse than naive there says nothing about Croston, because nothing in that
 * scoring set is intermittent and no Croston code produced any of the numbers.
 *
 * <p>So this class builds genuinely intermittent and genuinely steady demand,
 * runs the <em>real</em> selector and the <em>real</em> estimators over
 * progressively truncated history, and scores the result. Every forecast here is
 * a number this system actually computed, on a series shaped the way the method
 * is meant for.
 *
 * <h2>Truncated history, so each forecast only sees its own past</h2>
 *
 * <p>The series is built once and each evaluation window forecasts from the days
 * strictly before it. Forecasting from the whole series and then scoring against
 * part of it would let the model see its own answer — the same failure this
 * milestone caught by mutation in the baseline (see
 * {@code docs/adr/forecasting.md} §7).
 */
@DisplayName("Do the methods beat naive on the shapes they were chosen for?")
class BaselineComparisonTest extends AbstractIntegrationTest {

    @Autowired
    private MethodSelector selector;

    @Autowired
    private DemandModels models;

    @Autowired
    private ForecastAccuracyJob accuracy;

    private static final int HORIZON = 30;
    private static final int WINDOWS = 6;
    private static final int HISTORY_DAYS = 360;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 1);
    private static final LocalDate SERIES_START = TODAY.minusDays(HISTORY_DAYS);

    private static UUID tenantId;
    private static UUID locationId;
    private static UUID intermittentProduct;
    private static UUID steadyProduct;
    private static boolean seeded;

    @BeforeEach
    void seed() throws Exception {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        locationId = UUID.randomUUID();
        intermittentProduct = UUID.randomUUID();
        steadyProduct = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO tenants (id, slug, name, timezone)
                    VALUES ('%s', 'base-%s', 'Baseline Fixture', 'UTC')
                    """.formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Shelf', true)".formatted(locationId, tenantId));
            for (UUID productId : List.of(intermittentProduct, steadyProduct)) {
                stmt.execute("""
                        INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price, tax_rate)
                        VALUES ('%s', '%s', 'BASE-%s', 'Baseline Product', 4.00, 10.00, 0)
                        """.formatted(productId, tenantId, productId.toString().substring(0, 8)));
            }
        }

        // Sells on roughly 1 day in 10, 1-3 units — M6's intermittent shape.
        writeDemand(intermittentProduct, buildSeries(new Random(11L), 0.10, 1, 3));
        // Sells on ~90% of days, 2-4 units — M6's steady shape.
        writeDemand(steadyProduct, buildSeries(new Random(22L), 0.90, 2, 4));

        asTenant(() -> {
            forecastEachWindow(intermittentProduct);
            forecastEachWindow(steadyProduct);
            return accuracy.scoreDueForecasts(TODAY);
        });
        seeded = true;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, null, null));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private JdbcTemplate owner() {
        try {
            Connection conn = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Day> buildSeries(Random rng, double sellProbability, int min, int max) {
        List<Day> days = new ArrayList<>();
        for (int i = 0; i < HISTORY_DAYS; i++) {
            int units = rng.nextDouble() < sellProbability ? min + rng.nextInt(max - min + 1) : 0;
            days.add(new Day(SERIES_START.plusDays(i), new BigDecimal(units), false));
        }
        return days;
    }

    private void writeDemand(UUID productId, List<Day> days) {
        JdbcTemplate owner = owner();
        for (Day day : days) {
            owner.update("""
                    INSERT INTO demand_daily
                        (tenant_id, product_id, location_id, day, units_sold, sales_count, revenue)
                    VALUES (?, ?, ?, ?, ?, ?, 0)
                    """, tenantId, productId, locationId, day.day(), day.unitsSold(),
                    day.unitsSold().signum() > 0 ? 1 : 0);
        }
    }

    /**
     * One forecast per evaluation window, each computed from the days strictly
     * before that window — the real selector, the real estimator, truncated
     * history.
     */
    private void forecastEachWindow(UUID productId) {
        JdbcTemplate owner = owner();
        List<Day> all = owner.query("""
                SELECT day, units_sold, had_stockout FROM demand_daily
                WHERE product_id = ? ORDER BY day
                """,
                (rs, n) -> new Day(rs.getObject("day", LocalDate.class),
                        rs.getBigDecimal("units_sold"), rs.getBoolean("had_stockout")),
                productId);

        for (int w = WINDOWS; w >= 1; w--) {
            LocalDate windowStart = TODAY.minusDays((long) w * HORIZON);
            List<Day> history = all.stream().filter(d -> d.day().isBefore(windowStart)).toList();

            DemandSeries series = new DemandSeries(productId, locationId, history);
            MethodSelector.Selection selection = selector.select(series);
            if (!selection.isReady()) {
                continue;
            }
            BigDecimal daily = models.averageDailyDemand(selection.method(), series);
            BigDecimal predicted = daily.multiply(BigDecimal.valueOf(HORIZON), MathContext.DECIMAL64)
                    .setScale(3, RoundingMode.HALF_UP);

            owner.update("""
                    INSERT INTO forecasts
                        (tenant_id, product_id, location_id, method, generated_at, history_days,
                         avg_daily_demand, demand_stddev, horizon_days, forecast_qty,
                         service_level, is_current)
                    VALUES (?, ?, ?, CAST(? AS forecast_method), ?, ?, ?, ?, ?, ?, 0.950, false)
                    """,
                    tenantId, productId, locationId, selection.method().dbValue(),
                    java.sql.Timestamp.valueOf(windowStart.minusDays(1).atTime(9, 0)),
                    selection.historyDays(),
                    daily.setScale(4, RoundingMode.HALF_UP), series.stddev()
                            .setScale(4, RoundingMode.HALF_UP),
                    HORIZON, predicted);
        }
    }

    /** Mean absolute percentage error for one product and one method. */
    private BigDecimal mape(UUID productId, ForecastMethod method) {
        return owner().queryForObject("""
                SELECT avg(a.abs_pct_error)
                FROM forecast_accuracy a
                JOIN forecasts f ON f.id = a.forecast_id
                WHERE a.product_id = ? AND f.method = CAST(? AS forecast_method)
                """, BigDecimal.class, productId, method.dbValue());
    }

    private int scoreableWindows(UUID productId, ForecastMethod method) {
        Integer count = owner().queryForObject("""
                SELECT count(*)
                FROM forecast_accuracy a
                JOIN forecasts f ON f.id = a.forecast_id
                WHERE a.product_id = ? AND f.method = CAST(? AS forecast_method)
                  AND a.abs_pct_error IS NOT NULL
                """, Integer.class, productId, method.dbValue());
        return count == null ? 0 : count;
    }

    private static String show(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(2, RoundingMode.HALF_UP) + "%";
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the per-shape comparison, printed — this is the table that means something")
    void reportTheRealComparison() {
        BigDecimal intermittentCroston = mape(intermittentProduct, ForecastMethod.CROSTON);
        BigDecimal intermittentNaive = mape(intermittentProduct, ForecastMethod.NAIVE);
        BigDecimal steadyReal = mape(steadyProduct, ForecastMethod.MOVING_AVERAGE);
        BigDecimal steadyNaive = mape(steadyProduct, ForecastMethod.NAIVE);

        System.out.printf("""

                === Method vs naive, per demand shape (%d windows of %d days) ===
                intermittent product  croston        %8s  over %d scoreable windows
                                      naive          %8s  over %d
                steady product        moving_average %8s  over %d
                                      naive          %8s  over %d
                %n""",
                WINDOWS, HORIZON,
                show(intermittentCroston), scoreableWindows(intermittentProduct,
                        ForecastMethod.CROSTON),
                show(intermittentNaive), scoreableWindows(intermittentProduct,
                        ForecastMethod.NAIVE),
                show(steadyReal), scoreableWindows(steadyProduct,
                        ForecastMethod.MOVING_AVERAGE),
                show(steadyNaive), scoreableWindows(steadyProduct, ForecastMethod.NAIVE));

        assertThat(intermittentCroston)
                .as("the intermittent product must actually have been routed to Croston and "
                        + "scored, or this whole comparison is about nothing")
                .isNotNull();
        assertThat(steadyReal).isNotNull();
    }

    @Test
    @DisplayName("the intermittent product really is routed to Croston — not merely labelled it")
    void theIntermittentProductIsGenuinelyIntermittent() {
        List<String> methods = owner().queryForList("""
                SELECT DISTINCT method::text FROM forecasts
                WHERE product_id = ? AND method <> 'naive'
                """, String.class, intermittentProduct);

        assertThat(methods)
                .as("every forecast for this product came from the real MethodSelector reading "
                        + "its real shape. ForecastAccuracyTest's 'croston' fixtures are flat "
                        + "demand with a hand-written label, which is why their MAPE says "
                        + "nothing about Croston.")
                .containsExactly("croston");
    }

    @Test
    @DisplayName("Croston beats naive on genuinely intermittent demand")
    void crostonBeatsNaiveWhereItIsMeantTo() {
        BigDecimal croston = mape(intermittentProduct, ForecastMethod.CROSTON);
        BigDecimal naive = mape(intermittentProduct, ForecastMethod.NAIVE);

        assertThat(scoreableWindows(intermittentProduct, ForecastMethod.CROSTON))
                .as("a comparison over one window is luck, not evidence")
                .isGreaterThanOrEqualTo(4);

        assertThat(croston)
                .as("""
                        This is ADR §7's actual question, on the shape Croston exists for. \
                        Naive quotes whatever the previous 30 days happened to sell, and for \
                        an intermittent product that figure swings wildly between windows; \
                        Croston estimates a rate from demand size and interval and stays \
                        steady. If Croston LOST here it would be a real finding and step 6 \
                        should not ship recommendations built on it. Observed: croston %s, \
                        naive %s.""".formatted(show(croston), show(naive)))
                .isLessThan(naive);
    }

    @Test
    @DisplayName("the steady product's moving average also beats naive")
    void theMovingAverageBeatsNaiveOnSteadyDemand() {
        BigDecimal movingAverage = mape(steadyProduct, ForecastMethod.MOVING_AVERAGE);
        BigDecimal naive = mape(steadyProduct, ForecastMethod.NAIVE);

        assertThat(movingAverage)
                .as("a steady seller is the easiest case there is; a method that could not "
                        + "beat 'same as last month' here would not be earning its complexity "
                        + "at all. Observed: moving_average %s, naive %s.",
                        show(movingAverage), show(naive))
                .isLessThan(naive);
    }
}
