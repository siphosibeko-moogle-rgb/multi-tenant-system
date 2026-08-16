package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.SqlStates;
import com.example.inventory.web.NotFoundException;

/**
 * The only class that writes {@code stock_movements} (CLAUDE.md T5).
 *
 * <p>Sales, purchasing, transfers and stocktakes all come through here. Keeping
 * one writer is what makes the ledger auditable: every row in the table was
 * produced by this method, so there is one place to read to know what can ever
 * appear in it.
 *
 * <h2>The trigger enforces the balance, not this class</h2>
 *
 * <p>{@code apply_stock_movement} (V1) applies each row to {@code product_stock}
 * with an upsert and raises {@code check_violation} if the result would go
 * negative and the product does not allow it. That upsert takes a row lock, so
 * the read of the current balance and the write of the new one happen inside the
 * same lock — which is the only way two simultaneous sales of the last unit can
 * be adjudicated correctly.
 *
 * <p>So this class <strong>does not check stock before inserting</strong>. It
 * cannot, safely: a {@code SELECT quantity_on_hand} followed by an
 * {@code INSERT} is a read-then-write race, and under concurrency both callers
 * read "1 available" and both proceed. The insert <em>is</em> the check. The only
 * reads here happen after a refusal, to fill in an error message.
 *
 * <p>Nor does it retry. A refusal means the stock genuinely was not there; trying
 * again cannot make it appear, and a retry loop around an oversell would turn a
 * clean 409 into a slow one. {@code ConcurrentOversellTest} drives 20 real
 * threads at a 10-unit product and asserts exactly 10 succeed — it is testing
 * this class's restraint as much as the trigger.
 *
 * <h2>Append-only</h2>
 *
 * <p>There is no update method and no delete method, and there will not be
 * (T4). Corrections are new signed rows. {@code product_stock} is never written
 * from Java at all — the trigger maintains it, and writing to it directly would
 * make the cache disagree with the ledger it is derived from.
 */
@Service
public class StockLedgerService {

    /**
     * SQLSTATE {@code check_violation}. The trigger raises it for an oversell,
     * but the same state also covers every CHECK constraint on the table, so the
     * message is what separates them.
     */
    private static final String CHECK_VIOLATION = SqlStates.CHECK_VIOLATION;

    /** The literal from {@code apply_stock_movement}'s RAISE EXCEPTION in V1. */
    private static final String OVERSELL_MESSAGE = "Insufficient stock for product";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public StockLedgerService(@Qualifier("appDataSource") DataSource appDataSource,
                              TransactionTemplate transactions) {
        this.jdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
    }

    /**
     * What to append to the ledger.
     *
     * @param quantityDelta signed and non-zero — negative removes stock. The sign
     *                      must agree with the movement type, which V1's
     *                      {@code stock_movements_sign_matches_type} constraint
     *                      enforces rather than trusting the caller.
     */
    public record MovementRequest(
            UUID productId,
            UUID locationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal unitCost,
            String referenceType,
            UUID referenceId,
            String reason,
            OffsetDateTime occurredAt) {
    }

    public record PostedMovement(
            long id,
            UUID productId,
            UUID locationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal balanceAfter,
            BigDecimal unitCost,
            String reason,
            OffsetDateTime occurredAt) {
    }

    /**
     * Appends one movement and returns it with the resulting balance.
     *
     * <p>The insert and the balance read share a transaction, so the balance
     * returned is the one this movement produced rather than whatever a
     * concurrent movement left behind a moment later.
     */
    public PostedMovement post(MovementRequest request) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException(
                        "no tenant bound — a ledger write must run inside a tenant-bound request"));
        UUID actor = TenantContext.currentUserId().orElse(null);

        return transactions.execute(status -> {
            long id;
            try {
                id = insert(tenantId, actor, request);
            } catch (DataAccessException e) {
                throw translate(e, request);
            }

            BigDecimal balanceAfter = jdbc.queryForObject("""
                    SELECT quantity_on_hand FROM product_stock
                    WHERE product_id = ? AND location_id = ?
                    """, BigDecimal.class, request.productId(), request.locationId());

            return new PostedMovement(
                    id,
                    request.productId(),
                    request.locationId(),
                    request.movementType(),
                    request.quantityDelta(),
                    balanceAfter,
                    request.unitCost(),
                    request.reason(),
                    readOccurredAt(id));
        });
    }

    private long insert(UUID tenantId, UUID actor, MovementRequest request) {
        // tenant_id is written from the bound context, which is the same value
        // the RLS policy checks it against. It is never taken from the request —
        // AdjustmentRequest has no field for one (T1).
        return jdbc.queryForObject("""
                INSERT INTO stock_movements
                    (tenant_id, product_id, location_id, movement_type, quantity_delta,
                     unit_cost, reference_type, reference_id, reason, occurred_at, created_by)
                VALUES (?, ?, ?, ?::movement_type, ?, ?, ?, ?, ?,
                        COALESCE(?, now()), ?)
                RETURNING id
                """, Long.class,
                tenantId,
                request.productId(),
                request.locationId(),
                request.movementType(),
                request.quantityDelta(),
                request.unitCost(),
                request.referenceType(),
                request.referenceId(),
                request.reason(),
                request.occurredAt() == null ? null : java.sql.Timestamp.from(
                        request.occurredAt().toInstant()),
                actor);
    }

    private OffsetDateTime readOccurredAt(long id) {
        return jdbc.queryForObject(
                "SELECT occurred_at FROM stock_movements WHERE id = ?", OffsetDateTime.class, id);
    }

    /**
     * Turns the database's refusal into something the API layer can render.
     *
     * <p>Keyed on SQLSTATE plus the trigger's own message, not on exception type
     * — {@code DataIntegrityViolationException} covers every CHECK constraint on
     * the table, including {@code quantity_delta <> 0} and the sign/type
     * agreement rule, and reporting those as "insufficient stock" would send a
     * caller looking in entirely the wrong place.
     *
     * <p>A foreign key failure means the product or location does not exist
     * <em>in this tenant</em> — another tenant's id looks exactly the same from
     * here, because RLS hides the row, so it is a 404 rather than a 403 (T8).
     */
    private RuntimeException translate(DataAccessException e, MovementRequest request) {
        String sqlState = SqlStates.of(e);
        String message = SqlStates.rootMessage(e);

        if (CHECK_VIOLATION.equals(sqlState) && message != null
                && message.contains(OVERSELL_MESSAGE)) {
            return new InsufficientStockException(
                    request.productId(),
                    request.quantityDelta().abs(),
                    availableFor(request));
        }
        if (SqlStates.FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            return new NotFoundException("No such product or location in this business");
        }
        return e;
    }

    /**
     * The balance, read only to populate a 409 body.
     *
     * <p>By the time this runs the write has already been refused, so the number
     * is a courtesy for the client's error message and nothing depends on it
     * being current. Deliberately tolerant of finding no row at all: a product
     * that has never moved has no {@code product_stock} row, and reporting zero
     * is more useful than failing while building an error.
     */
    private BigDecimal availableFor(MovementRequest request) {
        try {
            return jdbc.query("""
                    SELECT quantity_available FROM product_stock
                    WHERE product_id = ? AND location_id = ?
                    """,
                    rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO,
                    request.productId(), request.locationId());
        } catch (DataAccessException ignored) {
            return BigDecimal.ZERO;
        }
    }
}
