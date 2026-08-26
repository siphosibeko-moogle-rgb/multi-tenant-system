package com.example.inventory.suppliers;

import java.math.BigDecimal;
import java.util.UUID;

/** Shapes for {@code /suppliers}, mirroring {@code docs/openapi.yaml}. */
public final class SupplierDtos {

    private SupplierDtos() {
    }

    /**
     * Measured from actual receipts, not the promised figure — the whole
     * point of {@code docs/adr/forecasting.md}.
     *
     * @param onTimeRate not computed yet — no "on time" definition has been
     *                    settled (against {@code expected_at}? against the
     *                    promised {@code lead_time_days}?) and the contract
     *                    marks the field nullable for exactly this reason.
     *                    Left null rather than guessed at.
     */
    /**
     * The contract's {@code Supplier} — the shape {@code SupplierDetail}
     * composes with {@code allOf}, without the two fields that cost a query
     * each.
     *
     * <p>Added for {@code GET /sync/changes}, which carries suppliers in bulk.
     * {@code SupplierDetail} would mean two extra round trips per supplier
     * (observed lead time, product count) to populate fields the contract does
     * not put in the sync feed at all.
     */
    public record Supplier(
            UUID id,
            String name,
            String contactName,
            String email,
            String phone,
            Integer leadTimeDays,
            BigDecimal minOrderValue,
            boolean isActive) {
    }

    public record ObservedLeadTime(
            BigDecimal averageDays,
            BigDecimal stddevDays,
            int sampleSize,
            BigDecimal onTimeRate) {
    }

    public record SupplierDetail(
            UUID id,
            String name,
            String contactName,
            String email,
            String phone,
            Integer leadTimeDays,
            BigDecimal minOrderValue,
            boolean isActive,
            String address,
            String notes,
            ObservedLeadTime observedLeadTime,
            int productCount) {
    }
}
