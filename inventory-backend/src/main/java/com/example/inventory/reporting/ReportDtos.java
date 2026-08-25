package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response records for the four M8 report endpoints.
 *
 * <p>Records, never entities (T6), and {@code BigDecimal} for every money and
 * quantity figure (T7) — a report is the one place a rounding error is both
 * invisible and acted upon.
 *
 * <p>Field names and nullability mirror {@code docs/openapi.yaml} exactly.
 * Everything the contract lists as {@code required} is non-null here by
 * construction; the two fields that may be null — {@code grossProfit} on a
 * sales-summary bucket and {@code grossMarginPct} on a top-products row — are
 * the two the contract deliberately leaves out of its required lists.
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    // ------------------------------------------------------------------
    // GET /reports/dashboard
    // ------------------------------------------------------------------

    /**
     * @param period    echoed back, so a client rendering a cached response can
     *                  say which window it is looking at
     * @param salesTotal net revenue, ex-tax, net of returns, voids excluded
     *                   (ADR §0) — not the till total
     * @param salesTrend always 14 days, regardless of {@code period} (ADR §3)
     * @param topProducts top 5 by units sold (ADR §4)
     */
    public record DashboardSummary(
            String period,
            BigDecimal salesTotal,
            long salesCount,
            BigDecimal grossProfit,
            BigDecimal inventoryValue,
            long lowStockCount,
            long outOfStockCount,
            long openRecommendationCount,
            List<TrendPoint> salesTrend,
            List<TopProductSummary> topProducts) {
    }

    public record TrendPoint(LocalDate day, BigDecimal revenue) {
    }

    /** The dashboard's slimmer top-products row — the contract gives it three fields. */
    public record TopProductSummary(UUID productId, String name, BigDecimal unitsSold) {
    }

    // ------------------------------------------------------------------
    // GET /reports/sales-summary
    // ------------------------------------------------------------------

    public record SalesSummaryResponse(List<SalesBucket> buckets) {
    }

    /**
     * @param periodStart the first day of the bucket, in the tenant's timezone
     * @param grossProfit nullable in the contract; always populated here, since
     *                    the cost snapshot is available for every line
     */
    public record SalesBucket(
            LocalDate periodStart,
            BigDecimal unitsSold,
            BigDecimal revenue,
            BigDecimal grossProfit) {
    }

    // ------------------------------------------------------------------
    // GET /reports/inventory-valuation
    // ------------------------------------------------------------------

    /**
     * @param asOf the date valued — echoed even when the caller omitted it, so a
     *             client never has to guess what "today" meant to the server in
     *             the tenant's timezone
     */
    public record InventoryValuation(
            BigDecimal totalCostValue,
            BigDecimal totalRetailValue,
            long productCount,
            LocalDate asOf) {
    }

    // ------------------------------------------------------------------
    // GET /reports/top-products
    // ------------------------------------------------------------------

    /**
     * @param grossMarginPct null when revenue is zero — a product that sold
     *                       nothing has no margin, and 0% would be a claim
     *                       rather than an absence (ADR §4)
     */
    public record TopProduct(
            UUID productId,
            String sku,
            String name,
            BigDecimal unitsSold,
            BigDecimal revenue,
            BigDecimal grossMarginPct) {
    }
}
