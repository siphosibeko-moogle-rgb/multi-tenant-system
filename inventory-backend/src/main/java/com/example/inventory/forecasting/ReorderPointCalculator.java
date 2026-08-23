package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * {@code docs/adr/forecasting.md} §1's reorder point.
 *
 * <pre>
 * reorder_point = avg_daily_demand × lead_time_days + safety_stock
 * safety_stock  = z(service_level) × demand_stddev × sqrt(lead_time_days)
 * </pre>
 *
 * <p>Both moments come from eligible days only — {@link DemandSeries} already
 * applies §3's had-stockout exclusion, so nothing here has to remember to.
 *
 * <h2>Why safety stock is this and not something simpler</h2>
 *
 * <p>ADR §1 rules out the two easy wrong answers explicitly, and they are worth
 * restating because both look like safety margins:
 *
 * <ul>
 *   <li><strong>A fixed number of units.</strong> Does not scale with the
 *       product or with how long the supplier takes.</li>
 *   <li><strong>A percentage of {@code avg_daily_demand × lead_time}.</strong>
 *       This is the tempting one, because it does scale — but it scales with
 *       <em>demand</em>, not with <em>uncertainty</em>. It does not grow when
 *       demand becomes erratic, which is the one situation safety stock exists
 *       for. Two products selling 3/day, one like clockwork and one lurching
 *       between 0 and 10, would get identical buffers.</li>
 * </ul>
 *
 * <p>The formula here grows with {@code demand_stddev}, so the erratic product
 * gets the bigger buffer, and with {@code sqrt(lead_time)} rather than
 * {@code lead_time}, because the variances of independent days add while their
 * standard deviations do not — doubling the lead time increases the uncertainty
 * to cover by about 41%, not 100%.
 *
 * <h2>Deliberately excluded: lead-time variability</h2>
 *
 * <p>{@code suppliers.lead_time_stddev_days} and
 * {@code observedLeadTime.stddevDays} exist and are not used. A fuller formula
 * folds them in as {@code z × sqrt(LT·σd² + d²·σLT²)}. ADR §1 scopes safety
 * stock to demand variability and the service level specifically, and widening
 * that the first time somebody notices the unused columns is exactly the
 * undiscussed formula change the ADR exists to prevent. It is a conversation to
 * have, not a fix to slip in.
 */
@Component
public class ReorderPointCalculator {

    /**
     * Standard-normal quantiles, for the service levels a shop would plausibly
     * pick. {@code z(0.95) ≈ 1.645} is ADR §1's worked value.
     *
     * <p>A table rather than an inverse-normal implementation: the schema
     * constrains {@code service_level} to (0,1) but nothing in this system
     * offers a free-form choice, and a handful of exact published values is
     * easier to check by eye than an approximation nobody will verify. Anything
     * between two entries is rounded to the nearer, which errs on the side of
     * more stock at the midpoint.
     */
    private static final NavigableMap<BigDecimal, BigDecimal> Z_BY_SERVICE_LEVEL = new TreeMap<>();

    static {
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.500"), new BigDecimal("0.000"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.750"), new BigDecimal("0.674"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.800"), new BigDecimal("0.842"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.850"), new BigDecimal("1.036"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.900"), new BigDecimal("1.282"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.950"), new BigDecimal("1.645"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.975"), new BigDecimal("1.960"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.990"), new BigDecimal("2.326"));
        Z_BY_SERVICE_LEVEL.put(new BigDecimal("0.999"), new BigDecimal("3.090"));
    }

    /** The schema's own default for {@code forecasts.service_level}. */
    public static final BigDecimal DEFAULT_SERVICE_LEVEL = new BigDecimal("0.950");

    /**
     * @param reorderPoint  units at which to reorder
     * @param safetyStock   the buffer component, kept separately so the
     *                      explanation can say how much of the number is cover
     *                      for uncertainty rather than for the lead time itself
     * @param leadTimeDemand {@code avg_daily_demand × lead_time_days}
     */
    public record ReorderPoint(BigDecimal reorderPoint, BigDecimal safetyStock,
                               BigDecimal leadTimeDemand, BigDecimal z) {
    }

    public ReorderPoint calculate(BigDecimal avgDailyDemand, BigDecimal demandStddev,
                                  BigDecimal leadTimeDays, BigDecimal serviceLevel) {
        if (avgDailyDemand.signum() < 0 || demandStddev.signum() < 0
                || leadTimeDays.signum() < 0) {
            throw new IllegalArgumentException(
                    "demand, spread and lead time must all be non-negative; got "
                            + avgDailyDemand + ", " + demandStddev + ", " + leadTimeDays);
        }

        BigDecimal z = zFor(serviceLevel);
        BigDecimal leadTimeDemand = avgDailyDemand.multiply(leadTimeDays, DemandSeries.MC);

        BigDecimal sqrtLeadTime = leadTimeDays.signum() == 0
                ? BigDecimal.ZERO
                : leadTimeDays.sqrt(DemandSeries.MC);
        BigDecimal safetyStock = z.multiply(demandStddev, DemandSeries.MC)
                .multiply(sqrtLeadTime, DemandSeries.MC);

        return new ReorderPoint(
                round(leadTimeDemand.add(safetyStock, DemandSeries.MC)),
                round(safetyStock),
                round(leadTimeDemand),
                z);
    }

    /** The nearest tabulated standard-normal quantile. */
    BigDecimal zFor(BigDecimal serviceLevel) {
        BigDecimal below = Z_BY_SERVICE_LEVEL.floorKey(serviceLevel);
        BigDecimal above = Z_BY_SERVICE_LEVEL.ceilingKey(serviceLevel);

        if (below == null) {
            return Z_BY_SERVICE_LEVEL.firstEntry().getValue();
        }
        if (above == null) {
            return Z_BY_SERVICE_LEVEL.lastEntry().getValue();
        }
        BigDecimal distanceBelow = serviceLevel.subtract(below);
        BigDecimal distanceAbove = above.subtract(serviceLevel);
        // Ties go to the higher service level: at the midpoint, hold more stock
        // rather than less. A stockout costs a sale and a customer; the extra
        // unit costs shelf space.
        return Z_BY_SERVICE_LEVEL.get(distanceAbove.compareTo(distanceBelow) <= 0 ? above : below);
    }

    /** {@code reorder_point} is {@code numeric(14,3)}. */
    private static BigDecimal round(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP);
    }
}
