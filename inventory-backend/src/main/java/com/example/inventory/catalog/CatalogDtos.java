package com.example.inventory.catalog;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Shapes for {@code /products}, {@code /categories} and {@code /locations},
 * mirroring {@code docs/openapi.yaml}.
 *
 * <p>Money and quantities are {@link BigDecimal} (T7). No request carries a
 * tenant id (T1).
 */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record Product(
            UUID id,
            String sku,
            String barcode,
            String name,
            String description,
            UUID categoryId,
            String unitOfMeasure,
            BigDecimal costPrice,
            BigDecimal sellingPrice,
            BigDecimal taxRate,
            BigDecimal reorderPoint,
            BigDecimal quantityOnHand,
            BigDecimal quantityAvailable,
            String stockState,
            boolean isTracked,
            boolean isActive,
            boolean allowNegativeStock,
            OffsetDateTime updatedAt) {
    }

    /**
     * @param openingStock create-only. Posts an {@code opening_balance} movement
     *                     through {@code StockLedgerService} rather than writing
     *                     {@code product_stock} directly — a starting quantity is
     *                     still a thing that moved, and T5 admits no exceptions
     *                     for convenience.
     */
    public record ProductWriteRequest(
            @NotBlank @Size(min = 1, max = 64) String sku,
            @Size(max = 128) String barcode,
            @NotBlank @Size(min = 1, max = 300) String name,
            String description,
            UUID categoryId,
            @Size(max = 32) String unitOfMeasure,
            @PositiveOrZero BigDecimal costPrice,
            @PositiveOrZero BigDecimal sellingPrice,
            @PositiveOrZero @DecimalMax(value = "1.0", inclusive = false) BigDecimal taxRate,
            @PositiveOrZero BigDecimal reorderPoint,
            @Positive BigDecimal reorderQuantity,
            @PositiveOrZero BigDecimal safetyStock,
            Boolean isTracked,
            Boolean allowNegativeStock,
            OpeningStock openingStock) {

        public String unitOfMeasureOrDefault() {
            return unitOfMeasure == null || unitOfMeasure.isBlank() ? "each" : unitOfMeasure;
        }
    }

    public record OpeningStock(UUID locationId, @PositiveOrZero BigDecimal quantity,
                               @PositiveOrZero BigDecimal unitCost) {
    }

    public record Category(UUID id, String name, UUID parentId, long productCount) {
    }

    public record CategoryWriteRequest(@NotBlank @Size(max = 200) String name, UUID parentId) {
    }

    public record Location(UUID id, String name, String address, boolean isDefault,
                           boolean isActive) {
    }

    public record LocationWriteRequest(@NotBlank @Size(max = 200) String name, String address,
                                       Boolean isDefault) {
    }

    public record ProductPage(List<Product> items, String nextCursor) {
    }
}
