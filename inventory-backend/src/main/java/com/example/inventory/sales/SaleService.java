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
import com.example.inventory.web.ConflictException;
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

    /**
     * Voids a sale: returns everything still outstanding on it, and marks it
     * voided. The sale row itself is never deleted (T4's spirit — corrections
     * are new rows, not erasures).
     *
     * <h2>Exactly one concurrent void wins, and the database decides</h2>
     *
     * <p>The status change is a <strong>conditional</strong> UPDATE —
     * {@code WHERE id = ? AND status = 'completed'} — and the row count it
     * returns is the answer. Two simultaneous voids both reach it; PostgreSQL
     * serialises them on the row lock; the first sees one row updated, the
     * second re-evaluates its WHERE against the committed new status, matches
     * nothing, and is refused.
     *
     * <p>This is the same reasoning as T12: no read-then-write pre-check, no
     * "is it already voided?" SELECT followed by an UPDATE. That shape looks
     * safe and is not — both transactions would read 'completed', both would
     * proceed, and the stock would be returned twice. The UPDATE <em>is</em> the
     * check, and its row count is the only thing that can adjudicate.
     *
     * <h2>Why the status changes first</h2>
     *
     * <p>Before any movement is posted. The lock the UPDATE takes is what makes
     * the rest of the method exclusive, so taking it last would leave the window
     * this method exists to close.
     */
    public SaleDetail voidSale(UUID saleId, String reason) {
        UUID actor = TenantContext.currentUserId().orElse(null);

        return ledger.withAvailableFilledIn(() -> transactions.execute(status -> {
            int updated = jdbc.update(
                    "UPDATE sales SET status = 'voided', updated_at = now() "
                            + "WHERE id = ? AND status = 'completed'", saleId);

            if (updated == 0) {
                // Two very different reasons for zero rows, and they must not be
                // reported the same way. RLS hides another tenant's sale
                // entirely, so "no such row" covers both "does not exist" and
                // "belongs to someone else" — which is exactly T8: 404, never
                // 403, because a 403 would confirm the id names something real.
                String current = currentStatus(saleId);
                if (current == null) {
                    throw new NotFoundException("No such sale");
                }
                throw new ConflictException(
                        "This sale is " + current + " and cannot be voided",
                        "sale-not-voidable");
            }

            returnOutstanding(saleId, actor, reason, true, true, null);
            return read(saleId).orElseThrow(() -> new NotFoundException("No such sale"));
        }));
    }

    /**
     * Returns part of a sale: validates the requested quantities against what
     * is still outstanding, records the commercial event (and, unless this is
     * a damaged-goods return, the compensating movement), and moves the sale
     * to {@code partially_refunded} or {@code refunded} depending on what is
     * left afterwards.
     *
     * <h2>Why a row lock instead of void's conditional UPDATE</h2>
     *
     * <p>{@link #voidSale} knows its target status before it touches the row —
     * always {@code 'voided'} — so a single {@code UPDATE ... WHERE status =
     * 'completed'} can serve as both the guard and the lock in one statement.
     * A return does not have that luxury: whether this call ends in
     * {@code partially_refunded} or {@code refunded} depends on the outstanding
     * quantities <em>after</em> this return is recorded, which cannot be known
     * until {@link #returnOutstanding} has run.
     *
     * <p>So the guard and the status write are two separate statements here,
     * and what makes that safe under concurrency is the lock taken by the
     * first one: {@code SELECT ... FOR UPDATE} takes the same row-level lock
     * a conditional UPDATE would, and holds it for the rest of the
     * transaction. A second concurrent return against the same sale blocks on
     * that lock rather than reading a stale outstanding figure — the same
     * guarantee T12 describes for the ledger trigger's row lock, applied here
     * to a question the ledger itself has no opinion on: sale-return caps are
     * enforced by this method, not by any DB trigger, so the lock is the only
     * thing making the check non-racy.
     */
    public SaleDetail returnSale(UUID saleId, SalesDtos.ReturnRequest request) {
        UUID actor = TenantContext.currentUserId().orElse(null);

        return ledger.withAvailableFilledIn(() -> transactions.execute(status -> {
            List<String> locked = jdbc.query(
                    "SELECT status::text AS status FROM sales WHERE id = ? "
                            + "AND status IN ('completed', 'partially_refunded') FOR UPDATE",
                    (rs, i) -> rs.getString("status"), saleId);

            if (locked.isEmpty()) {
                // Same reasoning as voidSale: RLS hides another tenant's sale
                // entirely, so a missing row and someone else's row look
                // identical here, and both must be 404 (T8).
                String current = currentStatus(saleId);
                if (current == null) {
                    throw new NotFoundException("No such sale");
                }
                throw new ConflictException(
                        "This sale is " + current + " and cannot accept a return",
                        "sale-not-returnable");
            }

            // Quantity validation and the over-return refusal both live inside
            // returnOutstanding, shared with voidSale so the two paths cannot
            // disagree about what "already returned" means.
            returnOutstanding(saleId, actor, request.reason(),
                    request.restockOrDefault(), false, request.lines());

            BigDecimal stillOutstanding = outstandingByProduct(saleId).stream()
                    .map(Outstanding::outstanding)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String newStatus = stillOutstanding.signum() == 0 ? "refunded" : "partially_refunded";

            jdbc.update("UPDATE sales SET status = ?::sale_status, updated_at = now() WHERE id = ?",
                    newStatus, saleId);

            return read(saleId).orElseThrow(() -> new NotFoundException("No such sale"));
        }));
    }

    private String currentStatus(UUID saleId) {
        return jdbc.query("SELECT status::text AS status FROM sales WHERE id = ?",
                        (rs, i) -> rs.getString("status"), saleId)
                .stream().findFirst().orElse(null);
    }

    /**
     * Returns stock against a sale and records the commercial event.
     *
     * <p>Shared by the void path and (from step 2) the partial-return path, so
     * the two cannot disagree about what "already returned" means.
     *
     * @param requested per-product quantities, or null to return everything
     *                  still outstanding — which is what a void wants.
     * @param restock   false for damaged goods: the refund is recorded and
     *                  <strong>no ledger movement is posted at all</strong>.
     */
    private void returnOutstanding(UUID saleId, UUID actor, String reason,
                                   boolean restock, boolean isVoid,
                                   List<SalesDtos.ReturnLine> requested) {
        UUID groupId = UUID.randomUUID();
        UUID locationId = saleLocation(saleId);

        for (var outstanding : outstandingByProduct(saleId)) {
            BigDecimal quantity = requested == null
                    ? outstanding.outstanding()
                    : requested.stream()
                            .filter(l -> l.productId().equals(outstanding.productId()))
                            .map(SalesDtos.ReturnLine::quantity)
                            .findFirst().orElse(BigDecimal.ZERO);

            if (quantity.signum() <= 0) {
                continue;
            }
            if (quantity.compareTo(outstanding.outstanding()) > 0) {
                throw new ConflictException(
                        "Cannot return %s of %s — only %s remain on this sale".formatted(
                                quantity.stripTrailingZeros().toPlainString(),
                                outstanding.sku(),
                                outstanding.outstanding().stripTrailingZeros().toPlainString()),
                        "return-exceeds-sale");
            }

            // The commercial record, written whether or not stock moves. This is
            // the row that stops the same unit being refunded twice, and for
            // damaged goods it is the ONLY trace — see V5's note.
            jdbc.update("""
                    INSERT INTO sale_returns
                        (tenant_id, sale_id, product_id, quantity, restocked,
                         return_group_id, is_void, reason, created_by)
                    VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    saleId, outstanding.productId(), quantity, restock,
                    groupId, isVoid, reason, actor);

            if (!restock) {
                // Damaged goods. NO movement — not a movement of zero. The
                // ledger's CHECK (quantity_delta <> 0) would refuse one, and a
                // zero row would pollute the demand history M7 trains on with a
                // transaction that moved nothing. The goods are not going back
                // on the shelf, so the ledger has nothing to say.
                continue;
            }

            ledger.postWithin(new MovementRequest(
                    outstanding.productId(),
                    locationId,
                    "sale_return",
                    // Positive: stock coming back in. StockLedgerService is the
                    // only writer of stock_movements (T5) and this path is no
                    // exception — there is no direct INSERT anywhere here.
                    quantity,
                    null,
                    "sale",
                    saleId,
                    reason,
                    null));
        }
    }

    /** What each product on a sale still has outstanding, after prior returns. */
    private record Outstanding(UUID productId, String sku, BigDecimal outstanding) {
    }

    private List<Outstanding> outstandingByProduct(UUID saleId) {
        // Sold minus already-returned, per product. The subquery reads
        // sale_returns rather than stock_movements deliberately: a damaged-goods
        // return posts no movement, so a ledger-derived figure would report it
        // as never returned and allow a second refund of the same unit.
        return jdbc.query("""
                SELECT si.product_id,
                       p.sku,
                       si.quantity - COALESCE((
                           SELECT SUM(sr.quantity) FROM sale_returns sr
                           WHERE sr.sale_id = si.sale_id
                             AND sr.product_id = si.product_id
                       ), 0) AS outstanding
                FROM sale_items si
                JOIN products p ON p.id = si.product_id
                WHERE si.sale_id = ?
                ORDER BY p.sku
                """, (rs, i) -> new Outstanding(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getBigDecimal("outstanding")), saleId);
    }

    private UUID saleLocation(UUID saleId) {
        return jdbc.queryForObject(
                "SELECT location_id FROM sales WHERE id = ?", UUID.class, saleId);
    }

    private SaleDetail insertSale(UUID tenantId, UUID actor, SaleWriteRequest request) {
        UUID locationId = request.locationId() != null
                ? request.locationId()
                : defaultLocationId().orElseThrow(() -> new ConflictException(
                        // Not 404. Nothing was looked up and not found: the
                        // request named no location and the business has none
                        // marked default, which is a state of the tenant rather
                        // than a missing resource. As a 404 it was also
                        // indistinguishable from "that product does not exist",
                        // so a client could not tell a fixable configuration
                        // problem from a bad id.
                        "This business has no default location; specify locationId",
                        "no-default-location"));

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
                saleId, tenantId, locationId, nextSaleNumber(tenantId), request.customerName(),
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
     * A receipt number that is unique within the tenant, and sequential —
     * something a cashier can read out over the phone, unlike the
     * time-plus-random placeholder this replaces.
     *
     * <h2>Why an atomic UPDATE ... RETURNING on {@code tenants}, not a Postgres
     * SEQUENCE</h2>
     *
     * <p>A single {@code SEQUENCE} shared by every tenant is a global counter —
     * exactly what V7's migration comment rejects: the gap between two of a
     * tenant's receipt numbers estimates that tenant's sales volume to anyone
     * who sees two receipts, and a global counter leaks that across tenants
     * rather than containing it. One {@code SEQUENCE} per tenant would avoid
     * the leak but means dynamic DDL every time a tenant registers, which is a
     * heavier and stranger thing to own than a counter column already sitting
     * on a row that exists exactly once per tenant.
     *
     * <p>{@code tenants.next_sale_number} plays the same role a sequence would,
     * allocated with the same reasoning T12 gives for the ledger trigger: the
     * row lock the UPDATE takes is what adjudicates two sales racing for a
     * number, not a read-then-increment pre-check. Concurrent sales for the
     * SAME tenant serialise briefly on that tenant's row; concurrent sales for
     * DIFFERENT tenants do not contend at all, because each tenant's counter is
     * a different row with a different lock.
     */
    private String nextSaleNumber(UUID tenantId) {
        long assigned = jdbc.queryForObject("""
                UPDATE tenants SET next_sale_number = next_sale_number + 1
                WHERE id = ? RETURNING next_sale_number - 1
                """, Long.class, tenantId);
        return "S-%06d".formatted(assigned);
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
                       si.discount_amount, si.line_total,
                       COALESCE((
                           SELECT SUM(sr.quantity) FROM sale_returns sr
                           WHERE sr.sale_id = si.sale_id AND sr.product_id = si.product_id
                       ), 0) AS returned_quantity
                FROM sale_items si
                JOIN products p ON p.id = si.product_id
                WHERE si.sale_id = ?
                ORDER BY p.sku
                """, (rs, i) -> {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    BigDecimal returned = rs.getBigDecimal("returned_quantity");
                    return new SaleLine(
                            rs.getObject("product_id", UUID.class),
                            rs.getString("sku"),
                            rs.getString("name"),
                            quantity,
                            rs.getBigDecimal("unit_price"),
                            rs.getBigDecimal("discount_amount"),
                            rs.getBigDecimal("line_total"),
                            returned,
                            quantity.subtract(returned));
                }, saleId);

        return Optional.of(new SaleDetail(
                (UUID) s[0], (String) s[1], (String) s[2], (String) s[3],
                lines.size(),
                (BigDecimal) s[4], (BigDecimal) s[5], (BigDecimal) s[6], (BigDecimal) s[7],
                (String) s[8], (OffsetDateTime) s[9], (UUID) s[10], (UUID) s[11], lines));
    }
}
