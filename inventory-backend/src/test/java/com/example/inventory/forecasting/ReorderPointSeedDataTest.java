package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.forecasting.LeadTimeResolver.LeadTime;
import com.example.inventory.forecasting.LeadTimeResolver.Source;
import com.example.inventory.seed.SeedDataRunner;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reorder points over M6's real seed data — both branches of
 * {@code docs/adr/forecasting.md} §2's lead-time rule, against real suppliers
 * with real observations rather than mocked figures.
 *
 * <p>M6 seeds two suppliers per tenant expressly so both branches exist in real
 * data: an established one with 7 completed purchase orders, comfortably over
 * §2's n≥5 threshold, whose measured lead time disagrees with what it promised;
 * and a new relationship with 2, below the threshold, whose promised 21 days is
 * far from anything it has actually achieved. A branch that only exists in a
 * fixture is a branch nobody finds out is broken.
 */
@DisplayName("Reorder points over M6 seed data")
class ReorderPointSeedDataTest extends AbstractIntegrationTest {

    private static final int WINDOW_WEEKS = SeedDataRunner.WINDOW_WEEKS;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private DemandSeriesRepository seriesRepository;

    @Autowired
    private LeadTimeResolver leadTimes;

    @Autowired
    private ReorderPointCalculator reorderPoints;

    @Autowired
    private Forecaster forecaster;

    @Autowired
    @Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private static SeededTenant tenant;
    private static boolean prepared;

    @BeforeEach
    void seedAndRollUp() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Reorder Bakery", "reorder-" + tag, 1L, WINDOW_WEEKS);
        asTenant(() -> rollup.rollUp());
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

    private UUID product(String shape) {
        return tenant.productIdsByShape().get(shape);
    }

    private Forecast forecastFor(String shape) throws Exception {
        return asTenant(() -> forecaster.forecast(product(shape), tenant.locationId()));
    }

