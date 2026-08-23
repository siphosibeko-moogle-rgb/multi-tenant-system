package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.inventory.forecasting.ForecastExplainer.Urgency;
import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.tenancy.TenantContext;

/**
 * Recomputes every forecast for the bound tenant and turns the ones that need
 * ordering into {@code reorder_recommendations}.
 *
 * <p>Tenant-scoped by its caller, exactly like {@link DemandRollupJob} and for
 * the same reason — it never binds a tenant and never enumerates them (CLAUDE.md
 * §12).
 *
 * <h2>What gets a recommendation</h2>
 *
 * <p>A product whose on-hand balance has fallen to or below its reorder point,
 * and which has a reorder point at all. The three ways a forecast withholds one
 * ({@link Forecaster}) are therefore also three ways a product produces no
 * recommendation — deliberately. A recommendation without a defensible number
 * behind it is worse than none: it teaches a shop owner to distrust the list.
 *
 * <h2>How much to order — a choice, not an ADR number</h2>
 *
 * <p>Neither the ADR nor {@code MILESTONES.md} specifies an order quantity, so
 * this is stated rather than assumed:
 *
 * <pre>
 * recommended_qty = (reorder_point + avg_daily_demand × horizon) − quantity_on_hand
 * </pre>
 *
 * <p>Order back up to the reorder point <em>plus</em> the next review period's
 * demand. Ordering only up to the reorder point would put the product straight
 * back on this list tomorrow, which is how a reorder list becomes noise. The
 * horizon is the same {@code app.forecasting.horizon-days} the forecast quotes,
 * so the sentence the shop owner reads and the quantity they are told to order
 * are derived from the same period rather than from two different ones.
 *
 * <p>Not an economic order quantity: EOQ needs an order cost and a holding cost,
 * and this system records neither. Inventing both to reach a more sophisticated
 * formula would be a worse answer dressed as a better one.
 *
 * <h2>Urgency</h2>
 *
 * <p>Measured against the lead time rather than against a fixed number of days,
 * because "urgent" means "you will run out before an order can arrive" and that
 * depends entirely on the supplier.
 */
@Service
public class ReorderService {

    private final DemandSeriesRepository seriesRepository;
    private final Forecaster forecaster;
    private final ForecastExplainer explainer;
    private final ForecastRepository forecasts;
    private final JdbcTemplate jdbc;

    public ReorderService(DemandSeriesRepository seriesRepository,
                          Forecaster forecaster,
                          ForecastExplainer explainer,
                          ForecastRepository forecasts,
                          @Qualifier("appDataSource") DataSource appDataSource) {
        this.seriesRepository = seriesRepository;
        this.forecaster = forecaster;
        this.explainer = explainer;
        this.forecasts = forecasts;
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /** One row bound for {@code reorder_recommendations}. */
    public record Recommendation(
            UUID productId,
            UUID locationId,
            UUID supplierId,
            Long forecastId,
            BigDecimal quantityOnHand,
            BigDecimal reorderPoint,
            BigDecimal recommendedQty,
            BigDecimal estimatedCost,
            LocalDate projectedStockoutOn,
            Urgency urgency,
            String rationale) {
    }

    /** What one recompute did. */
    public record RecomputeResult(int forecastsWritten, int recommendationsWritten) {
    }

    /**
     * Recomputes every product/location with demand history, stores each
     * forecast as current, and refreshes the open recommendations.
     */
    @Transactional
    public RecomputeResult recomputeAll() {
        TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException(
                "ReorderService requires a bound tenant — see DemandRollupJob's Javadoc "
                        + "and CLAUDE.md section 12."));

        Map<UUID, ProductFacts> facts = productFacts();
        List<DemandSeries> allSeries = seriesRepository.loadAll();

        int forecastCount = 0;
        int recommendationCount = 0;

        for (DemandSeries series : allSeries) {
            ProductFacts product = facts.get(series.productId());
            if (product == null) {
                // Deleted or untracked since the rollup ran. Its demand_daily
                // rows survive by design (the ledger is append-only), but a
                // recommendation to reorder a deleted product is noise.
                continue;
            }

            Forecast forecast = forecaster.forecast(series, LocalDate.now());
            long forecastId = forecasts.saveAsCurrent(forecast);
            forecastCount++;

            // Always cleared, whether or not a new one replaces it. A product
            // that has been restocked since yesterday must stop appearing on the
            // list — leaving the old row open would keep telling a shop owner to
            // order something they already ordered.
            forecasts.expireOpenRecommendation(series.productId(), series.locationId());

            Optional<Recommendation> recommendation =
                    recommendationFor(forecast, forecastId, product);
            if (recommendation.isPresent()) {
                forecasts.saveRecommendation(recommendation.get());
                recommendationCount++;
            }
        }
        return new RecomputeResult(forecastCount, recommendationCount);
    }

