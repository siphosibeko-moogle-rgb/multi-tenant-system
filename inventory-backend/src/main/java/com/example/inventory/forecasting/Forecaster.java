package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.inventory.forecasting.LeadTimeResolver.LeadTime;
import com.example.inventory.forecasting.MethodSelector.Selection;
import com.example.inventory.forecasting.ReorderPointCalculator.ReorderPoint;

/**
 * Turns one product's demand history into a forecast: which method, what daily
 * rate, and — when it can honestly say so — a reorder point.
 *
 * <h2>Three ways a reorder point is withheld rather than invented</h2>
 *
 * <p>Each returns {@code null}, and the contract allows it: {@code reorderPoint}
 * and {@code projectedStockoutOn} are both nullable on {@code Forecast}.
 *
 * <ol>
 *   <li><strong>Not ready</strong> ({@code insufficient_data}). ADR §5 is
 *       explicit — null reorder point, null projected stockout, and an
 *       explanation saying so. A product with two sales in six weeks has no
 *       rate; producing one anyway is the confidently-wrong number the
 *       threshold exists to prevent.</li>
 *   <li><strong>No supplier on file.</strong> A reorder point needs a lead time
 *       and a lead time comes from a supplier (ADR §2). Substituting a default
 *       would produce a made-up number indistinguishable from a real one.</li>
 *   <li><strong>Zero measured demand</strong> over the eligible window. Nothing
 *       to reorder against.</li>
 * </ol>
 *
 * <h2>A seasonal product gets its number AND a warning</h2>
 *
 * <p>Deliberately not a fourth withholding case. When
 * {@link Selection#isSeasonalitySuspected()} holds, the reorder point is still
 * computed and {@link Forecast#seasonalitySuspected()} is set alongside it, so
 * every layer above — the explanation, the API response, the recommendation —
 * carries the caveat rather than presenting the number as trustworthy.
 *
 * <p>Withholding it instead was considered and rejected: a seasonal product is
 * exactly the kind a shop most needs ordering help with, and refusing to say
 * anything would be less useful than saying something qualified. But it must be
 * qualified — the selector can only choose methods that average a cycle flat, so
 * the number is right for an average week and wrong at both the peak and the
 * trough. See {@code docs/adr/forecasting.md} §4; closing that gap is an open M7
 * requirement, and this flag is the seam it plugs into.
 */
@Service
public class Forecaster {

    private final DemandSeriesRepository seriesRepository;
    private final MethodSelector selector;
    private final DemandModels models;
    private final LeadTimeResolver leadTimes;
    private final ReorderPointCalculator reorderPoints;
    private final ForecastingProperties properties;

    public Forecaster(DemandSeriesRepository seriesRepository,
                      MethodSelector selector,
                      DemandModels models,
                      LeadTimeResolver leadTimes,
                      ReorderPointCalculator reorderPoints,
                      ForecastingProperties properties) {
        this.seriesRepository = seriesRepository;
        this.selector = selector;
        this.models = models;
        this.leadTimes = leadTimes;
        this.reorderPoints = reorderPoints;
        this.properties = properties;
    }

    /**
     * @param reorderPoint          null when withheld — see the class Javadoc
     * @param daysOfCover           null when demand is zero, i.e. cover is
     *                              effectively infinite, which is what
     *                              {@code V1}'s own comment on the column says
     * @param seasonalitySuspected  the number is real but not to be trusted for
     *                              this product; callers must surface it
     */
    public record Forecast(
            UUID productId,
            UUID locationId,
            Selection selection,
            BigDecimal avgDailyDemand,
            BigDecimal demandStddev,
            int horizonDays,
            BigDecimal forecastQty,
            BigDecimal quantityOnHand,
            BigDecimal daysOfCover,
            LocalDate projectedStockoutOn,
            BigDecimal reorderPoint,
            BigDecimal safetyStock,
            LeadTime leadTime,
            BigDecimal serviceLevel,
            boolean seasonalitySuspected) {

        public ForecastMethod method() {
            return selection.method();
        }

        public boolean hasReorderPoint() {
            return reorderPoint != null;
        }
    }

    public Forecast forecast(UUID productId, UUID locationId) {
        DemandSeries series = seriesRepository.load(productId, locationId);
        return forecast(series, LocalDate.now());
    }

    Forecast forecast(DemandSeries series, LocalDate today) {
        Selection selection = selector.select(series);
        BigDecimal onHand = seriesRepository.quantityOnHand(
                series.productId(), series.locationId());
        BigDecimal serviceLevel = ReorderPointCalculator.DEFAULT_SERVICE_LEVEL;
        int horizon = properties.horizonDays();

        if (!selection.isReady()) {
            // ADR §5. No rate, no cover, no reorder point — and the explanation
            // (step 4) still has to say something real about why.
            return new Forecast(series.productId(), series.locationId(), selection,
                    BigDecimal.ZERO, BigDecimal.ZERO, horizon, BigDecimal.ZERO,
                    onHand, null, null, null, null, null, serviceLevel, false);
        }

        BigDecimal avgDaily = DemandSeries.stored(
                models.averageDailyDemand(selection.method(), series));
        BigDecimal stddev = DemandSeries.stored(series.stddev());
        BigDecimal forecastQty = avgDaily.multiply(BigDecimal.valueOf(horizon), DemandSeries.MC)
                .setScale(3, RoundingMode.HALF_UP);

        BigDecimal daysOfCover = null;
        LocalDate projectedStockout = null;
        if (avgDaily.signum() > 0) {
            daysOfCover = onHand.divide(avgDaily, DemandSeries.MC).setScale(2, RoundingMode.HALF_UP);
            // Floor, not round: a shelf that lasts 3.9 days runs out ON day 3,
            // and rounding up would put the projected date after the stockout.
            projectedStockout = today.plusDays(daysOfCover.setScale(0, RoundingMode.FLOOR)
                    .longValueExact());
        }

        Optional<LeadTime> leadTime = leadTimes.forProduct(series.productId());
        BigDecimal reorderPoint = null;
        BigDecimal safetyStock = null;
        if (leadTime.isPresent() && avgDaily.signum() > 0) {
            ReorderPoint computed = reorderPoints.calculate(
                    avgDaily, stddev, leadTime.get().days(), serviceLevel);
            reorderPoint = computed.reorderPoint();
            safetyStock = computed.safetyStock();
        }

        return new Forecast(series.productId(), series.locationId(), selection,
                avgDaily, stddev, horizon, forecastQty, onHand, daysOfCover,
                projectedStockout, reorderPoint, safetyStock, leadTime.orElse(null),
                serviceLevel, selection.isSeasonalitySuspected());
    }
}
