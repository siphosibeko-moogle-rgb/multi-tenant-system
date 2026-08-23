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
 * <h2>The window is all available history</h2>
 *
 * <p>ADR §4 says "the trailing history window" without fixing a length, and §5
 * defines {@code history_days} as the span from the first {@code demand_daily}
 * row to the most recent. Taken together the window is everything there is, and
 * that is what this class computes over. It is also what makes the steady
 * seller's measured 2.65/day reproducible: a 90-day window would report a
 * different number than the one {@code MILESTONES.md} now records.
 *
 * <p>The cost is that a long window lags a product whose demand is moving, which
 * is precisely why {@link #relativeTrend()} exists and why the selector routes a
 * trending product away from a plain mean rather than letting the window flatten
 * it.
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

    /** Rounds a computed quantity to the scale its column stores. */
    static BigDecimal stored(BigDecimal value) {
        return value.setScale(STORED_SCALE, RoundingMode.HALF_UP);
    }
}
