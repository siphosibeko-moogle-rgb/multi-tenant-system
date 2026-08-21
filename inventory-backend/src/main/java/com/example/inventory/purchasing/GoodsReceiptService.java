package com.example.inventory.purchasing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderDetail;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptLine;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptRequest;
import com.example.inventory.web.ConflictException;
import com.example.inventory.web.NotFoundException;

/**
 * Receives stock against a purchase order. Posts {@code purchase_receipt}
 * movements through {@link StockLedgerService} — the sole writer of
 * {@code stock_movements} (T5) — and updates {@code purchase_order_items
 * .quantity_received} and the order's own status. Nothing here writes to
 * {@code stock_movements} or {@code product_stock} directly, and nothing
 * pre-checks stock availability: T12 is StockLedgerService's concern alone
 * and this class does not duplicate it.
 *
 * <p>Lead-time observation — the part {@code docs/adr/forecasting.md} depends
 * on — is the next step, not this one.
 *
 * <h2>Why the outstanding-quantity check needs its own lock</h2>
 *
 * <p>Unlike stock availability, nothing in the schema enforces "cannot
 * receive more than was ordered" — there is no trigger and no CHECK spanning
 * {@code quantity_ordered} and {@code quantity_received}. That means, unlike
 * T12, this check genuinely IS this class's job, and a read-then-write
 * pre-check would genuinely be racy: two concurrent receipts against the last
 * 3 outstanding units could each read "3 outstanding", each accept 3, and the
 * line would show -3 outstanding with no error anywhere.
 *
 * <p>The fix is the same one {@code SaleService.returnSale} uses for the
 * identical shape of problem: {@code SELECT ... FOR UPDATE} on the parent
 * {@code purchase_orders} row before touching any line, held for the rest of
 * the transaction. A second concurrent receipt against the same order blocks
 * on that lock rather than reading a stale outstanding figure, and by the
 * time it proceeds it reads figures the first receipt already committed.
 */
@Service
public class GoodsReceiptService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StockLedgerService ledger;
    private final PurchaseOrderService purchaseOrders;

    public GoodsReceiptService(@Qualifier("appDataSource") DataSource appDataSource,
                               TransactionTemplate transactions,
                               StockLedgerService ledger,
                               PurchaseOrderService purchaseOrders) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.ledger = ledger;
        this.purchaseOrders = purchaseOrders;
    }

    public PurchaseOrderDetail receive(UUID poId, ReceiptRequest request) {
        // No actor is threaded through here: StockLedgerService.postWithin
        // already resolves TenantContext.currentUserId() itself for
        // created_by, so passing it again would be a second source of truth
        // for the same value.
        return ledger.withAvailableFilledIn(() -> transactions.execute(status -> {
            var locked = jdbc.query(
                    "SELECT status::text AS status FROM purchase_orders "
                            + "WHERE id = ? AND status IN ('ordered', 'partial') FOR UPDATE",
                    (rs, i) -> rs.getString("status"), poId);

            if (locked.isEmpty()) {
                String current = currentStatus(poId);
                if (current == null) {
                    throw new NotFoundException("No such purchase order");
                }
                // Covers both halves of the contract's 409: draft (never
                // submitted) and cancelled land here exactly like 'received'
                // does — none of the three can receive stock.
                throw new ConflictException(
                        "This purchase order is " + current + " and cannot receive stock",
                        "po-not-receivable");
            }

            UUID locationId = poLocation(poId);
            OffsetDateTime receivedAt = request.receivedAt() != null ? request.receivedAt() : OffsetDateTime.now();

            for (ReceiptLine line : request.lines()) {
                receiveLine(poId, locationId, receivedAt, line);
            }

            boolean allReceived = allLinesFullyReceived(poId);
            if (allReceived) {
                jdbc.update("""
                        UPDATE purchase_orders SET status = 'received', received_at = ?
                        WHERE id = ?
                        """, java.sql.Timestamp.from(receivedAt.toInstant()), poId);
            } else {
                jdbc.update("UPDATE purchase_orders SET status = 'partial' WHERE id = ?", poId);
            }

            return purchaseOrders.read(poId).orElseThrow(() -> new NotFoundException("No such purchase order"));
        }));
    }

    private void receiveLine(UUID poId, UUID locationId, OffsetDateTime receivedAt, ReceiptLine line) {
        // Safe to read-then-write here specifically because the caller
        // already holds the parent row's lock for the rest of this
        // transaction — see the class Javadoc. Without that lock this would
        // be exactly the race T12 warns against, applied to a different
        // table.
        var current = jdbc.query("""
                SELECT poi.quantity_ordered, poi.quantity_received, poi.unit_cost, p.sku
                FROM purchase_order_items poi
                JOIN products p ON p.id = poi.product_id
                WHERE poi.purchase_order_id = ? AND poi.product_id = ?
                """, (rs, i) -> new Object[] {
                        rs.getBigDecimal("quantity_ordered"),
                        rs.getBigDecimal("quantity_received"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getString("sku"),
                }, poId, line.productId())
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "That product is not a line on this purchase order"));

        BigDecimal ordered = (BigDecimal) current[0];
        BigDecimal receivedSoFar = (BigDecimal) current[1];
        BigDecimal orderedUnitCost = (BigDecimal) current[2];
        String sku = (String) current[3];
        BigDecimal outstanding = ordered.subtract(receivedSoFar);

        if (line.quantityReceived().compareTo(outstanding) > 0) {
            throw new ConflictException(
                    "Cannot receive %s of %s — only %s remain outstanding on this order".formatted(
                            line.quantityReceived().stripTrailingZeros().toPlainString(),
                            sku,
                            outstanding.stripTrailingZeros().toPlainString()),
                    "receipt-exceeds-outstanding");
        }

        BigDecimal unitCost = line.unitCost() != null ? line.unitCost() : orderedUnitCost;

        jdbc.update("""
                UPDATE purchase_order_items SET quantity_received = quantity_received + ?
                WHERE purchase_order_id = ? AND product_id = ?
                """, line.quantityReceived(), poId, line.productId());

        // The only write to stock_movements anywhere in this class — T5.
        // Positive: stock coming in. The trigger enforces nothing here (a
        // receipt cannot oversell), so there is no availability check to NOT
        // duplicate, unlike a sale.
        ledger.postWithin(new MovementRequest(
                line.productId(),
                locationId,
                "purchase_receipt",
                line.quantityReceived(),
                unitCost,
                "purchase_order",
                poId,
                null,
                receivedAt));
    }

    private boolean allLinesFullyReceived(UUID poId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM purchase_order_items
                    WHERE purchase_order_id = ? AND quantity_received < quantity_ordered
                )
                """, Boolean.class, poId));
    }

    private String currentStatus(UUID poId) {
        return jdbc.query("SELECT status::text AS status FROM purchase_orders WHERE id = ?",
                        (rs, i) -> rs.getString("status"), poId)
                .stream().findFirst().orElse(null);
    }

    private UUID poLocation(UUID poId) {
        return jdbc.queryForObject(
                "SELECT location_id FROM purchase_orders WHERE id = ?", UUID.class, poId);
    }
}
