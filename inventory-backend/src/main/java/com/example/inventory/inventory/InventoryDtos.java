package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Shapes for {@code /inventory*}, mirroring {@code docs/openapi.yaml}.
 *
 * <p>Money and quantities are {@link BigDecimal} throughout — never
 * {@code double} (T7). A binary float cannot represent 0.1, and a ledger whose
 * balances drift by fractions of a unit is worse than no ledger.
 *
 * <p>No request here carries a tenant id (T1).
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    /**
     * @param quantityDelta signed and non-zero — {@code -2} for two damaged units.
     *                      There is deliberately no "set quantity to N" field:
     *                      corrections are new signed rows, so the audit trail
     *                      cannot be rewritten (T4).
     * @param locationId    optional; defaults to the tenant's default location
     */
    public record AdjustmentRequest(
            @NotNull UUID productId,
            UUID locationId,
            @NotNull BigDecimal quantityDelta,
            @NotNull @Size(min = 1, max = 500) String reason,
            @PositiveOrZero BigDecimal unitCost,
            OffsetDateTime occurredAt) {
    }

    /**
     * @param quantity positive. Direction comes from the two movement types the
     *                 service writes, not from a sign the caller supplies —
     *                 there is no such thing as a negative transfer, only a
     *                 transfer the other way.
     */
    public record TransferRequest(
            @NotNull UUID productId,
            @NotNull UUID fromLocationId,
            @NotNull UUID toLocationId,
            @NotNull @Positive BigDecimal quantity,
            @Size(max = 500) String reason,
            OffsetDateTime occurredAt) {
    }

    public record StockMovement(
            long id,
            UUID productId,
            String productName,
            UUID locationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal balanceAfter,
            BigDecimal unitCost,
            String referenceType,
            UUID referenceId,
            String reason,
            OffsetDateTime occurredAt,
            UUID createdBy,
            String createdByName) {
    }

    public record StockStatus(
            UUID productId,
            String sku,
            String name,
            UUID locationId,
            String locationName,
            BigDecimal quantityOnHand,
            BigDecimal quantityReserved,
            BigDecimal quantityAvailable,
            BigDecimal reorderPoint,
            String stockState,
            BigDecimal daysOfCover,
            OffsetDateTime updatedAt) {
    }

    public record MovementPage(List<StockMovement> items, String nextCursor) {
    }

    public record StockStatusPage(List<StockStatus> items, String nextCursor) {
    }
}
