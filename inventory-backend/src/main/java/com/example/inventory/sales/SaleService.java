package com.example.inventory.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.sales.SalesDtos.SaleDetail;
import com.example.inventory.sales.SalesDtos.SaleLine;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.NotFoundException;

/**
 * Records a sale: the sale, its lines, and one {@code sale} movement per line.
 *
 * <p>Pulled forward from M4 so that M3's Android slice has a sale endpoint to
 * call. Deliberately minimal — void, returns and real sale numbering stay in M4.
 *
 * <h2>One transaction, or nothing</h2>
 *
 * <p>The sale row, every line, and every stock movement share one transaction
 * via {@link StockLedgerService#postWithin}. A sale whose third line oversells
 * must persist <em>nothing</em>: not the sale, not the first two lines, and not
 * the first two movements. Partially recorded sales are the worst outcome
 * available here, because the ledger is append-only (T4) — there is no update
 * that tidies them up afterwards, only a compensating row somebody has to notice
 * is needed.
 *
 * <p>Availability is still enforced by the trigger alone (T12). This class does
 * not pre-check any line.
 *
 * <h2>Idempotency</h2>
 *
 * <p>{@code clientRequestId} is the key, backed by V1's partial unique index
 * {@code sales_idempotency_uq}. A replay returns the original sale with 200 and
 * moves stock once.
 *
 * <p>Both the read-first path and the unique-violation path are implemented, and
 * the second is not redundant: two retries arriving together both find nothing,
 * both insert, and one loses on the index. Handling only the read would move
 * stock twice under exactly the conditions a flaky link produces.
 */