    private static BigDecimal round(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the observed and promised figures genuinely disagree — the fixture has teeth")
    void theTwoLeadTimeSourcesDisagree() throws Exception {
        LeadTimeResolver.Observations observed =
                asTenant(() -> leadTimes.observationsFor(tenant.supplierId()));
        // Inside asTenant: this reads through the RLS-bound application pool, so
        // with no tenant bound the policy matches no rows and the query returns
        // nothing rather than failing loudly. That is the isolation working
        // (CLAUDE.md §10) — an unbound read is empty, not an error.
        Integer promised = asTenant(() -> new JdbcTemplate(appDataSource).queryForObject(
                "SELECT lead_time_days FROM suppliers WHERE id = ?",
                Integer.class, tenant.supplierId()));

        System.out.printf("established supplier: %d observations, observed mean %.3f days, "
                + "promised %d days%n", observed.count(), observed.averageDays(), promised);

        assertThat(observed.count())
                .as("M6 gives this supplier 6 scheduled receipts plus the stockout product's "
                        + "own restock. ADR §2 trusts the average at 5 or more.")
                .isGreaterThanOrEqualTo(LeadTimeResolver.MIN_OBSERVATIONS);
        assertThat(observed.averageDays())
                .as("and the measured average must actually differ from the promised %d, or a "
                        + "reorder point built from the wrong one would be indistinguishable "
                        + "from a correct one and every assertion below would be vacuous",
                        promised)
                .isNotEqualByComparingTo(BigDecimal.valueOf(promised));
    }

    @Test
    @DisplayName("a supplier with 5+ observations resolves to the OBSERVED average, not the promise")
    void theObservedBranchIsUsed() throws Exception {
        LeadTime leadTime = asTenant(() -> leadTimes.forProduct(product("steady")).orElseThrow());
        LeadTimeResolver.Observations observed =
                asTenant(() -> leadTimes.observationsFor(tenant.supplierId()));

        assertThat(leadTime.source())
                .as("promised lead times are optimistic and measured ones are not — preferring "
                        + "the promise whenever it is present would make the system repeat what "
                        + "the supplier said instead of what it did")
                .isEqualTo(Source.OBSERVED);
        assertThat(leadTime.days())
                .as("and it is the measured mean, to the value the observations actually carry")
                .isEqualByComparingTo(observed.averageDays());
        assertThat(leadTime.sampleSize()).isEqualTo(observed.count());
    }

    @Test
    @DisplayName("a supplier with fewer than 5 observations falls back to the promised figure")
    void theFallbackBranchIsUsed() throws Exception {
        // The seasonal product is on M6's second supplier — a new relationship
        // with 2 completed orders, deliberately under the threshold.
        LeadTime leadTime = asTenant(() -> leadTimes.forProduct(product("seasonal")).orElseThrow());
        LeadTimeResolver.Observations observed =
                asTenant(() -> leadTimes.observationsFor(leadTime.supplierId()));

        System.out.printf("new supplier: %d observations, observed mean %.3f days, "
                + "resolved to %s days from %s%n",
                observed.count(), observed.averageDays(), leadTime.days(), leadTime.source());

        assertThat(observed.count())
                .as("fixture check: this supplier must be genuinely below the threshold, or "
                        + "this test is exercising the observed branch under a fallback name")
                .isLessThan(LeadTimeResolver.MIN_OBSERVATIONS);
        assertThat(observed.count())
                .as("but not zero — a supplier with SOME evidence that is still too little is "
                        + "the interesting case. Zero observations would also pass a test that "
                        + "merely checked the count, while proving nothing about the threshold.")
                .isGreaterThan(0);

        assertThat(leadTime.isObserved())
                .as("at %d observations the measured average is not trusted", observed.count())
                .isFalse();
        assertThat(leadTime.days())
                .as("it uses the promised 21 days, which M6 sets far from the 3-8 its two real "
                        + "receipts achieved, precisely so a bug that read observations anyway "
                        + "would be obviously wrong rather than plausibly close. Observed mean "
                        + "was %s.", observed.averageDays())
                .isEqualByComparingTo("21");
    }

    @Test
    @DisplayName("the same demand series gives a higher reorder point at 21 days than at 3")
    void aSlowerSupplierMeansAHigherReorderPoint() throws Exception {
        DemandSeries steady = asTenant(() -> seriesRepository.load(
                product("steady"), tenant.locationId()));

        var fast = reorderPoints.calculate(steady.mean(), steady.stddev(),
                new BigDecimal("3"), ReorderPointCalculator.DEFAULT_SERVICE_LEVEL);
        var slow = reorderPoints.calculate(steady.mean(), steady.stddev(),
                new BigDecimal("21"), ReorderPointCalculator.DEFAULT_SERVICE_LEVEL);

        System.out.printf("steady seller (avg %.3f/day, stddev %.3f): reorder point %s at 3 days, "
                + "%s at 21 days%n", steady.mean(), steady.stddev(),
                fast.reorderPoint(), slow.reorderPoint());

        assertThat(slow.reorderPoint())
                .as("MILESTONES M7: a product on a 21-day supplier must carry more stock than "
                        + "the same product on a 3-day one — this is the real demand series, "
                        + "not a constructed one")
                .isGreaterThan(fast.reorderPoint());
    }

    @Test
    @DisplayName("the steady seller's reorder point is built from its own measured numbers")
    void theSteadySellersReorderPoint() throws Exception {
        Forecast forecast = forecastFor("steady");

        System.out.printf("steady seller: method=%s avg=%.3f/day stddev=%.3f leadTime=%s (%s) "
                + "reorderPoint=%s safetyStock=%s onHand=%s daysOfCover=%s%n",
                forecast.method().dbValue(), forecast.avgDailyDemand(), forecast.demandStddev(),
                forecast.leadTime().days(), forecast.leadTime().source(),
                forecast.reorderPoint(), forecast.safetyStock(),
                forecast.quantityOnHand(), forecast.daysOfCover());

        assertThat(forecast.avgDailyDemand())
                .as("the figure MILESTONES records, measured under the bounded window")
                .isCloseTo(new BigDecimal("2.65"),
                        org.assertj.core.data.Offset.offset(new BigDecimal("0.20")));

        // Recomputed independently from the forecast's own inputs. If the
        // calculator were wired up wrongly — say the stddev and the lead time
        // swapped — the number would still look plausible on its own.
        var expected = reorderPoints.calculate(forecast.avgDailyDemand(),
                forecast.demandStddev(), forecast.leadTime().days(), forecast.serviceLevel());
        assertThat(forecast.reorderPoint()).isEqualByComparingTo(expected.reorderPoint());

        assertThat(forecast.reorderPoint())
                .as("and it must exceed the pure lead-time demand — the difference IS the "
                        + "safety stock, and a reorder point equal to lead-time demand would "
                        + "mean the buffer silently vanished")
                .isGreaterThan(forecast.avgDailyDemand()
                        .multiply(forecast.leadTime().days())
                        .setScale(3, RoundingMode.HALF_UP));
        assertThat(forecast.safetyStock()).isPositive();
    }

    @Test
    @DisplayName("the stockout product's reorder point uses the CENSORED-EXCLUDED average")
    void theStockoutProductIsNotPunishedForItsOutage() throws Exception {
        DemandSeries series = asTenant(() -> seriesRepository.load(
                product("stockout"), tenant.locationId()));
        Forecast forecast = forecastFor("stockout");

        // What the average would have been if the outage days had been averaged
        // in — ADR §3's spiral, computed here purely for contrast.
        BigDecimal naiveAverage = series.days().stream()
                .map(DemandSeries.Day::unitsSold)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(series.days().size()), DemandSeries.MC);

        var naiveReorderPoint = reorderPoints.calculate(
                naiveAverage.setScale(4, RoundingMode.HALF_UP), forecast.demandStddev(),
                forecast.leadTime().days(), forecast.serviceLevel());

        System.out.printf("stockout product: eligible avg %.3f/day vs naive %.3f/day; "
                + "reorder point %s vs naive %s%n",
                forecast.avgDailyDemand(), naiveAverage,
                forecast.reorderPoint(), naiveReorderPoint.reorderPoint());

        assertThat(forecast.avgDailyDemand())
                .as("the eligible-days average must be HIGHER than the all-days one — the "
                        + "outage days are floors, not counts, and averaging them in is what "
                        + "starts ADR §3's spiral")
                .isGreaterThan(naiveAverage.setScale(4, RoundingMode.HALF_UP));
        assertThat(forecast.reorderPoint())
                .as("and the reorder point inherits that: excluding the censored days is worth "
                        + "real units of buffer on this product, which is the whole point of "
                        + "the exclusion")
                .isGreaterThan(naiveReorderPoint.reorderPoint());
    }

