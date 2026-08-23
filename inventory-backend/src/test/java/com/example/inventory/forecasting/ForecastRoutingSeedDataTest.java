package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.seed.SeedDataRunner;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every M6 demand shape routes to the method {@code docs/adr/forecasting.md}
 * predicts for it — across <strong>five different RNG seeds</strong>, not one.
 *
 * <h2>Why five seeds and not one</h2>
 *
 * <p>Step 1 found the reason the hard way. The seed originally used here drew 11
 * selling days for the intermittent shape against a coded 10% rate that averages
 * ~21, leaving it one day above ADR §5's ten-non-zero-day readiness floor.
 * Everything passed. But a routing assertion resting on a series that close to a
 * threshold is not testing the selector — it is testing one lucky draw, and the
 * day the seeder's randomness moves it fails for a reason that has nothing to do
 * with the code under test.
 *
 * <p>Running the same assertions over five independent seeds is what makes
 * "intermittent routes to Croston" a statement about the shape rather than about
 * a sample. If a shape lands differently on one seed, that is either a genuinely
 * marginal shape or a threshold in the wrong place, and both are worth knowing
 * before steps 3–6 build on the routing.
 */
@DisplayName("M6 shapes route to the methods the ADR predicts")
class ForecastRoutingSeedDataTest extends AbstractIntegrationTest {

    private static final int WINDOW_WEEKS = SeedDataRunner.WINDOW_WEEKS;

    /**
     * Five arbitrary but fixed seeds. Fixed so a failure is reproducible;
     * arbitrary so they are not chosen to flatter the thresholds.
     */
    private static final List<Long> SEEDS = List.of(1L, 2L, 3L, 5L, 8L);

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private DemandSeriesRepository seriesRepository;

    @Autowired
    private MethodSelector selector;

    @Autowired
    private DemandModels models;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    /** Rows the rollup wrote, before any window is applied. */
    private long countRolledUpDays(SeededTenant tenant, String shape) {
        return new org.springframework.jdbc.core.JdbcTemplate(appDataSource).queryForObject(
                "SELECT count(*) FROM demand_daily WHERE product_id = ? AND location_id = ?",
                Long.class, tenant.productIdsByShape().get(shape), tenant.locationId());
    }

    private static List<SeededTenant> tenants;
    private static boolean prepared;

    @BeforeEach
    void seedAndRollUp() throws Exception {
        if (prepared) {
            return;
        }
        tenants = new ArrayList<>();
        for (long seed : SEEDS) {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            SeededTenant tenant = seeder.seedTenant(
                    "Routing Bakery " + seed, "route-" + seed + "-" + tag, seed, WINDOW_WEEKS);
            tenants.add(tenant);
            asTenant(tenant, () -> rollup.rollUp());
        }
        prepared = true;
    }

    private <T> T asTenant(SeededTenant tenant, Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(
                tenant.tenantId(), tenant.ownerId(), "owner"));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private MethodSelector.Selection selectionFor(SeededTenant tenant, String shape)
            throws Exception {
        return asTenant(tenant, () -> selector.select(seriesRepository.load(
                tenant.productIdsByShape().get(shape), tenant.locationId())));
    }

    private DemandSeries seriesFor(SeededTenant tenant, String shape) throws Exception {
        return asTenant(tenant, () -> seriesRepository.load(
                tenant.productIdsByShape().get(shape), tenant.locationId()));
    }

    /** Runs an assertion for one shape against every seed, naming the seed on failure. */
    private void forEachSeed(String shape, SeedAssertion assertion) throws Exception {
        for (int i = 0; i < tenants.size(); i++) {
            assertion.check(SEEDS.get(i), selectionFor(tenants.get(i), shape));
        }
    }

    @FunctionalInterface
    private interface SeedAssertion {
        void check(long seed, MethodSelector.Selection selection) throws Exception;
    }

