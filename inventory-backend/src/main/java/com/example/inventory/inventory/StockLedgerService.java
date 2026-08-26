package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.SqlStates;
import com.example.inventory.web.ConflictException;
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

    /**
     * @param quantity   positive; the direction is supplied by the two movement
     *                   types, not by the caller's sign
     * @param transferId ties the pair together as {@code reference_id}, so the
     *                   two halves can be found as one event later
     */
    public record TransferRequest(
            UUID productId,
            UUID fromLocationId,
            UUID toLocationId,
            BigDecimal quantity,
            String reason,
            UUID transferId,
            OffsetDateTime occurredAt) {
    }

    /** The two paired movements a transfer produces. */
    public record Transfer(PostedMovement out, PostedMovement in) {
    }

    /**
     * @param createdBy     who posted it, from the token — never from the
     *                      request. Null only for a movement with no user behind
     *                      it, which is why the contract keeps it nullable.
     * @param createdByName resolved for display, so a client does not have to
     *                      fetch a user to render "who".
     */
    public record PostedMovement(
            long id,
            UUID productId,
            String productName,
            UUID locationId,
            String movementType,
            BigDecimal quantityDelta,
            BigDecimal balanceAfter,
            BigDecimal unitCost,
            String reason,
            OffsetDateTime occurredAt,
            UUID createdBy,
            String createdByName) {
    }

    /**
     * Appends one movement and returns it with the resulting balance.
     *
     * <p>The insert and the balance read share a transaction, so the balance
     * returned is the one this movement produced rather than whatever a
     * concurrent movement left behind a moment later.
     */
    public PostedMovement post(MovementRequest request) {
        return withAvailableFilledIn(() -> transactions.execute(status -> postWithin(request)));
    }

    /**
     * Runs a ledger write and, if it is refused for want of stock, fills in how
     * much there actually was.
     *
     * <p>The read has to happen out here, after the transaction has ended. Inside
     * it, the refusal has already aborted the transaction and PostgreSQL rejects
     * every further statement on that connection — so the balance simply cannot
     * be read at the point the exception is constructed. An earlier version tried
     * anyway, caught the resulting failure and substituted zero, which meant
     * `available` was always zero no matter what was on the shelf. The HTTP test
     * caught it; the service-level tests never would have, because they only
     * asserted that the exception carried the product id.
     *
     * <p><strong>Public because {@link #postWithin} is public.</strong> Any
     * caller that opens its own transaction and appends through {@code postWithin}
     * — multi-line sales, and whatever M4 adds next — bypasses {@link #post}, and
     * with it this fill-in. {@code SaleService} did exactly that and shipped a
     * 409 whose {@code available} was null on every oversold sale, while
     * {@code /inventory/adjustments} reported the number correctly. The two paths
     * looked identical from the outside and the divergence was invisible to every
     * test, because the only test that checked the number went through the path
     * that worked.
     *
     * <p>So: <strong>if you call {@code postWithin}, wrap the transaction in
     * this.</strong> Not optional, and not a detail — the number is the whole
     * point of the 409.
     */
    public <T> T withAvailableFilledIn(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (InsufficientStockException e) {
            if (e.available() != null) {
                throw e;
            }
            throw e.withAvailable(availableFor(e.productId(), e.locationId()));
        }
    }

    /**
     * Moves stock between two locations as {@code transfer_out} plus
     * {@code transfer_in}.
     *
     * <p><strong>One transaction for both.</strong> A transfer that wrote the
     * departure and then failed to write the arrival would destroy stock: the
     * source is decremented, the destination never credited, and the ledger says
     * so forever — there is no update to correct it (T4), only a compensating
     * row that somebody has to notice is needed. Sharing a transaction makes the
     * pair atomic, so a failure at either end leaves the ledger exactly as it
     * was.
     *
     * <p>This is why {@link #postWithin} exists. {@link #post} opens its own
     * transaction, which is right for a single movement and wrong here: two
     * calls to it would be two transactions, and the first would commit.
     */
    public Transfer transfer(TransferRequest request) {
        return withAvailableFilledIn(() -> transferWithin(request));
    }

    private Transfer transferWithin(TransferRequest request) {
        if (request.fromLocationId().equals(request.toLocationId())) {
            // Otherwise both movements hit the same product_stock row and net to
            // zero — a pair of ledger entries recording that nothing happened.
            throw new ConflictException(
                    "A transfer needs two different locations", "transfer-same-location");
        }

        return transactions.execute(status -> {
            // Out first: it is the movement that can be refused, so failing here
            // costs nothing. The reverse order would credit the destination and
            // then discover the source was empty.
            PostedMovement out = postWithin(new MovementRequest(
                    request.productId(), request.fromLocationId(), "transfer_out",
                    request.quantity().abs().negate(), null,
                    "transfer", request.transferId(), request.reason(), request.occurredAt()));

            PostedMovement in = postWithin(new MovementRequest(
                    request.productId(), request.toLocationId(), "transfer_in",
                    request.quantity().abs(), null,
                    "transfer", request.transferId(), request.reason(), request.occurredAt()));

            return new Transfer(out, in);
        });
    }

    /** A posted movement, and whether this call is the one that posted it. */
    public record Posted(PostedMovement movement, boolean replayed) {
    }

    /**
     * {@link #post} with an idempotency key — the adjustment path M8's offline
     * outbox replays.
     *
     * <p>The contract has declared {@code Idempotency-Key} on
     * {@code POST /inventory/adjustments} since v1 and the implementation
     * ignored it until M8, which made CLAUDE.md §4's "any endpoint that moves
     * stock or money accepts it" true of sales and false here. A stated
     * convention that is half-implemented is worse than either alternative,
     * because the statement is what a reader checks instead of the code.
     *
     * <p>It stops mattering academically the moment a queued adjustment can be
     * replayed: a request whose response was lost to a dropped connection is
     * sent again, and without a key the second attempt is a SECOND movement.
     * The ledger is append-only (T4), so the fix is another compensating row
     * that somebody has to notice is needed first.
     *
     * <p><strong>Both paths are implemented, and the second is not
     * redundant</strong> — the same reasoning as {@code SaleService}: two
     * retries arriving together both find nothing, both insert, and one loses
     * on {@code stock_movements_idempotency_uq}. Handling only the read would
     * move stock twice under exactly the conditions a flaky link produces.
     *
     * @param clientRequestId may be null, in which case this is an ordinary
     *                        non-idempotent post and a replay would double-post
     */
    public Posted post(MovementRequest request, UUID clientRequestId) {
        if (clientRequestId == null) {
            return new Posted(post(request), false);
        }

        Optional<PostedMovement> existing = findByClientRequestId(clientRequestId);
        if (existing.isPresent()) {
            return new Posted(existing.get(), true);
        }

        try {
            return new Posted(withAvailableFilledIn(
                    () -> transactions.execute(status -> postWithin(request, clientRequestId))),
                    false);
        } catch (DuplicateKeyException e) {
            // Lost the race against a concurrent replay of the same key. The
            // winner's movement is the answer; ours rolled back, so stock moved
            // exactly once.
            return new Posted(findByClientRequestId(clientRequestId).orElseThrow(() -> e), true);
        }
    }

    private Optional<PostedMovement> findByClientRequestId(UUID clientRequestId) {
        return jdbc.query("SELECT id FROM stock_movements WHERE client_request_id = ?",
                        (rs, i) -> rs.getLong("id"), clientRequestId)
                .stream().findFirst().map(this::readPosted);
    }

    /**
     * Appends one movement inside whatever transaction the caller has already
     * opened, and returns it with the resulting balance.
     *
     * <p>Deliberately has no transaction of its own, so that callers composing
     * several movements — a transfer here, a multi-line sale — get all or
     * nothing. Calling it outside a transaction would still work and would still
     * be atomic per statement; it simply would not compose.
     *
     * <p><strong>The caller must already be inside a transaction.</strong> Public
     * so {@code SaleService} can put one movement per line alongside the sale and
     * its lines in a single transaction; that does not weaken T5, because this
     * class is still the only one that issues an INSERT into
     * {@code stock_movements}. A caller that forgets the transaction gets
     * per-statement atomicity and a half-recorded sale on the second line's
     * refusal — which is what {@code SaleTest.aLineThatOversellsPersistsNothing}
     * exists to catch.
     */
    public PostedMovement postWithin(MovementRequest request) {
        return postWithin(request, null);
    }

    /** @param clientRequestId the idempotency key to stamp on the row; may be null */
    private PostedMovement postWithin(MovementRequest request, UUID clientRequestId) {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException(
                        "no tenant bound — a ledger write must run inside a tenant-bound request"));
        UUID actor = TenantContext.currentUserId().orElse(null);

        long id;
        try {
            id = insert(tenantId, actor, request, clientRequestId);
        } catch (DuplicateKeyException e) {
            // The idempotency index, not an oversell. Rethrown unchanged so
            // post(request, key) can answer it with the winner's movement —
            // translate() would turn it into an insufficient-stock problem and
            // report a duplicate request as an empty shelf.
            throw e;
        } catch (DataAccessException e) {
            throw translate(e, request);
        }

        BigDecimal balanceAfter = jdbc.queryForObject("""
                SELECT quantity_on_hand FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, request.productId(), request.locationId());

        Echo echo = readEcho(id);

        return new PostedMovement(
                id,
                request.productId(),
                echo.productName(),
                request.locationId(),
                request.movementType(),
                request.quantityDelta(),
                balanceAfter,
                request.unitCost(),
                request.reason(),
                echo.occurredAt(),
                echo.createdBy(),
                echo.createdByName());
    }

    private long insert(UUID tenantId, UUID actor, MovementRequest request) {
        return insert(tenantId, actor, request, null);
    }

    private long insert(UUID tenantId, UUID actor, MovementRequest request, UUID clientRequestId) {
        // tenant_id is written from the bound context, which is the same value
        // the RLS policy checks it against. It is never taken from the request —
        // AdjustmentRequest has no field for one (T1).
        return jdbc.queryForObject("""
                INSERT INTO stock_movements
                    (tenant_id, product_id, location_id, movement_type, quantity_delta,
                     unit_cost, reference_type, reference_id, reason, occurred_at, created_by,
                     client_request_id)
                VALUES (?, ?, ?, ?::movement_type, ?, ?, ?, ?, ?,
                        COALESCE(?, now()), ?, ?)
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
                actor,
                clientRequestId);
    }

    /**
     * Rebuilds a {@link PostedMovement} from a row that already exists — the
     * answer to an idempotent replay.
     *
     * <p>{@code balanceAfter} is the balance <em>now</em>, not the balance
     * immediately after this movement was originally posted. Those differ if
     * anything moved in between, and now is the honest answer: a client
     * replaying a request wants to know where the stock actually stands, and
     * reconstructing a historical balance would mean replaying the ledger to
     * this row for a number nobody asked for.
     */
    private PostedMovement readPosted(long id) {
        return jdbc.queryForObject("""
                SELECT m.id, m.product_id, p.name AS product_name, m.location_id,
                       m.movement_type::text AS movement_type, m.quantity_delta,
                       m.unit_cost, m.reason, m.occurred_at, m.created_by,
                       u.full_name AS created_by_name,
                       COALESCE(ps.quantity_on_hand, 0) AS balance_after
                FROM stock_movements m
                JOIN products p
                     ON p.tenant_id = m.tenant_id AND p.id = m.product_id
                LEFT JOIN users u
                     ON u.tenant_id = m.tenant_id AND u.id = m.created_by
                LEFT JOIN product_stock ps
                     ON ps.tenant_id = m.tenant_id AND ps.product_id = m.product_id
                    AND ps.location_id = m.location_id
                WHERE m.id = ?
                """,
                (rs, row) -> new PostedMovement(
                        rs.getLong("id"),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("product_name"),
                        rs.getObject("location_id", UUID.class),
                        rs.getString("movement_type"),
                        rs.getBigDecimal("quantity_delta"),
                        rs.getBigDecimal("balance_after"),
                        rs.getBigDecimal("unit_cost"),
                        rs.getString("reason"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name")),
                id);
    }

    /** The parts of a posted movement that only the database can answer. */
    private record Echo(OffsetDateTime occurredAt, String productName,
                        UUID createdBy, String createdByName) {
    }

    /**
     * Reads back what the insert produced: the stored timestamp, the product's
     * name, and who posted it.
     *
     * <p>This replaced a read of {@code occurred_at} alone. The write response
     * previously hardcoded {@code productName}, {@code createdBy} and
     * {@code createdByName} to null on the grounds that a caller echoing its own
     * write already knows what it sent — which is true of the product, and false
     * of the actor: the client sends no user id, the server takes it from the
     * token, so the client cannot fill it in and neither could anyone reading
     * the response later.
     *
     * <p>"Who moved this stock" is the first question asked when a physical
     * count disagrees with the system, and answering it from the write response
     * costs one already-necessary round trip. {@code productName} is also
     * <em>required</em> by the contract's StockMovement schema, so emitting null
     * there was a violation — one that
     * {@code ResponseRequiredFieldsHttpTest} missed because it exercised
     * {@code GET /inventory/movements}, which joins these columns, and not the
     * POST response, which did not.
     *
     * <p>{@code created_by} is nullable and stays so: a movement posted by a
     * background job has no user behind it. The LEFT JOIN keeps that case
     * working rather than dropping the row.
     */
    private Echo readEcho(long id) {
        return jdbc.queryForObject("""
                SELECT m.occurred_at,
                       p.name       AS product_name,
                       m.created_by,
                       u.full_name  AS created_by_name
                FROM stock_movements m
                JOIN products p
                     ON p.tenant_id = m.tenant_id AND p.id = m.product_id
                LEFT JOIN users u
                     ON u.tenant_id = m.tenant_id AND u.id = m.created_by
                WHERE m.id = ?
                """,
                (rs, row) -> new Echo(
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("product_name"),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("created_by_name")),
                id);
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
            // available is deliberately left unknown here — see
            // withAvailableFilledIn. Reading it now would fail: this
            // transaction is already aborted.
            return new InsufficientStockException(
                    request.productId(), request.locationId(), request.quantityDelta().abs());
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
    private BigDecimal availableFor(UUID productId, UUID locationId) {
        try {
            return jdbc.query("""
                    SELECT quantity_available FROM product_stock
                    WHERE product_id = ? AND location_id = ?
                    """,
                    rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO,
                    productId, locationId);
        } catch (DataAccessException ignored) {
            // A product that has never moved has no product_stock row, so zero
            // is the honest answer. This catch is now a genuine fallback rather
            // than the silent default it used to be.
            return BigDecimal.ZERO;
        }
    }
}
