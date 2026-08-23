package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.inventory.forecasting.DemandSeries.Day;

/**
 * The demand estimators themselves. {@link MethodSelector} decides which one
 * runs; this class is only the arithmetic.
 *
 * <p>Each produces one number — average daily demand — because that is what
 * {@code docs/adr/forecasting.md} §1's reorder point consumes. The spread comes
 * from {@link DemandSeries#stddev()} and is method-independent on purpose: it
 * describes the observed history, not the model's opinion of it, and a model
 * that got to state its own uncertainty could quietly report a comfortable one.
 *
 * <p>All of them read <strong>eligible days only</strong> (ADR §3). A censored
 * day's {@code units_sold} is a floor set by the empty shelf, and feeding it to
 * any of these estimators reintroduces exactly the bias the flag exists to
 * remove.
 */
@Component
public class DemandModels {

    /**
     * Croston's smoothing constant.
     *
     * <p>Not an ADR number — the ADR names Croston without fixing its alpha, so
     * this is the conventional 0.1 from the intermittent-demand literature,
     * where values of 0.1–0.2 are standard. Low on purpose: intermittent series
     * are mostly zeros, and a high alpha makes the estimate lurch on every
     * individual sale, which is the behaviour Croston exists to avoid.
     */
    static final BigDecimal CROSTON_ALPHA = new BigDecimal("0.1");

    /**
     * @throws IllegalArgumentException for a method that estimates nothing —
     *         {@code insufficient_data} has no number to give, and returning
     *         zero for it would be indistinguishable from a real zero-demand
     *         forecast at every call site downstream.
     */
    public BigDecimal averageDailyDemand(ForecastMethod method, DemandSeries series) {
        return switch (method) {
            case MOVING_AVERAGE -> movingAverage(series);
            case WEIGHTED_MOVING_AVERAGE -> weightedMovingAverage(series);
            case CROSTON -> croston(series);
            case EXPONENTIAL_SMOOTHING, ML_MODEL, NAIVE -> throw new IllegalArgumentException(
                    method.dbValue() + " is declared but not implemented — MethodSelector "
                            + "never returns it. See MethodSelector's Javadoc.");
            case INSUFFICIENT_DATA -> throw new IllegalArgumentException(
                    "insufficient_data has no demand estimate by definition. The caller must "
                            + "branch on Selection.isReady() rather than asking for a number "
                            + "and getting a zero it cannot tell from a real one.");
        };
    }

    /** The plain mean over eligible days. */
    private BigDecimal movingAverage(DemandSeries series) {
        return series.mean();
    }

    /**
     * Linearly recency-weighted: the oldest eligible day carries weight 1, the
     * newest weight n.
     *
     * <p>Chosen over the plain mean when the series is trending, because a mean
     * of a ramp reports the middle of the ramp — a number the product has
     * already left behind, and one that under-orders a growing product every
     * period and over-orders a dying one.
     *
     * <p><strong>Known limitation, stated rather than hidden:</strong> a linear
     * weighting over a long window still lags a ramp. For a straight ramp from
     * a to b this lands near a + ⅔(b−a), not at b. Closing that gap properly
     * means a model with an explicit trend term (Holt), which is a real
     * modelling decision rather than a wiring one, and ADR §4's instruction was
     * to avoid pinning a rule nobody has validated. This is strictly better than
     * the plain mean it replaces — that would report a + ½(b−a) — and the
     * residual lag is recorded here so the next person does not have to
     * rediscover it.
     */
    private BigDecimal weightedMovingAverage(DemandSeries series) {
        List<Day> eligible = series.eligible();
        if (eligible.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal weightedTotal = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (int i = 0; i < eligible.size(); i++) {
            BigDecimal weight = BigDecimal.valueOf(i + 1L);
            weightedTotal = weightedTotal.add(
                    eligible.get(i).unitsSold().multiply(weight, DemandSeries.MC), DemandSeries.MC);
            weightSum = weightSum.add(weight);
        }
        return weightedTotal.divide(weightSum, DemandSeries.MC);
    }

    /**
     * Croston's method for intermittent demand: smooth the <em>size</em> of a
     * sale and the <em>interval</em> between sales separately, and divide.
     *
     * <p>Why two series rather than one average. A product selling 3 units once
     * a fortnight and one selling 0.2 units every day both average ~0.21/day,
     * and a single mean cannot tell them apart — but they need very different
     * reorder points, because the first has to cover a 3-unit hit that arrives
     * without warning. Separating size from interval keeps that distinction
     * visible, which is why ADR §4 routes intermittent demand here rather than
     * letting a naive average smooth it into a rate that never actually occurs.
     *
     * <p>The estimate is size ÷ interval, which for a stable series agrees with
     * the plain mean. Agreement is expected and is not evidence the routing was
     * pointless: the two diverge exactly when the series is irregular, which is
     * the case that matters.
     *
     * <p><strong>Known limitation, stated rather than hidden:</strong> classic
     * Croston does not decay when demand stops. A product that sold steadily for
     * six months and nothing for the last two still reports the old rate, because
     * the estimate only updates on a sale and there have been none. The
     * Syntetos-Boylan correction and the bias-adjusted variants address this and
     * are a different method rather than a tweak. In this system the effect is
     * bounded by the readiness threshold at one end — a product that never sells
     * enough stays {@code insufficient_data} — and it is worth knowing about at
     * the other.
     */
    private BigDecimal croston(DemandSeries series) {
        List<Day> eligible = series.eligible();

        BigDecimal size = null;
        BigDecimal interval = null;
        int gap = 0;

        for (Day day : eligible) {
            gap++;
            if (day.unitsSold().signum() <= 0) {
                continue;
            }
            if (size == null) {
                // First observation initialises the size and nothing else.
                //
                // The interval is deliberately NOT initialised here, and this is
                // the subtle part: the gap leading to the FIRST sale is the
                // distance from the start of the window, which is not an
                // inter-arrival interval at all. Seeding the estimate with it
                // and smoothing from there is a real bug with a quiet symptom —
                // at alpha 0.1 the estimate crawls toward the true interval and,
                // over a series with only ~20 sales, never arrives. A product
                // selling 3 units every 10 days reported 0.34/day instead of
                // 0.30, about 14% high, with nothing to indicate it was wrong.
                // Caught by MethodSelectorTest.crostonIsSizeOverInterval.
                size = day.unitsSold();
            } else if (interval == null) {
                // The second observation supplies the first genuine
                // inter-arrival gap, which is what the estimate starts from.
                interval = BigDecimal.valueOf(gap);
                size = smooth(size, day.unitsSold());
            } else {
                size = smooth(size, day.unitsSold());
                interval = smooth(interval, BigDecimal.valueOf(gap));
            }
            gap = 0;
        }

        if (size == null || interval == null || interval.signum() <= 0) {
            // Fewer than two non-zero days, so there is no interval to speak of.
            // Readiness needs ten, so this is unreachable through the selector;
            // it is here so a direct caller gets zero rather than a division by
            // zero.
            return BigDecimal.ZERO;
        }
        return size.divide(interval, DemandSeries.MC);
    }

    /** {@code alpha * observation + (1 - alpha) * previous}. */
    private BigDecimal smooth(BigDecimal previous, BigDecimal observation) {
        return CROSTON_ALPHA.multiply(observation, DemandSeries.MC)
                .add(BigDecimal.ONE.subtract(CROSTON_ALPHA)
                        .multiply(previous, DemandSeries.MC), DemandSeries.MC);
    }
}