    private static BigDecimal round(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the routing matrix across every seed — printed, so the numbers can be checked")
    void reportTheRoutingMatrix() throws Exception {
        StringBuilder report = new StringBuilder(
                "\n=== MethodSelector over M6 seed data, %d seeds x %d weeks ===\n"
                        .formatted(SEEDS.size(), WINDOW_WEEKS));
        report.append(String.format("%-13s %5s %7s %9s %9s %8s %8s  %s%n",
                "shape", "seed", "history", "nonzeroFr", "trend", "season", "avg/day", "method"));

        Map<String, List<ForecastMethod>> byShape = new LinkedHashMap<>();

        for (int i = 0; i < tenants.size(); i++) {
            SeededTenant tenant = tenants.get(i);
            long seed = SEEDS.get(i);
            for (String shape : tenant.productIdsByShape().keySet()) {
                DemandSeries series = seriesFor(tenant, shape);
                MethodSelector.Selection selection = selector.select(series);
                byShape.computeIfAbsent(shape, k -> new ArrayList<>()).add(selection.method());

                String average = selection.isReady()
                        ? round(models.averageDailyDemand(selection.method(), series), 3).toString()
                        : "-";
                report.append(String.format("%-13s %5d %7d %9s %9s %8s %8s  %s%s%n",
                        shape, seed, selection.historyDays(),
                        round(selection.nonzeroFraction(), 3),
                        round(selection.relativeTrend(), 3),
                        round(selection.seasonalityIndicator(), 3),
                        average, selection.method().dbValue(),
                        selection.isSeasonalitySuspected() ? "  [SEASONAL CAVEAT]" : ""));
            }
        }

        report.append("\nstability across seeds:\n");
        for (Map.Entry<String, List<ForecastMethod>> entry : byShape.entrySet()) {
            long distinct = entry.getValue().stream().distinct().count();
            report.append(String.format("  %-13s %s%n", entry.getKey(),
                    distinct == 1
                            ? entry.getValue().get(0).dbValue() + " on all " + SEEDS.size()
                            : "UNSTABLE: " + entry.getValue().stream()
                                    .map(ForecastMethod::dbValue).toList()));
        }
        System.out.println(report);

        assertThat(byShape.keySet())
                .as("all six M6 shapes must be present, or a shape is silently going unrouted")
                .hasSize(6);
    }

    @Test
    @DisplayName("the 12-month window is a no-op at 30 weeks — the whole series still fits")
    void theDefaultWindowDoesNotTrimThisSeedData() throws Exception {
        SeededTenant tenant = tenants.get(0);

        for (String shape : tenant.productIdsByShape().keySet()) {
            DemandSeries windowed = seriesFor(tenant, shape);
            long rolledUpRows = asTenant(tenant, () -> countRolledUpDays(tenant, shape));

            assertThat((long) windowed.days().size())
                    .as("%s: M6 seeds 30 weeks (~212 days), well inside a 365-day window, so "
                            + "every rolled-up row must still reach the forecaster. If this "
                            + "ever trims, every figure in MILESTONES M7 was computed over a "
                            + "different series than the one the code now reads.", shape)
                    .isEqualTo(rolledUpRows);
        }
    }

    @Test
    @DisplayName("a shorter window genuinely trims — the bound is real, not decorative")
    void aShorterWindowTrimsTheSeries() throws Exception {
        SeededTenant tenant = tenants.get(0);
        // Constructed directly rather than through a second Spring context: the
        // point is that the window value drives the query, and this is the
        // cheapest way to prove it does.
        DemandSeriesRepository narrow = new DemandSeriesRepository(
                appDataSource, new ForecastingProperties(60));

        DemandSeries full = seriesFor(tenant, "steady");
        DemandSeries trimmed = asTenant(tenant, () -> narrow.load(
                tenant.productIdsByShape().get("steady"), tenant.locationId()));

        assertThat(full.days().size())
                .as("the default window keeps the whole 30 weeks")
                .isGreaterThan(200);
        assertThat(trimmed.days().size())
                .as("and a 60-day window keeps 60 days — without this, the window could be "
                        + "ignored entirely and every test above would still pass, because "
                        + "365 happens to exceed this seed data's length")
                .isEqualTo(60);
        assertThat(trimmed.lastDay())
                .as("trimmed from the OLD end: a trailing window keeps the most recent days, "
                        + "and keeping the oldest instead would forecast from history the "
                        + "product has already left behind")
                .isEqualTo(full.lastDay());
    }

    @Test
    @DisplayName("no M6 shape trips the seasonality caveat — none of them is seasonal")
    void noSeededShapeIsFlaggedSeasonal() throws Exception {
        for (String shape : List.of("steady", "intermittent", "stockout", "trending")) {
            forEachSeed(shape, (seed, selection) -> assertThat(selection.isSeasonalitySuspected())
                    .as("seed %d, %s: M6 seeds no cyclical shape, so a caveat here would be a "
                            + "false positive — and the trending product is the one that would "
                            + "trip it if the autocorrelation were not detrended first. "
                            + "Observed indicator %s against a %s threshold.",
                            seed, shape, round(selection.seasonalityIndicator(), 3),
                            MethodSelector.SEASONALITY_THRESHOLD)
                    .isFalse());
        }
    }

    @Test
    @DisplayName("intermittent routes to Croston on every seed — never a naive average")
    void intermittentRoutesToCroston() throws Exception {
        forEachSeed("intermittent", (seed, selection) -> {
            assertThat(selection.method())
                    .as("seed %d: M7's own 'Done when' says the intermittent product must NOT "
                            + "get a naive-average forecast. nonzeroFraction %s against ADR §4's "
                            + "0.3 line, %d non-zero eligible days.",
                            seed, round(selection.nonzeroFraction(), 3),
                            selection.nonZeroEligibleDays())
                    .isEqualTo(ForecastMethod.CROSTON);

            assertThat(selection.nonZeroEligibleDays())
                    .as("seed %d: and it must clear the readiness floor with room, not by one "
                            + "day — a routing assertion resting on a marginal draw tests the "
                            + "draw, not the selector", seed)
                    .isGreaterThanOrEqualTo(MethodSelector.MIN_NON_ZERO_ELIGIBLE_DAYS);
        });
    }

    @Test
    @DisplayName("the steady seller is ready and is not intermittent, on every seed")
    void steadyRoutesToASteadyMethod() throws Exception {
        forEachSeed("steady", (seed, selection) -> {
            assertThat(selection.isReady())
                    .as("seed %d: ADR §5's table says the steady seller crosses at week 6; the "
                            + "window is 30", seed)
                    .isTrue();
            assertThat(selection.method())
                    .as("seed %d: it sells on ~87%% of days — nowhere near ADR §4's 0.3 line. "
                            + "Observed nonzeroFraction %s.",
                            seed, round(selection.nonzeroFraction(), 3))
                    .isNotEqualTo(ForecastMethod.CROSTON);
        });
    }

    @Test
    @DisplayName("the trending product gets the weighted average, never the flat mean")
    void trendingRoutesAwayFromAFlatMean() throws Exception {
        forEachSeed("trending", (seed, selection) -> {
            assertThat(selection.isReady())
                    .as("seed %d: ADR §5 — same as steady, assuming regular sales", seed)
                    .isTrue();
            assertThat(selection.method())
                    .as("seed %d: ADR §4's one hard requirement on whoever resolved the steady "
                            + "split — the trending shape must not fall through to a plain "
                            + "moving_average, which would under-forecast it every single "
                            + "period. M6 ramps this product from ~5/week to ~23/week. "
                            + "Observed relative trend %s against a %s threshold.",
                            seed, round(selection.relativeTrend(), 3),
                            MethodSelector.TREND_THRESHOLD)
                    .isEqualTo(ForecastMethod.WEIGHTED_MOVING_AVERAGE);
        });
    }

    @Test
    @DisplayName("dead stock stays insufficient_data forever — on every seed")
    void deadStockIsNeverReady() throws Exception {
        forEachSeed("dead", (seed, selection) -> {
            assertThat(selection.method())
                    .as("seed %d: ADR §5 — 'Never'. A product with no measurable demand has no "
                            + "reorder signal, and inventing one is the confidently-wrong number "
                            + "the threshold exists to prevent. History %d days, %d non-zero.",
                            seed, selection.historyDays(), selection.nonZeroEligibleDays())
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);

            assertThat(selection.historyDays())
                    .as("seed %d: and it is refused on the NON-ZERO condition, not the calendar "
                            + "one — it has a full window of history. Failing for the wrong "
                            + "reason would pass this test and be a different bug.", seed)
                    .isGreaterThanOrEqualTo(MethodSelector.MIN_HISTORY_DAYS);
            assertThat(selection.nonZeroDaysShortOfCount())
                    .as("seed %d: ten short, because it never sold anything", seed)
                    .isEqualTo(MethodSelector.MIN_NON_ZERO_ELIGIBLE_DAYS);
        });
    }