@Service
public class SaleService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final StockLedgerService ledger;

    public SaleService(@Qualifier("appDataSource") DataSource appDataSource,
                       TransactionTemplate transactions,
                       StockLedgerService ledger) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.ledger = ledger;
    }

    /** A recorded sale, and whether this call is the one that recorded it. */
    public record Recorded(SaleDetail sale, boolean replayed) {
    }

    public Recorded record(SaleWriteRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("no tenant bound"));
        UUID actor = TenantContext.currentUserId().orElse(null);

        if (request.clientRequestId() != null) {
            Optional<SaleDetail> existing = findByClientRequestId(request.clientRequestId());
            if (existing.isPresent()) {
                return new Recorded(existing.get(), true);
            }
        }

        try {
            // withAvailableFilledIn wraps the transaction, not the insert: the
            // balance can only be read once the refusal has ended it. Going
            // through postWithin rather than post means this wrapper is this
            // class's responsibility — without it every oversold sale returns a
            // 409 with `available` null, which is precisely the number the
            // cashier needs.
            return new Recorded(ledger.withAvailableFilledIn(
                    () -> transactions.execute(status -> insertSale(tenantId, actor, request))),
                    false);
        } catch (DuplicateKeyException e) {
            // Lost the race against a concurrent replay of the same
            // clientRequestId. The winner's sale is the answer; ours rolled back,
            // so stock moved exactly once.
            if (request.clientRequestId() != null) {
                return new Recorded(findByClientRequestId(request.clientRequestId())
                        .orElseThrow(() -> e), true);
            }
            throw e;
        }
    }

    private SaleDetail insertSale(UUID tenantId, UUID actor, SaleWriteRequest request) {
        UUID locationId = request.locationId() != null
                ? request.locationId()
                : defaultLocationId().orElseThrow(() -> new NotFoundException(
                        "This business has no default location; specify locationId"));

        UUID saleId = UUID.randomUUID();
        OffsetDateTime soldAt = request.soldAt() != null ? request.soldAt() : OffsetDateTime.now();
        BigDecimal saleDiscount = request.discountAmount() == null
                ? BigDecimal.ZERO : request.discountAmount();

        // The sale row first, so the lines and movements have something to
        // reference. Totals are zero here and updated once the lines are priced —
        // they cannot be known before the products are read.
        jdbc.update("""
                INSERT INTO sales (id, tenant_id, location_id, sale_number, status,
                                   customer_name, customer_phone, payment_method,
                                   discount_amount, sold_at, created_by, client_request_id)
                VALUES (?, ?, ?, ?, 'completed', ?, ?, ?, ?, ?, ?, ?)
                """,
                saleId, tenantId, locationId, nextSaleNumber(), request.customerName(),
                request.customerPhone(), request.paymentMethod(), saleDiscount,
                java.sql.Timestamp.from(soldAt.toInstant()), actor, request.clientRequestId());

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;

        for (var line : request.lines()) {
            var product = readProduct(line.productId());

            BigDecimal unitPrice = line.unitPrice() != null ? line.unitPrice() : product.sellingPrice();
            BigDecimal lineDiscount = line.discountAmount() == null
                    ? BigDecimal.ZERO : line.discountAmount();
            BigDecimal lineTotal = unitPrice.multiply(line.quantity())
                    .subtract(lineDiscount)
                    .setScale(2, RoundingMode.HALF_UP);

            jdbc.update("""
                    INSERT INTO sale_items (tenant_id, sale_id, product_id, quantity,
                                            unit_price, unit_cost, discount_amount, tax_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId, saleId, line.productId(), line.quantity(), unitPrice,
                    product.costPrice(), lineDiscount, product.taxRate());

            // One movement per line, inside this same transaction. If the trigger
            // refuses this line, everything above unwinds.
            ledger.postWithin(new MovementRequest(
                    line.productId(), locationId, "sale",
                    line.quantity().abs().negate(), product.costPrice(),
                    "sale", saleId, null, soldAt));

            subtotal = subtotal.add(lineTotal);
            tax = tax.add(lineTotal.multiply(product.taxRate()).setScale(2, RoundingMode.HALF_UP));
        }

        BigDecimal total = subtotal.subtract(saleDiscount).add(tax).setScale(2, RoundingMode.HALF_UP);

        jdbc.update("""
                UPDATE sales SET subtotal_amount = ?, tax_amount = ?, total_amount = ?
                WHERE id = ?
                """, subtotal, tax, total, saleId);

        return read(saleId).orElseThrow(() -> new IllegalStateException("sale vanished after insert"));
    }

    /**
     * A receipt number that is unique within the tenant.
     *
     * <p>Minimal on purpose: M4 owns real sale numbering (per-tenant sequences,
     * a human-friendly format, gap behaviour). This is time-based with a random
     * suffix so it satisfies the {@code (tenant_id, sale_number)} unique
     * constraint without pretending to be the final scheme.
     */
    private static String nextSaleNumber() {
        return "S-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }

    private record ProductPricing(BigDecimal sellingPrice, BigDecimal costPrice, BigDecimal taxRate) {
    }

    private ProductPricing readProduct(UUID productId) {
        // No tenant predicate: RLS scopes this, so another tenant's product id is
        // simply not found (T8).
        return jdbc.query("""
                SELECT selling_price, cost_price, tax_rate FROM products
                WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, i) -> new ProductPricing(
                        rs.getBigDecimal("selling_price"),
                        rs.getBigDecimal("cost_price"),
                        rs.getBigDecimal("tax_rate")), productId)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No such product"));
    }

    private Optional<UUID> defaultLocationId() {
        return jdbc.query("SELECT id FROM locations WHERE is_default LIMIT 1",
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    private Optional<SaleDetail> findByClientRequestId(UUID clientRequestId) {
        return jdbc.query("SELECT id FROM sales WHERE client_request_id = ?",
                        (rs, i) -> rs.getObject("id", UUID.class), clientRequestId)
                .stream().findFirst()
                .flatMap(this::read);
    }

    public Optional<SaleDetail> read(UUID saleId) {
        var sales = jdbc.query("""
                SELECT id, sale_number, status::text AS status, customer_name,
                       subtotal_amount, discount_amount, tax_amount, total_amount,
                       payment_method, sold_at, location_id, created_by
                FROM sales WHERE id = ?
                """, (rs, i) -> new Object[] {
                        rs.getObject("id", UUID.class),
                        rs.getString("sale_number"),
                        rs.getString("status"),
                        rs.getString("customer_name"),
                        rs.getBigDecimal("subtotal_amount"),
                        rs.getBigDecimal("discount_amount"),
                        rs.getBigDecimal("tax_amount"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("payment_method"),
                        rs.getObject("sold_at", OffsetDateTime.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("created_by", UUID.class),
                }, saleId);

        if (sales.isEmpty()) {
            return Optional.empty();
        }
        Object[] s = sales.get(0);

        List<SaleLine> lines = jdbc.query("""
                SELECT si.product_id, p.sku, p.name, si.quantity, si.unit_price,
                       si.discount_amount, si.line_total
                FROM sale_items si
                JOIN products p ON p.id = si.product_id
                WHERE si.sale_id = ?
                ORDER BY p.sku
                """, (rs, i) -> new SaleLine(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("discount_amount"),
                        rs.getBigDecimal("line_total")), saleId);

        return Optional.of(new SaleDetail(
                (UUID) s[0], (String) s[1], (String) s[2], (String) s[3],
                lines.size(),
                (BigDecimal) s[4], (BigDecimal) s[5], (BigDecimal) s[6], (BigDecimal) s[7],
                (String) s[8], (OffsetDateTime) s[9], (UUID) s[10], (UUID) s[11], lines));
    }
}
