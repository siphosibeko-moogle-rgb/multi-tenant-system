package com.example.inventory.forecasting;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.inventory.forecasting.Forecaster.Forecast;

/**
 * Writes forecasts and reorder recommendations.
 *
 * <p>Hand-written SQL through the RLS-bound pool, so {@code current_tenant_id()}
 * supplies the tenant on every insert and the policy refuses anything else.
 *
 * <h2>Exactly one live forecast per product/location</h2>
 *
 * <p>{@code forecasts_current_uq} is a partial unique index on
 * {@code WHERE is_current}, so superseding is a two-step: clear the old flag,
 * then insert. Both happen in the caller's transaction — a recompute that
 * cleared the flag and then failed would leave a product with no current
 * forecast at all, which reads downstream as "never forecast" rather than as
 * "the last run broke".
 *
 * <p>Superseded rows are kept, not deleted. {@code forecast_accuracy} scores
 * past predictions against what actually happened (step 5), and a forecast
 * deleted the moment it stopped being current could never be scored.
 */
@Repository
public class ForecastRepository {

    private final JdbcTemplate jdbc;

    public ForecastRepository(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /**
     * Supersedes any current forecast for this product/location and stores this
     * one as current.
     *
     * @return the new row's id, for {@code reorder_recommendations.forecast_id}
     */
    public long saveAsCurrent(Forecast forecast) {
        jdbc.update("""
                UPDATE forecasts SET is_current = false
                WHERE product_id = ? AND location_id = ? AND is_current
                """, forecast.productId(), forecast.locationId());

        return jdbc.queryForObject("""
                INSERT INTO forecasts
                    (tenant_id, product_id, location_id, method, history_days, history_from,
                     history_to, avg_daily_demand, demand_stddev, horizon_days, forecast_qty,
                     days_of_cover, projected_stockout_on, reorder_point, service_level,
                     confidence, is_current)
                VALUES (current_tenant_id(), ?, ?, CAST(? AS forecast_method), ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, true)
                RETURNING id
                """,
                Long.class,
                forecast.productId(),
                forecast.locationId(),
                forecast.method().dbValue(),
                forecast.selection().historyDays(),
                forecast.historyFrom(),
                forecast.historyTo(),
                forecast.avgDailyDemand(),
                forecast.demandStddev(),
                forecast.horizonDays(),
                forecast.forecastQty(),
                forecast.daysOfCover(),
                forecast.projectedStockoutOn(),
                forecast.reorderPoint(),
                forecast.serviceLevel(),
                forecast.confidence());
    }

    /**
     * Replaces the open recommendation for a product/location, if any.
     *
     * <p>{@code reorder_recommendations_open_uq} permits one open row per
     * product/location, so a recompute must clear the previous one rather than
     * insert alongside it. Expiring rather than deleting: the old advice was
     * genuinely given, and a shop owner who acted on yesterday's number should
     * still be able to see what it said.
     */
    public void expireOpenRecommendation(UUID productId, UUID locationId) {
        jdbc.update("""
                UPDATE reorder_recommendations
                SET status = 'expired', resolved_at = now()
                WHERE product_id = ? AND location_id = ? AND status = 'open'
                """, productId, locationId);
    }

    public UUID saveRecommendation(ReorderService.Recommendation recommendation) {
        return jdbc.queryForObject("""
                INSERT INTO reorder_recommendations
                    (tenant_id, product_id, location_id, supplier_id, forecast_id,
                     quantity_on_hand, reorder_point, recommended_qty, estimated_cost,
                     projected_stockout_on, rationale, status)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open')
                RETURNING id
                """,
                UUID.class,
                recommendation.productId(),
                recommendation.locationId(),
                recommendation.supplierId(),
                recommendation.forecastId(),
                recommendation.quantityOnHand(),
                recommendation.reorderPoint(),
                recommendation.recommendedQty(),
                recommendation.estimatedCost(),
                recommendation.projectedStockoutOn(),
                recommendation.rationale());
    }
}