    @Test
    @DisplayName("the brand-new product is refused on the calendar floor, on every seed")
    void brandNewIsNotReadyYet() throws Exception {
        forEachSeed("new", (seed, selection) -> {
            assertThat(selection.method())
                    .as("seed %d: under two weeks of history fails ADR §5's 42-day floor "
                            + "outright. Observed %d days.", seed, selection.historyDays())
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
            assertThat(selection.daysShortOfHistory())
                    .as("seed %d: and the shortfall is a real number the explanation can quote "
                            + "(ADR §6), not a shrug", seed)
                    .isGreaterThan(0);
        });
    }

    @Test
    @DisplayName("the stockout product is ready — its outage does not slow its readiness clock")
    void theStockoutProductIsStillReady() throws Exception {
        forEachSeed("stockout", (seed, selection) -> {
            assertThat(selection.isReady())
                    .as("seed %d: ADR §5 — 'Same as steady, once outside the outage'. The "
                            + "calendar floor counts censored days precisely so a "
                            + "stockout-prone product's readiness clock does not run slower on "
                            + "top of the average it is already denied. History %d days, %d "
                            + "eligible, %d non-zero eligible.",
                            seed, selection.historyDays(), selection.eligibleDays(),
                            selection.nonZeroEligibleDays())
                    .isTrue();

            assertThat(selection.eligibleDays())
                    .as("seed %d: and its eligible set is genuinely smaller than its calendar "
                            + "span — otherwise the censoring is not being applied at all and "
                            + "the assertion above proves nothing about it", seed)
                    .isLessThan(selection.historyDays());
        });
    }
}