    @Test
    @DisplayName("an insufficient_data product gets NO reorder point and no projected stockout")
    void anUnreadyProductGetsNoNumbers() throws Exception {
        for (String shape : java.util.List.of("dead", "new")) {
            Forecast forecast = forecastFor(shape);

            assertThat(forecast.method())
                    .as("fixture check: %s must be below the readiness threshold", shape)
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
            assertThat(forecast.reorderPoint())
                    .as("%s: ADR §5 — a null reorder point, not a confident zero. A zero would "
                            + "read as 'never reorder this' and is a different claim entirely.",
                            shape)
                    .isNull();
            assertThat(forecast.projectedStockoutOn())
                    .as("%s: and a null projected stockout date", shape)
                    .isNull();
        }
    }

    @Test
    @DisplayName("a product with no supplier on file gets no reorder point rather than a made-up one")
    void noSupplierMeansNoReorderPoint() throws Exception {
        // M6 leaves dead stock and the brand-new product unlinked on purpose.
        assertThat(asTenant(() -> leadTimes.forProduct(product("dead"))))
                .as("nobody keeps a supplier on file for discontinued stock")
                .isEmpty();
        assertThat(asTenant(() -> leadTimes.forProduct(product("new"))))
                .as("and 'just started carrying this, stocked by hand' is brand-new's own story")
                .isEmpty();

        // The positive twin: a linked product DOES resolve one, so the two
        // assertions above are not passing because the resolver is simply broken.
        assertThat(asTenant(() -> leadTimes.forProduct(product("steady"))))
                .as("without this, a resolver that returned empty for everything would satisfy "
                        + "both assertions above perfectly")
                .isPresent();
    }

    @Test
    @DisplayName("the seasonal product gets a reorder point AND carries the caveat with it")
    void theSeasonalCaveatPropagatesIntoTheReorderPoint() throws Exception {
        Forecast forecast = forecastFor("seasonal");

        System.out.printf("seasonal product: method=%s seasonalityIndicator=%s avg=%.3f/day "
                + "leadTime=%s (%s) reorderPoint=%s caveat=%s%n",
                forecast.method().dbValue(),
                round(forecast.selection().seasonalityIndicator(), 3),
                forecast.avgDailyDemand(), forecast.leadTime().days(),
                forecast.leadTime().source(), forecast.reorderPoint(),
                forecast.seasonalitySuspected());

        assertThat(forecast.method())
                .as("it routes to a perfectly ordinary moving_average — which is exactly the "
                        + "danger: nothing about the method looks wrong")
                .isEqualTo(ForecastMethod.MOVING_AVERAGE);

        assertThat(forecast.hasReorderPoint())
                .as("the number is still produced. Withholding it was considered and rejected: "
                        + "a seasonal product is the kind a shop most needs ordering help with, "
                        + "and saying nothing would be less useful than saying something "
                        + "qualified.")
                .isTrue();
        assertThat(forecast.reorderPoint()).isPositive();

        assertThat(forecast.seasonalitySuspected())
                .as("but it must NOT be presented as trustworthy. Every method the selector can "
                        + "choose averages this product's weekend peaks flat, so the number is "
                        + "right for an average week and wrong at both the peak and the trough. "
                        + "This flag is what carries that into the explanation and the API "
                        + "response.")
                .isTrue();
    }

    @Test
    @DisplayName("no non-cyclical product's reorder point carries the caveat")
    void theCaveatDoesNotLeakOntoOrdinaryProducts() throws Exception {
        for (String shape : java.util.List.of("steady", "intermittent", "stockout", "trending")) {
            Forecast forecast = forecastFor(shape);
            assertThat(forecast.seasonalitySuspected())
                    .as("%s: a caveat on every product is a caveat nobody reads. Observed "
                            + "indicator %s.", shape,
                            round(forecast.selection().seasonalityIndicator(), 3))
                    .isFalse();
        }
    }
}
