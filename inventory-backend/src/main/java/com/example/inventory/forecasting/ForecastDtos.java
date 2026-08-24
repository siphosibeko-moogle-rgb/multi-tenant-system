package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response and request shapes for the forecasting endpoints, matching
 * {@code docs/openapi.yaml}.
 *
 * <p>Records, and DTOs only — no entity crosses the controller boundary (T6).
 * Every field the contract marks {@code required} is non-null on every path that
 * builds one of these; {@code ResponseRequiredFieldsHttpTest} reads the contract
 * at run time and checks that over HTTP rather than trusting it here.
 */
public final class ForecastDtos {

    private ForecastDtos() {
    }

    /** The contract's {@code Forecast}. */
    public record ForecastResponse(
            UUID productId,
            UUID locationId,
            String method,
            OffsetDateTime generatedAt,
            Integer historyDays,
            BigDecimal avgDailyDemand,
            BigDecimal demandStddev,
            Integer horizonDays,
            BigDecimal forecastQty,
            BigDecimal daysOfCover,
            LocalDate projectedStockoutOn,
            BigDecimal reorderPoint,
            BigDecimal serviceLevel,
            BigDecimal confidence) {
    }

    /** One day of the demand series behind a forecast. */
    public record HistoryPoint(LocalDate day, BigDecimal unitsSold, boolean hadStockout) {
    }

    /**
     * The contract's {@code ForecastDetail} — a {@code Forecast} plus the
     * explanation and the history behind it.
     *
     * <p>Flattened rather than nested: the contract composes it with
     * {@code allOf}, which is a schema-level union and serializes as one flat
     * object. A nested {@code forecast} field would validate against nothing the
     * contract declares.
     */
    public record ForecastDetailResponse(
            UUID productId,
            UUID locationId,
            String method,
            OffsetDateTime generatedAt,
            Integer historyDays,
            BigDecimal avgDailyDemand,
            BigDecimal demandStddev,
            Integer horizonDays,
            BigDecimal forecastQty,
            BigDecimal daysOfCover,
            LocalDate projectedStockoutOn,
            BigDecimal reorderPoint,
            BigDecimal serviceLevel,
            BigDecimal confidence,
            String explanation,
            List<HistoryPoint> history) {
    }

    public record ForecastPage(List<ForecastResponse> items, String nextCursor) {
    }

    /** The contract's {@code ReorderRecommendation}. */
    public record RecommendationResponse(
            UUID id,
            UUID productId,
            String sku,
            String name,
            UUID locationId,
            UUID supplierId,
            String supplierName,
            BigDecimal quantityOnHand,
            BigDecimal reorderPoint,
            BigDecimal recommendedQty,
            BigDecimal estimatedCost,
            LocalDate projectedStockoutOn,
            String urgency,
            String rationale,
            String status,
            OffsetDateTime createdAt) {
    }

    public record RecommendationPage(List<RecommendationResponse> items, String nextCursor) {
    }

    /** @param productIds omit or leave empty to recompute the whole catalogue */
    public record RecomputeRequest(List<UUID> productIds) {
    }

    /**
     * The contract's 202 body.
     *
     * @param jobId          a correlation id for the logs of this run
     * @param queuedProducts how many product/location series the run covered
     */
    public record RecomputeAccepted(UUID jobId, int queuedProducts) {
    }

    public record DismissRequest(String reason) {
    }
}
