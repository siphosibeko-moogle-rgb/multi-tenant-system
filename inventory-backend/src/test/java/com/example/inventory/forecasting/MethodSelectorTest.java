package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.inventory.forecasting.DemandSeries.Day;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MethodSelector} and {@link DemandModels} on constructed series, where
 * every boundary can be probed from both sides.
 *
 * <p>No database and no Spring context: a {@link DemandSeries} is a plain
 * record, so the thresholds can be tested exhaustively in milliseconds rather
 * than by seeding a tenant per case. The realistic-shape routing lives in
 * {@code ForecastRoutingSeedDataTest}, against M6's generator across several
 * RNG seeds.
 *
 * <p>Every threshold is asserted from <strong>both</strong> sides. A test that
 * only checks the failing side passes for a selector that refuses everything,
 * and one that only checks the passing side passes for a selector that accepts
 * everything — and both of those are real bugs that a green build would hide.
 */
@DisplayName("MethodSelector")
class MethodSelectorTest {

    private final MethodSelector selector = new MethodSelector();
    private final DemandModels models = new DemandModels();

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    /**
     * @param nonZeroEvery a selling day every N days; 1 sells daily
     */
    private static DemandSeries series(int days, int nonZeroEvery, BigDecimal quantity) {
        List<Day> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            boolean sells = nonZeroEvery > 0 && i % nonZeroEvery == 0;
            list.add(new Day(START.plusDays(i), sells ? quantity : BigDecimal.ZERO, false));
        }
        return new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);
    }

    /** Exactly {@code nonZero} selling days spread across {@code days} calendar days. */
    private static DemandSeries seriesWithExactly(int days, int nonZero) {
        List<Day> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            list.add(new Day(START.plusDays(i),
                    i < nonZero ? new BigDecimal("2") : BigDecimal.ZERO, false));
        }
        return new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);
    }

    private static DemandSeries ramp(int days, double from, double to) {
        List<Day> list = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            double value = from + (to - from) * i / (days - 1.0);
            list.add(new Day(START.plusDays(i),
                    BigDecimal.valueOf(Math.round(value * 1000), 3), false));
        }
        return new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("readiness (ADR §5)")
    class Readiness {

        @Test
        @DisplayName("41 calendar days is not ready; 42 is")
        void theCalendarFloorIsFortyTwo() {
            assertThat(selector.select(series(41, 1, BigDecimal.ONE)).method())
                    .as("one day short of six weeks — ADR §5 says say nothing rather than be "
                            + "confidently wrong")
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);

            assertThat(selector.select(series(42, 1, BigDecimal.ONE)).method())
                    .as("and the positive twin: at exactly 42 days a ready series must get a "
                            + "real method, or the floor is refusing everything")
                    .isNotEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("9 non-zero eligible days is not ready; 10 is")
        void theNonZeroFloorIsTen() {
            assertThat(selector.select(seriesWithExactly(60, 9)).method())
                    .as("ADR §5: below ten, the average and especially the spread are dominated "
                            + "by one or two individual sales")
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);

            assertThat(selector.select(seriesWithExactly(60, 10)).method())
                    .isNotEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("it is an AND — clearing one condition is not enough")
        void bothConditionsMustHold() {
            // Ten sales in twelve days. ADR §5 explicitly refuses this: ten
            // sales in twelve days says nothing about whether week three's
            // Tuesday looks like week one's.
            assertThat(selector.select(seriesWithExactly(12, 12)).method())
                    .as("plenty of selling days, not enough calendar")
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);

            assertThat(selector.select(seriesWithExactly(200, 9)).method())
                    .as("plenty of calendar, not enough selling days")
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("the calendar floor counts censored and zero days; the non-zero floor does not")
        void theTwoConditionsCountDifferentDays() {
            // 50 calendar days, 40 of them censored. ADR §5 is explicit that the
            // clock is NOT narrowed by the §3 exclusion the way the count is:
            // a stocked-out day still proves calendar time passed.
            List<Day> list = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                boolean censored = i < 40;
                list.add(new Day(START.plusDays(i), new BigDecimal("2"), censored));
            }
            DemandSeries mostlyCensored = new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);

            assertThat(mostlyCensored.historyDays())
                    .as("the span is 50 days regardless of how many were censored — narrowing "
                            + "this would make a stockout-prone product wait longer for a "
                            + "forecast on top of the average it is already denied")
                    .isEqualTo(50);
            assertThat(mostlyCensored.nonZeroEligibleDayCount())
                    .as("but only the 10 uncensored days count toward the non-zero floor")
                    .isEqualTo(10);
            assertThat(selector.select(mostlyCensored).method())
                    .as("50 >= 42 and 10 >= 10 — ready, on exactly the boundary of both")
                    .isNotEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        }

        @Test
        @DisplayName("an unready selection reports how far short it is, in both dimensions")
        void theShortfallIsReported() {
            MethodSelector.Selection selection = selector.select(seriesWithExactly(30, 4));

            assertThat(selection.daysShortOfHistory())
                    .as("42 - 30")
                    .isEqualTo(12);
            assertThat(selection.nonZeroDaysShortOfCount())
                    .as("10 - 4 — ADR §6's explanation needs a real number, not a shrug")
                    .isEqualTo(6);
            assertThat(selection.isReady()).isFalse();
        }

        @Test
        @DisplayName("an empty series is insufficient_data, not a crash and not a zero forecast")
        void anEmptySeriesIsRefused() {
            DemandSeries empty = new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), List.of());

            assertThat(empty.historyDays()).isZero();
            assertThat(selector.select(empty).method()).isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        }
    }

    @Nested
    @DisplayName("intermittent vs steady (ADR §4)")
    class TheIntermittentSplit {

        @Test
        @DisplayName("below 0.3 routes to Croston, at 0.3 it does not")
        void theSplitIsAtThreeTenths() {
            // 100 days, 29 selling: fraction 0.29.
            assertThat(selector.select(seriesWithExactly(100, 29)).method())
                    .as("0.29 < 0.3")
                    .isEqualTo(ForecastMethod.CROSTON);

            // 100 days, 30 selling: fraction exactly 0.30. ADR §4's condition is
            // "< 0.3" for intermittent and ">= 0.3" for steady, so the boundary
            // value itself is steady.
            assertThat(selector.select(seriesWithExactly(100, 30)).method())
                    .as("0.30 is not < 0.30 — the boundary belongs to steady")
                    .isNotEqualTo(ForecastMethod.CROSTON);
        }

        @Test
        @DisplayName("the fraction divides by ELIGIBLE days, not by calendar days")
        void censoredDaysLeaveTheDenominatorToo() {
            // 100 calendar days. 50 censored. Of the 50 eligible, 20 sell.
            // Over eligible days that is 0.40 — steady.
            // Over calendar days it would be 0.20 — Croston. The difference is
            // the entire bug this asserts against.
            List<Day> list = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                boolean censored = i >= 50;
                boolean sells = !censored && i < 20;
                list.add(new Day(START.plusDays(i),
                        sells ? new BigDecimal("3") : BigDecimal.ZERO, censored));
            }
            DemandSeries halfCensored = new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);

            assertThat(halfCensored.nonzeroFraction())
                    .as("20 selling days out of 50 eligible")
                    .isEqualByComparingTo("0.4");
            assertThat(selector.select(halfCensored).method())
                    .as("a censored day is not evidence of a quiet product, so it cannot be "
                            + "allowed to push a steady product into the intermittent bucket")
                    .isNotEqualTo(ForecastMethod.CROSTON);
        }
    }

    @Nested
    @DisplayName("which steady method")
    class TheSteadySplit {

        @Test
        @DisplayName("a flat series gets the plain moving average")
        void flatDemandIsAMovingAverage() {
            DemandSeries flat = series(120, 1, new BigDecimal("3"));

            assertThat(flat.relativeTrend().abs())
                    .as("a constant series has no drift at all")
                    .isLessThan(new BigDecimal("0.0001"));
            assertThat(selector.select(flat).method()).isEqualTo(ForecastMethod.MOVING_AVERAGE);
        }

        @Test
        @DisplayName("a rising series gets the weighted moving average")
        void risingDemandIsWeighted() {
            DemandSeries rising = ramp(120, 1.0, 5.0);

            assertThat(rising.relativeTrend())
                    .as("rises by four units on a mean of three — well over one whole mean's "
                            + "worth of drift across the window")
                    .isGreaterThan(MethodSelector.TREND_THRESHOLD);
            assertThat(selector.select(rising).method())
                    .as("ADR §4 forbids a silent default to plain moving_average for exactly "
                            + "this shape — it would under-forecast every period")
                    .isEqualTo(ForecastMethod.WEIGHTED_MOVING_AVERAGE);
        }

        @Test
        @DisplayName("a falling series gets it too — the threshold is on the absolute drift")
        void fallingDemandIsWeightedAsWell() {
            DemandSeries falling = ramp(120, 5.0, 1.0);

            assertThat(falling.relativeTrend())
                    .as("negative drift")
                    .isNegative();
            assertThat(selector.select(falling).method())
                    .as("a collapsing product is the more urgent of the two to get right — a "
                            + "flat mean keeps quoting last quarter's rate and recommends "
                            + "reorders nobody will sell")
                    .isEqualTo(ForecastMethod.WEIGHTED_MOVING_AVERAGE);
        }

        @Test
        @DisplayName("a gentle drift stays on the plain average")
        void aSmallTrendIsNotATrend() {
            DemandSeries gentle = ramp(120, 3.0, 3.6);

            assertThat(gentle.relativeTrend())
                    .as("0.6 of drift on a mean of 3.3 — about 0.18, under the threshold")
                    .isLessThan(MethodSelector.TREND_THRESHOLD);
            assertThat(selector.select(gentle).method())
                    .as("the threshold must not be so low that ordinary noise routes "
                            + "everything to the weighted average")
                    .isEqualTo(ForecastMethod.MOVING_AVERAGE);
        }
    }

    @Nested
    @DisplayName("seasonality")
    class Seasonality {

        /** A weekly cycle: high on two days a week, low the rest. */
        private static DemandSeries weeklyCycle(int days, double baseline, double peak) {
            List<Day> list = new ArrayList<>();
            for (int i = 0; i < days; i++) {
                boolean weekendish = i % 7 == 5 || i % 7 == 6;
                list.add(new Day(START.plusDays(i),
                        BigDecimal.valueOf(weekendish ? peak : baseline), false));
            }
            return new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);
        }

        @Test
        @DisplayName("a weekly cycle is flagged")
        void aCycleIsDetected() {
            DemandSeries seasonal = weeklyCycle(210, 1.0, 6.0);
            MethodSelector.Selection selection = selector.select(seasonal);

            assertThat(seasonal.seasonalityIndicator())
                    .as("a clean weekly cycle should correlate strongly with itself at lag 7. "
                            + "Observed %s.", seasonal.seasonalityIndicator())
                    .isGreaterThan(MethodSelector.SEASONALITY_THRESHOLD);
            assertThat(selection.isSeasonalitySuspected())
                    .as("and the flag the API caveat hangs off must be set")
                    .isTrue();
        }

        @Test
        @DisplayName("a flat series is not flagged")
        void flatDemandIsNotSeasonal() {
            DemandSeries flat = series(210, 1, new BigDecimal("3"));

            assertThat(selector.select(flat).isSeasonalitySuspected())
                    .as("a warning that appears on every product is one nobody reads")
                    .isFalse();
        }

        /** A ramp with real, aperiodic day-to-day noise on top. */
        private static DemandSeries noisyRamp(int days, double from, double to) {
            java.util.Random rng = new java.util.Random(42);
            List<Day> list = new ArrayList<>();
            for (int i = 0; i < days; i++) {
                double trend = from + (to - from) * i / (days - 1.0);
                double value = Math.max(0, trend + (rng.nextDouble() - 0.5) * 2.0);
                list.add(new Day(START.plusDays(i),
                        BigDecimal.valueOf(Math.round(value * 1000), 3), false));
            }
            return new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);
        }

        @Test
        @DisplayName("a NOISY trending series is not flagged — this is what the detrending buys")
        void aTrendIsNotMistakenForACycle() {
            // Noisy on purpose. A clean ramp leaves almost no residual and is
            // caught by the negligible-residual guard instead, which would make
            // this test pass while exercising a different mechanism than its
            // name claims (CLAUDE.md §5). Real noise means the residual is
            // genuine and substantial, so the only thing keeping the indicator
            // low is that the TREND was removed before correlating.
            DemandSeries rising = noisyRamp(210, 1.0, 5.0);
            MethodSelector.Selection selection = selector.select(rising);

            assertThat(rising.stddev())
                    .as("fixture check: the residual must be real, or the guard rather than "
                            + "the detrending is what this test measures")
                    .isGreaterThan(new BigDecimal("0.5"));
            assertThat(selection.method())
                    .as("fixture check: this must actually be routed as trending, or the "
                            + "assertion below is about the wrong series")
                    .isEqualTo(ForecastMethod.WEIGHTED_MOVING_AVERAGE);
            assertThat(selection.isSeasonalitySuspected())
                    .as("a steadily growing series is highly autocorrelated at EVERY lag "
                            + "simply because tomorrow resembles today. Without removing the "
                            + "fitted line first, every trending product would carry a "
                            + "seasonality caveat and the flag would mean nothing. Observed "
                            + "indicator %s.", selection.seasonalityIndicator())
                    .isFalse();
        }

        @Test
        @DisplayName("a series the trend explains entirely reports no cycle")
        void aNegligibleResidualIsNotACycle() {
            // A clean ramp, quantised to three decimals. The leftovers are pure
            // rounding — and rounding a linear sequence is itself a sawtooth,
            // which correlated at 0.80 before the guard existed and put a
            // seasonality caveat on a plainly non-seasonal product.
            DemandSeries cleanRamp = ramp(210, 1.0, 5.0);

            assertThat(cleanRamp.seasonalityIndicator())
                    .as("normalising by a near-zero denominator makes arbitrarily small "
                            + "structure look like a strong cycle")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a series too short to contain two cycles is not flagged")
        void oneBumpIsNotACycle() {
            // 13 days cannot contain two weekly cycles, so lag 7 is not tested.
            // A single bump is not evidence of periodicity, and treating it as
            // such would caveat every new product.
            DemandSeries tooShort = weeklyCycle(13, 1.0, 6.0);

            assertThat(tooShort.seasonalityIndicator())
                    .as("no candidate lag has two full cycles to compare")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an insufficient_data product is never flagged as seasonal")
        void anUnreadyProductCarriesNoSeasonalCaveat() {
            DemandSeries tooNew = weeklyCycle(28, 1.0, 6.0);

            assertThat(selector.select(tooNew).method())
                    .as("fixture check: 28 days is under the 42-day floor")
                    .isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
            assertThat(selector.select(tooNew).isSeasonalitySuspected())
                    .as("there is no reorder point to caveat — 'still learning' already says "
                            + "the number is not there, and stacking a second warning on it "
                            + "would be noise")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the estimators")
    class Estimators {

        @Test
        @DisplayName("the moving average is the mean of eligible days, censored days excluded")
        void movingAverageExcludesCensoredDays() {
            // Ten days at 4 units, ten censored days at 1 unit (the floor an
            // empty shelf produced). Mean over eligible is 4; over everything
            // it would be 2.5 — and 2.5 is the number that walks into the
            // reorder point and starts ADR §3's spiral.
            List<Day> list = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                boolean censored = i >= 50;
                list.add(new Day(START.plusDays(i),
                        censored ? BigDecimal.ONE : new BigDecimal("4"), censored));
            }
            DemandSeries mixed = new DemandSeries(UUID.randomUUID(), UUID.randomUUID(), list);

            assertThat(models.averageDailyDemand(ForecastMethod.MOVING_AVERAGE, mixed))
                    .as("censored days are removed from the sum AND the count — not zeroed, "
                            + "not imputed (ADR §3)")
                    .isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("the weighted average leads the plain one on a rising series")
        void theWeightedAverageLeadsTheMean() {
            DemandSeries rising = ramp(120, 1.0, 5.0);

            BigDecimal plain = models.averageDailyDemand(ForecastMethod.MOVING_AVERAGE, rising);
            BigDecimal weighted = models.averageDailyDemand(
                    ForecastMethod.WEIGHTED_MOVING_AVERAGE, rising);

            assertThat(plain)
                    .as("the mean of a ramp is its midpoint")
                    .isEqualByComparingTo(new BigDecimal("3.000"));
            assertThat(weighted)
                    .as("recency weighting must land above the midpoint — that is the entire "
                            + "reason this method is selected for a trending product. Plain "
                            + "%s, weighted %s.", plain, weighted)
                    .isGreaterThan(plain);
            assertThat(weighted)
                    .as("and below the ramp's end value: a linear weighting still lags a ramp, "
                            + "which DemandModels documents rather than hides")
                    .isLessThan(new BigDecimal("5"));
        }

        @Test
        @DisplayName("on a flat series the weighted average barely differs from the plain one")
        void misroutingAFlatSeriesToTheWeightedAverageIsCheap() {
            DemandSeries flat = series(120, 1, new BigDecimal("3"));

            BigDecimal plain = models.averageDailyDemand(ForecastMethod.MOVING_AVERAGE, flat);
            BigDecimal weighted = models.averageDailyDemand(
                    ForecastMethod.WEIGHTED_MOVING_AVERAGE, flat);

            // This is what justifies putting TREND_THRESHOLD low rather than in
            // the middle of the observed gap. The two errors are not
            // symmetric: routing a flat series to the weighted average costs
            // almost nothing, because on flat demand the two agree — while
            // routing a ramp to the plain mean is the failure ADR §4
            // explicitly forbids. When the costs are lopsided the threshold
            // belongs on the cheap side.
            assertThat(weighted)
                    .as("a false positive is nearly free: plain %s, weighted %s", plain, weighted)
                    .isCloseTo(plain, org.assertj.core.data.Offset.offset(new BigDecimal("0.001")));
        }

        @Test
        @DisplayName("Croston divides smoothed demand size by smoothed interval")
        void crostonIsSizeOverInterval() {
            // 3 units every 10th day, for 200 days. Size 3, interval 10,
            // so the rate is 0.3/day — which is also total/days here.
            DemandSeries intermittent = series(200, 10, new BigDecimal("3"));

            assertThat(selector.select(intermittent).method())
                    .as("fixture check: this must actually be routed to Croston, or the "
                            + "assertion below is testing an estimator nothing would use")
                    .isEqualTo(ForecastMethod.CROSTON);
            assertThat(models.averageDailyDemand(ForecastMethod.CROSTON, intermittent))
                    .as("3 units per 10 days")
                    .isEqualByComparingTo(new BigDecimal("0.3"));
        }

        @Test
        @DisplayName("Croston separates two series a single mean cannot tell apart")
        void crostonSeesWhatAMeanCannot() {
            // Both average 0.3/day. One sells 3 units every 10 days; the other
            // sells 1 unit roughly every 3 days (0.333/day). A single mean
            // reports nearly the same number for both; the size estimate is
            // what distinguishes them, and it is the size of the hit that
            // decides how much buffer the product needs.
            DemandSeries lumpy = series(200, 10, new BigDecimal("3"));
            DemandSeries frequent = series(200, 3, BigDecimal.ONE);

            BigDecimal lumpyRate = models.averageDailyDemand(ForecastMethod.CROSTON, lumpy);
            BigDecimal frequentRate = models.averageDailyDemand(ForecastMethod.CROSTON, frequent);

            assertThat(lumpyRate).isEqualByComparingTo(new BigDecimal("0.3"));
            assertThat(frequentRate)
                    .as("1 unit per 3 days — a recurring decimal, so this is asserted to a "
                            + "tolerance rather than to DECIMAL64's last digit")
                    .isCloseTo(new BigDecimal("0.333333"), org.assertj.core.data.Offset.offset(
                            new BigDecimal("0.000001")));
            assertThat(lumpy.stddev())
                    .as("the lumpy series is visibly more volatile day to day, which is what "
                            + "ADR §1's safety stock is sized from. Lumpy %s, frequent %s.",
                            lumpy.stddev(), frequent.stddev())
                    .isGreaterThan(frequent.stddev());
        }

        @Test
        @DisplayName("asking for an estimate the selector never produces is refused, not defaulted")
        void unimplementedMethodsRefuseRatherThanReturnZero() {
            DemandSeries any = series(120, 1, new BigDecimal("3"));

            assertThatThrownBy(() -> models.averageDailyDemand(
                    ForecastMethod.INSUFFICIENT_DATA, any))
                    .as("a zero here is indistinguishable from a real zero-demand forecast at "
                            + "every call site downstream")
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> models.averageDailyDemand(
                    ForecastMethod.EXPONENTIAL_SMOOTHING, any))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not implemented");
        }
    }

    @Nested
    @DisplayName("the contract")
    class Contract {

        @Test
        @DisplayName("every ForecastMethod value appears in docs/openapi.yaml's enum")
        void theEnumMatchesTheContract() {
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            OpenAPI contract = new OpenAPIV3Parser()
                    .read(Path.of("..", "docs", "openapi.yaml").toString(), null, options);
            assertThat(contract).as("docs/openapi.yaml must parse").isNotNull();

            io.swagger.v3.oas.models.media.Schema<?> methodSchema =
                    (io.swagger.v3.oas.models.media.Schema<?>) contract.getComponents()
                            .getSchemas().get("Forecast").getProperties().get("method");
            List<String> declared = methodSchema.getEnum().stream()
                    .map(String::valueOf).toList();

            assertThat(declared)
                    .as("the contract is the source of truth (CLAUDE.md §1) — a value in the "
                            + "Java enum and not in the contract serializes something no "
                            + "generated client can parse")
                    .containsAll(java.util.Arrays.stream(ForecastMethod.values())
                            .filter(m -> m != ForecastMethod.NAIVE)
                            .map(ForecastMethod::dbValue).toList());

            assertThat(declared)
                    .as("'naive' is the one deliberate exception, and it is asserted rather "
                            + "than merely omitted. ForecastAccuracyJob writes naive rows only "
                            + "as scoring baselines, always is_current = false, so GET "
                            + "/forecasts can never return one — listing it would document a "
                            + "response the API does not produce. This enum mirrors the "
                            + "DATABASE's forecast_method; the contract mirrors what the API "
                            + "RETURNS. Adding it to the contract, or letting a real forecast "
                            + "use it, should fail here.")
                    .doesNotContain(ForecastMethod.NAIVE.dbValue());

            assertThat(selector.select(series(200, 1, new BigDecimal("3"))).method())
                    .as("and the selector must never choose it")
                    .isNotEqualTo(ForecastMethod.NAIVE);
        }
    }
}
