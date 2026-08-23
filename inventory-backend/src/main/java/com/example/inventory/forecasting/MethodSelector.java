package com.example.inventory.forecasting;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

/**
 * Chooses a forecasting method by measuring the series, never by reading a
 * setting — {@code docs/adr/forecasting.md} §4.
 *
 * <p>"Not configuration" is the whole point and is worth restating, because a
 * per-product override column is the obvious-looking feature request: a shop
 * owner who can pin a method can pin the wrong one, and the wrongness shows up
 * as quietly bad reorder advice rather than as an error. The data already knows
 * which method suits it.
 *
 * <h2>The three buckets (ADR §4), in order</h2>
 *
 * <ol>
 *   <li><strong>Not ready</strong> — fails either half of ADR §5's threshold:
 *       fewer than 42 calendar days of history, or fewer than 10 eligible
 *       non-zero demand days. {@code insufficient_data}, and it is checked
 *       first, so no series can be classified as intermittent or steady on
 *       evidence too thin to classify anything.</li>
 *   <li><strong>Intermittent</strong> — {@code nonzero_fraction < 0.3}.
 *       {@code croston}.</li>
 *   <li><strong>Steady</strong> — everything else, split below.</li>
 * </ol>
 *
 * <h2>Which steady method: RESOLVED here, and it was left open on purpose</h2>
 *
 * <p>ADR §4 fixed the top-level split and deliberately did <em>not</em> fix
 * which of the three steady methods to use, on the grounds that pinning a rule
 * with no implementation to test it against risks pinning one nobody has
 * validated. It attached one hard requirement to whoever resolved it: the choice
 * must come from measured shape — trend detection — and must <strong>not</strong>
 * be a silent default to plain {@code moving_average}, which would flatly under-
 * or over-forecast a trending product every single period.
 *
 * <p>So: {@link DemandSeries#relativeTrend()} measures how far the series drifts
 * across its own window as a fraction of its mean level, and
 * {@link #TREND_THRESHOLD} splits flat from trending. A flat series gets
 * {@code moving_average}; a trending one gets {@code weighted_moving_average},
 * whose linear recency weighting tracks a ramp instead of averaging its start
 * and end together.
 *
 * <p><strong>{@code exponential_smoothing} is deliberately not selected by
 * anything.</strong> It would be a third slot filled to look complete: for a
 * flat series it is a slower {@code moving_average}, and for a trending one
 * single exponential smoothing lags a ramp exactly the way a plain mean does —
 * it needs a trend term (Holt) to compete with the weighted average, and adding
 * Holt is a real modelling decision rather than a wiring one. The same reasoning
 * ADR §4 applies to {@code ml_model}: an enum slot existing is not a decision
 * that it should be filled.
 *
 * <h2>Why the threshold is 0.5, and why it does not need to be exact</h2>
 *
 * <p>Same argument ADR §4 makes for its 0.3: the line only has to fall in the
 * gap between the shapes that actually occur. Measured over M6's real 30-week
 * seed data across five RNG seeds, the steady seller's relative trend sits near
 * zero and the trending product's sits above 1.0 — the ramp roughly quadruples
 * its daily rate across the window by construction. 0.5 is comfortably between
 * them and nowhere near either.
 *
 * <p>The threshold is on the <em>absolute</em> value. A product whose demand is
 * collapsing is as badly served by a flat mean as one whose demand is climbing,
 * and it is the more urgent of the two to get right — an average that keeps
 * quoting last quarter's rate for a dying product recommends reorders nobody
 * will sell.
 */
@Component
public class MethodSelector {

    /** ADR §5, first condition: calendar days, including censored and zero days. */
    static final int MIN_HISTORY_DAYS = 42;

    /** ADR §5, second condition: eligible days that sold something. */
    static final int MIN_NON_ZERO_ELIGIBLE_DAYS = 10;

    /** ADR §4: below this share of selling days, demand is intermittent. */
    static final BigDecimal INTERMITTENT_FRACTION = new BigDecimal("0.3");

    /**
     * Above this much drift across the window — as a fraction of the mean level
     * — a plain average is the wrong shape. See the class Javadoc for the
     * calibration.
     */
    static final BigDecimal TREND_THRESHOLD = new BigDecimal("0.5");

    /**
     * What was chosen and the measurements that chose it.
     *
     * <p>The measurements ride along rather than being recomputed by callers,
     * because ADR §6's explanation has to quote them back to the shop owner and
     * a second computation is a second chance to disagree with the first.
     *
     * @param daysShortOfHistory      calendar days still needed, 0 if satisfied
     * @param nonZeroDaysShortOfCount eligible non-zero days still needed, 0 if satisfied
     */
    public record Selection(
            ForecastMethod method,
            int historyDays,
            int eligibleDays,
            int nonZeroEligibleDays,
            BigDecimal nonzeroFraction,
            BigDecimal relativeTrend,
            int daysShortOfHistory,
            int nonZeroDaysShortOfCount) {

        public boolean isReady() {
            return method != ForecastMethod.INSUFFICIENT_DATA;
        }

        /**
         * The larger of the two shortfalls, in days — what ADR §6's "still
         * learning, N more days" sentence should quote.
         *
         * <p>The non-zero shortfall is a count of <em>selling</em> days, not of
         * calendar days, so quoting it directly would promise a date it cannot
         * keep: a product needing 8 more selling days at one sale a fortnight is
         * four months away, not eight days. It is converted at the series' own
         * observed selling rate by {@code ReorderService}; here it stays a raw
         * count so nothing rounds twice.
         */
        public int daysShort() {
            return Math.max(daysShortOfHistory, nonZeroDaysShortOfCount);
        }
    }

    public Selection select(DemandSeries series) {
        int historyDays = series.historyDays();
        int eligibleDays = series.eligibleDayCount();
        int nonZeroEligible = series.nonZeroEligibleDayCount();
        BigDecimal fraction = series.nonzeroFraction();
        BigDecimal trend = series.relativeTrend();

        int daysShortOfHistory = Math.max(0, MIN_HISTORY_DAYS - historyDays);
        int nonZeroShort = Math.max(0, MIN_NON_ZERO_ELIGIBLE_DAYS - nonZeroEligible);

        // Readiness first. ADR §5 is an AND: a product can rack up ten selling
        // days in under two weeks and still wait for week six, because ten sales
        // in twelve days says nothing about whether week three's Tuesday looks
        // like week one's.
        if (daysShortOfHistory > 0 || nonZeroShort > 0) {
            return new Selection(ForecastMethod.INSUFFICIENT_DATA, historyDays, eligibleDays,
                    nonZeroEligible, fraction, trend, daysShortOfHistory, nonZeroShort);
        }

        ForecastMethod method;
        if (fraction.compareTo(INTERMITTENT_FRACTION) < 0) {
            method = ForecastMethod.CROSTON;
        } else if (trend.abs().compareTo(TREND_THRESHOLD) > 0) {
            method = ForecastMethod.WEIGHTED_MOVING_AVERAGE;
        } else {
            method = ForecastMethod.MOVING_AVERAGE;
        }
        return new Selection(method, historyDays, eligibleDays, nonZeroEligible, fraction, trend,
                0, 0);
    }
}
