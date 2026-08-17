package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The movement would drive stock negative and the product does not permit it.
 * Rendered as 409 with {@code productId}, {@code requested} and {@code available}
 * in the problem body, as the contract's {@code InsufficientStock} response
 * defines.
 *
 * <p>This is raised <em>in response to</em> the database refusing the write, never
 * in place of it. The check lives in the {@code apply_stock_movement} trigger, so
 * the decision is made while holding the row lock that the upsert on
 * {@code product_stock} already takes. A Java-side "is there enough?" test before
 * inserting would be a read-then-write race that two concurrent sales would both
 * pass — which is exactly the bug {@code ConcurrentOversellTest} exists to catch.
 *
 * @param requested the absolute quantity the caller tried to remove
 * @param available what was on hand, read <em>after</em> the refusal purely to
 *                  fill in the error body — never used to decide anything
 */
public class InsufficientStockException extends RuntimeException {

    private final UUID productId;
    private final UUID locationId;
    private final BigDecimal requested;
    private final BigDecimal available;

    /**
     * Raised with {@code available} unknown.
     *
     * <p>It cannot be filled in at the point of failure: the refusal has aborted
     * the transaction, and PostgreSQL rejects every subsequent statement on that
     * connection until it ends. A read attempted there fails, and an earlier
     * version of this code caught that failure and substituted zero — so the
     * field was <em>always</em> zero, and the number a cashier would have acted
     * on was fiction. {@code StockLedgerService} fills it in after the rollback.
     */
    public InsufficientStockException(UUID productId, UUID locationId, BigDecimal requested) {
        this(productId, locationId, requested, null);
    }

    private InsufficientStockException(UUID productId, UUID locationId,
                                       BigDecimal requested, BigDecimal available) {
        super("Insufficient stock for product " + productId);
        this.productId = productId;
        this.locationId = locationId;
        this.requested = requested;
        this.available = available;
    }

    /** A copy carrying the balance, read once the transaction has ended. */
    public InsufficientStockException withAvailable(BigDecimal actuallyAvailable) {
        return new InsufficientStockException(productId, locationId, requested, actuallyAvailable);
    }

    public UUID productId() {
        return productId;
    }

    public UUID locationId() {
        return locationId;
    }

    public BigDecimal requested() {
        return requested;
    }

    /** Null only if the follow-up read also failed. */
    public BigDecimal available() {
        return available;
    }
}