    /** The recommendation this forecast justifies, if any. */
    Optional<Recommendation> recommendationFor(Forecast forecast, Long forecastId,
                                               ProductFacts product) {
        if (!forecast.hasReorderPoint()) {
            return Optional.empty();
        }
        if (forecast.quantityOnHand().compareTo(forecast.reorderPoint()) > 0) {
            return Optional.empty();
        }

        BigDecimal target = forecast.reorderPoint().add(
                forecast.avgDailyDemand().multiply(
                        BigDecimal.valueOf(forecast.horizonDays()), DemandSeries.MC));
        BigDecimal quantity = target.subtract(forecast.quantityOnHand())
                .setScale(3, RoundingMode.HALF_UP);

        if (quantity.signum() <= 0) {
            // recommended_qty has a CHECK (> 0), and a recommendation to order
            // nothing is not a recommendation. Reachable when on-hand sits
            // exactly at the reorder point with a zero-demand horizon.
            return Optional.empty();
        }

        Urgency urgency = urgencyFor(forecast);
        BigDecimal estimatedCost = product.unitCost() == null ? null
                : quantity.multiply(product.unitCost(), DemandSeries.MC)
                        .setScale(2, RoundingMode.HALF_UP);

        String rationale = explainer.explainRecommendation(
                forecast, quantity, urgency, product.supplierName());

        return Optional.of(new Recommendation(
                forecast.productId(), forecast.locationId(),
                forecast.leadTime() == null ? null : forecast.leadTime().supplierId(),
                forecastId, forecast.quantityOnHand(), forecast.reorderPoint(),
                quantity, estimatedCost, forecast.projectedStockoutOn(), urgency, rationale));
    }

    /**
     * Against the lead time, not against a fixed number of days: "urgent" means
     * "you run out before an order can land", and a three-day supplier and a
     * three-week one make that the same shelf level's problem at very different
     * times.
     */
    private Urgency urgencyFor(Forecast forecast) {
        if (forecast.quantityOnHand().signum() <= 0) {
            return Urgency.CRITICAL;
        }
        if (forecast.daysOfCover() == null || forecast.leadTime() == null) {
            return Urgency.NORMAL;
        }
        BigDecimal leadTimeDays = forecast.leadTime().days();
        if (forecast.daysOfCover().compareTo(leadTimeDays) < 0) {
            // The order cannot arrive in time however fast it is placed.
            return Urgency.CRITICAL;
        }
        if (forecast.daysOfCover().compareTo(
                leadTimeDays.multiply(BigDecimal.valueOf(2))) < 0) {
            return Urgency.HIGH;
        }
        return Urgency.NORMAL;
    }

    /** Catalogue facts a recommendation needs that a demand series does not carry. */
    public record ProductFacts(UUID productId, String sku, String name,
                               String supplierName, BigDecimal unitCost) {
    }

    private Map<UUID, ProductFacts> productFacts() {
        List<ProductFacts> rows = jdbc.query("""
                SELECT p.id, p.sku, p.name,
                       s.name       AS supplier_name,
                       COALESCE(ps.unit_cost, p.cost_price) AS unit_cost
                FROM products p
                LEFT JOIN product_suppliers ps
                       ON ps.tenant_id = p.tenant_id AND ps.product_id = p.id
                      AND ps.is_preferred
                LEFT JOIN suppliers s
                       ON s.tenant_id = ps.tenant_id AND s.id = ps.supplier_id
                WHERE p.deleted_at IS NULL AND p.is_tracked
                """,
                (rs, rowNum) -> new ProductFacts(
                        rs.getObject("id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getString("supplier_name"),
                        rs.getBigDecimal("unit_cost")));

        Map<UUID, ProductFacts> byId = new java.util.HashMap<>();
        for (ProductFacts row : rows) {
            byId.put(row.productId(), row);
        }
        return byId;
    }

    /** The forecast a caller asked for, with its explanation attached. */
    public record ExplainedForecast(Forecast forecast, String explanation) {
    }

    public ExplainedForecast explainedForecast(UUID productId, UUID locationId) {
        Forecast forecast = forecaster.forecast(productId, locationId);
        ProductFacts product = productFacts().get(productId);
        return new ExplainedForecast(forecast,
                explainer.explain(forecast, product == null ? null : product.name()));
    }
}
