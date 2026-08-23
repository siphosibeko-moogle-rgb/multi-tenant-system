package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * One product/location's {@code demand_daily} history, and the measurements
 * {@link MethodSelector} and the reorder-point arithmetic read off it.
 *
 * <h2>Eligible days, and why almost everything here is computed over them</h2>
 *
 * <p>{@code docs/adr/forecasting.md} §3: a day with {@code had_stockout} is a
 * day whose {@code units_sold} is a <em>floor</em> — the shop could have sold
 * more if there had been stock. Averaging it in drags {@code avg_daily_demand}
 * down, which drags the reorder point down, which makes the next stockout
 * arrive sooner and get averaged in too. The system's own output degrades the
 * input it is handed next cycle.
 *
 * <p>So flagged days are <strong>removed</strong> from the sum and the count —
 * not zeroed, not imputed — exactly the way {@code AVG()} skips a {@code NULL}.
 * Every statistic below except {@link #historyDays()} is over eligible days
 * only.
 *
 * <h2>{@link #historyDays()} is the one exception, deliberately</h2>
 *
 * <p>It is the calendar span from the first row to the last, <strong>including
 * flagged and zero-demand days</strong>. ADR §5 spells out why the two questions
 * are different: a stocked-out day still proves six weeks of calendar time have
 * passed, it just is not trustworthy evidence of <em>demand</em>. Narrowing the
 * clock the same way the average is narrowed would make a stockout-prone product
 * wait longer for a forecast on top of the average it is already denied.
 *
 * <h2>The window is bounded, and the bound is configuration</h2>
 *
 * <p>ADR §4 says "the trailing history window" without fixing a length. It is
 * fixed at twelve months by {@code app.forecasting.history-window-days} —
 * {@link ForecastingProperties} — and {@link DemandSeriesRepository} applies it
 * when loading, so everything here already sees only the windowed series and
 * {@link #historyDays()} is the span of what survived.
 *
 * <p>Twelve months is long enough to contain a full annual cycle if the product
 * has one, and short enough that demand from over a year ago stops steering
 * today's reorder point. An unbounded window fails the second half: a product
 * that genuinely changed — new competitor, price change, supplier switch — would
 * keep being forecast from a market that no longer exists.
 *
 * <p>Even inside the bound a long window lags a product whose demand is moving,
 * which is why {@link #relativeTrend()} exists and why the selector routes a
 * trending product away from a plain mean rather than letting the window flatten
 * it.
 *
 * <p>At M6's 30-week seed data the window changes nothing — 212 days fits inside
 * 365 — which is why the steady seller still measures 2.65/day.
 * {@code ForecastRoutingSeedDataTest} asserts both halves of that: that the
 * default window trims nothing here, and that a shorter one genuinely does, so
 * the bound cannot quietly stop being applied.
 */
public record DemandSeries(UUID productId, UUID locationId, List<Day> days) {

    /** One row of {@code demand_daily}. */
    public record Day(LocalDate day, BigDecimal unitsSold, boolean hadStockout) {
    }

    /**
     * 16 significant digits. Enough that a division or a square root below is
     * exact to far more places than {@code numeric(14,4)} stores, so rounding
     * happens once, on the way into the column, rather than compounding through
     * each intermediate step.
     */
    static final MathContext MC = MathContext.DECIMAL64;

    /** Scale of {@code forecasts.avg_daily_demand} and {@code demand_stddev}. */
    static final int STORED_SCALE = 4;

    public boolean isEmpty() {
        return days.isEmpty();
    }

    /**
     * Calendar span, inclusive of both ends and of every flagged and zero day
     * in between. Compare against ADR §5's 42-day floor.
     */
    public int historyDays() {
        if (days.isEmpty()) {
            return 0;
        }
        LocalDate first = days.get(0).day();
        LocalDate last = days.get(days.size() - 1).day();
        return (int) ChronoUnit.DAYS.between(first, last) + 1;
    }

    public LocalDate firstDay() {
        return days.isEmpty() ? null : days.get(0).day();
    }

    public LocalDate lastDay() {
        return days.isEmpty() ? null : days.get(days.size() - 1).day();
    }

    /** Days the forecaster is allowed to believe: everything not censored. */
    public List<Day> eligible() {
        return days.stream().filter(d -> !d.hadStockout()).toList();
    }

    public int eligibleDayCount() {
        return eligible().size();
    }

    /**
     * Eligible days that actually sold something. ADR §5's second readiness
     * condition counts these, and §4's {@code nonzero_fraction} divides by
     * {@link #eligibleDayCount()}.
     */
    public int nonZeroEligibleDayCount() {
        return (int) eligible().stream().filter(d -> d.unitsSold().signum() > 0).count();
    }

    /**
     * The share of eligible days that sold anything — ADR §4's split between
     * intermittent and steady demand.
     *
     * <p>Zero when there are no eligible days at all. That is the honest answer
     * rather than a division by zero, and it cannot route a product anywhere
     * dangerous: a series with no eligible days fails the readiness threshold on
     * the non-zero count first.
     */
    public BigDecimal nonzeroFraction() {
        int eligibleDays = eligibleDayCount();
        if (eligibleDays == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(nonZeroEligibleDayCount())
                .divide(BigDecimal.valueOf(eligibleDays), MC);
    }

    /** Mean daily demand over eligible days. Zero for an empty eligible set. */
    public BigDecimal mean() {
        List<Day> eligible = eligible();
        if (eligible.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = eligible.stream()
                .map(Day::unitsSold)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(eligible.size()), MC);
    }

    /**
     * Sample standard deviation (n−1) of daily demand over eligible days.
     *
     * <p>Sample rather than population because these days are a sample of the
     * product's demand, not the whole of it — the point of the figure is to say
     * something about days that have not happened yet. With fewer than two
     * eligible days there is no spread to speak of and this is zero; the
     * readiness threshold has already refused such a series a forecast.
     */
    public BigDecimal stddev() {
        List<Day> eligible = eligible();
        if (eligible.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal mean = mean();
        BigDecimal sumOfSquares = eligible.stream()
                .map(d -> d.unitsSold().subtract(mean))
                .map(diff -> diff.multiply(diff, MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal variance = sumOfSquares.divide(BigDecimal.valueOf(eligible.size() - 1L), MC);
        return variance.signum() <= 0 ? BigDecimal.ZERO : variance.sqrt(MC);
    }

    /**
     * How much the eligible series drifts across the whole window, as a fraction
     * of its own mean level. Positive means growing.
     *
     * <p>An ordinary least-squares slope over the eligible days, multiplied by
     * the window length and divided by the mean — so the number reads as "the
     * modelled demand at the end of the window is this much higher, relative to
     * the average level, than at the start". 0.0 is flat; 1.0 means the trend
     * line rises by one whole mean's worth across the window.
     *
     * <p>Expressed relatively rather than as a raw slope so one threshold can
     * serve every product. A raw slope of 0.01 units/day is a strong trend for a
     * product selling 0.2/day and noise for one selling 30/day; dividing by the
     * mean removes the scale and leaves the shape.
     *
     * <p>Zero when there are fewer than two eligible days, or when the mean is
     * zero — a series that sold nothing has no trend to measure, and dividing by
     * its mean would be a division by zero rather than a large trend.
     */
    public BigDecimal relativeTrend() {
        List<Day> eligible = eligible();
        int n = eligible.size();
        BigDecimal mean = mean();
        if (n < 2 || mean.signum() == 0) {
            return BigDecimal.ZERO;
        }

        // x is the position of the day within the eligible series, not its
        // calendar offset. Positions are evenly spaced by construction, which
        // keeps the regression from being pulled about by a long censored gap.
        BigDecimal meanX = BigDecimal.valueOf(n - 1L).divide(BigDecimal.valueOf(2), MC);
        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal varianceX = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal dx = BigDecimal.valueOf(i).subtract(meanX);
            BigDecimal dy = eligible.get(i).unitsSold().subtract(mean);
            covariance = covariance.add(dx.multiply(dy, MC), MC);
            varianceX = varianceX.add(dx.multiply(dx, MC), MC);
        }
        if (varianceX.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal slope = covariance.divide(varianceX, MC);
        return slope.multiply(BigDecimal.valueOf(n - 1L), MC).divide(mean, MC);
    }

    /**
     * Candidate cycle lengths, in days, tested for periodicity.
     *
     * <p>Weekly and fortnightly (a shop's own rhythm), monthly (pay cycles),
     * quarterly, half-yearly and annual. Not an exhaustive search over every
     * possible lag: this is a flag, not a model, and testing every lag would
     * find a "best" one in pure noise for any series long enough.
     */
    private static final int[] CANDIDATE_LAGS = {7, 14, 30, 91, 182, 365};

    /**
     * Below this much residual variation, relative to the mean, the trend is
     * taken to explain the whole series and no cycle is reported.
     */
    private static final BigDecimal NEGLIGIBLE_RESIDUAL_SHARE = new BigDecimal("0.01");

    /**
     * How strongly the series repeats itself at any candidate cycle length,
     * after its linear trend is removed. Roughly 0 for noise, approaching 1 for
     * a clean cycle.
     *
     * <p><strong>Detrending first is what makes this mean anything.</strong> A
     * steadily growing series is highly autocorrelated at every lag simply
     * because tomorrow resembles today — measure raw autocorrelation and every
     * trending product looks seasonal. Subtracting the fitted line leaves the
     * part of the series the trend does not explain, and asks whether <em>that
     * remainder</em> repeats on a cycle.
     *
     * <p>This deliberately does not identify which cycle, and nothing acts on
     * the number beyond raising a caveat. Detecting that a product is seasonal
     * is a much cheaper problem than forecasting one, and the honest thing to do
     * with the gap between them is say so — see {@link MethodSelector} and
     * {@code docs/adr/forecasting.md} §4.
     */
    public BigDecimal seasonalityIndicator() {
        List<Day> eligible = eligible();
        int n = eligible.size();
        if (n < 2 * CANDIDATE_LAGS[0]) {
            return BigDecimal.ZERO;
        }

        BigDecimal mean = mean();
        BigDecimal slope = rawSlope(eligible, mean);
        // Residual after removing the fitted line. The intercept works out so
        // that the residuals are centred on zero, which is what lets the
        // correlation below be a plain sum of products.
        BigDecimal meanX = BigDecimal.valueOf(n - 1L).divide(BigDecimal.valueOf(2), MC);
        BigDecimal[] residuals = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            BigDecimal fitted = mean.add(
                    slope.multiply(BigDecimal.valueOf(i).subtract(meanX), MC), MC);
            residuals[i] = eligible.get(i).unitsSold().subtract(fitted);
        }

        BigDecimal denominator = BigDecimal.ZERO;
        for (BigDecimal residual : residuals) {
            denominator = denominator.add(residual.multiply(residual, MC), MC);
        }
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }

        // If the trend line already explains essentially the whole series, there
        // is no remainder worth correlating and the ratio below stops meaning
        // anything: it normalises by a denominator near zero, so arbitrarily
        // small structure in the leftovers reads as a strong cycle.
        //
        // This is not hypothetical. A clean linear ramp, rounded to three
        // decimals, leaves a residual that is nothing but rounding — and
        // rounding a linear sequence produces a genuine sawtooth, which
        // correlated at 0.80 and put a seasonality caveat on a plainly
        // non-seasonal product. Caught by
        // MethodSelectorTest.aTrendIsNotMistakenForACycle.
        //
        // A cycle whose amplitude is under 1% of the product's own mean is also
        // not worth warning a shop owner about, so the same guard serves both.
        BigDecimal residualRms = denominator.divide(BigDecimal.valueOf(n), MC).sqrt(MC);
        if (residualRms.compareTo(mean.multiply(NEGLIGIBLE_RESIDUAL_SHARE, MC)) < 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal strongest = BigDecimal.ZERO;
        for (int lag : CANDIDATE_LAGS) {
            // Needs at least two full cycles to distinguish a cycle from a bump.
            if (lag * 2 > n) {
                continue;
            }
            BigDecimal numerator = BigDecimal.ZERO;
            for (int i = 0; i + lag < n; i++) {
                numerator = numerator.add(residuals[i].multiply(residuals[i + lag], MC), MC);
            }
            BigDecimal correlation = numerator.divide(denominator, MC).abs();
            if (correlation.compareTo(strongest) > 0) {
                strongest = correlation;
            }
        }
        return strongest;
    }

    /** The OLS slope in units per position, shared by trend and detrending. */
    private BigDecimal rawSlope(List<Day> eligible, BigDecimal mean) {
        int n = eligible.size();
        BigDecimal meanX = BigDecimal.valueOf(n - 1L).divide(BigDecimal.valueOf(2), MC);
        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal varianceX = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal dx = BigDecimal.valueOf(i).subtract(meanX);
            BigDecimal dy = eligible.get(i).unitsSold().subtract(mean);
            covariance = covariance.add(dx.multiply(dy, MC), MC);
            varianceX = varianceX.add(dx.multiply(dx, MC), MC);
        }
        return varianceX.signum() == 0 ? BigDecimal.ZERO : covariance.divide(varianceX, MC);
    }

    /** Rounds a computed quantity to the scale its column stores. */
    static BigDecimal stored(BigDecimal value) {
        return value.setScale(STORED_SCALE, RoundingMode.HALF_UP);
    }
}
