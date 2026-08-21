package com.example.inventory.purchasing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Shapes for {@code /purchase-orders}, mirroring {@code docs/openapi.yaml}.
 *
 * <p>Money and quantities are {@link BigDecimal} (T7). No request carries a
 * tenant id (T1).
 */
public final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {
    }

    /**
     * @param locationId            omit to use the tenant's default location
     * @param fromRecommendationIds marks those {@code reorder_recommendations}
     *                              rows 'ordered'. Accepted and applied now
     *                              even though nothing produces a
     *                              recommendation until M7 — a caller that
     *                              already has ids (imported, or entered by
     *                              hand) gets the contract's behaviour rather
     *                              than a field that silently does nothing.
     */
    public record PurchaseOrderWriteRequest(
            @NotNull UUID supplierId,
            UUID locationId,
            LocalDate expectedAt,
            String notes,
            List<UUID> fromRecommendationIds,
            @NotEmpty @Valid List<PurchaseOrderLineRequest> lines) {
    }

    /** @param unitCost omit to use the product's current cost price */
    public record PurchaseOrderLineRequest(
            @NotNull UUID productId,
            @NotNull @Positive BigDecimal quantityOrdered,
            @PositiveOrZero BigDecimal unitCost) {
    }

    /** {@code GET /purchase-orders} row shape — the contract's lighter {@code PurchaseOrder}, no lines. */
    public record PurchaseOrder(
            UUID id,
            String poNumber,
            UUID supplierId,
            String supplierName,
            String status,
            OffsetDateTime orderedAt,
            LocalDate expectedAt,
            OffsetDateTime receivedAt,
            BigDecimal totalAmount,
            int lineCount) {
    }

    public record PurchaseOrderLine(
            UUID productId,
            String sku,
            String name,
            BigDecimal quantityOrdered,
            BigDecimal quantityReceived,
            BigDecimal quantityOutstanding,
            BigDecimal unitCost,
            BigDecimal lineTotal) {
    }

    public record PurchaseOrderDetail(
            UUID id,
            String poNumber,
            UUID supplierId,
            String supplierName,
            String status,
            OffsetDateTime orderedAt,
            LocalDate expectedAt,
            OffsetDateTime receivedAt,
            BigDecimal totalAmount,
            int lineCount,
            UUID locationId,
            String notes,
            List<PurchaseOrderLine> lines) {
    }

    public record PurchaseOrderPage(List<PurchaseOrder> items, String nextCursor) {
    }
}
