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
    private final BigDecimal requested;
    private final BigDecimal available;

    public InsufficientStockException(UUID productId, BigDecimal requested, BigDecimal available) {
        super("Insufficient stock for product " + productId);
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public UUID productId() {
        return productId;
    }

    public BigDecimal requested() {
        return requested;
    }

    public BigDecimal available() {
        return available;
    }
}
