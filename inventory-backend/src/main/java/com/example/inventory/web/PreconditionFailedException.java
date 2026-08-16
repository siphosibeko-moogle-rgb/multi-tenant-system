package com.example.inventory.web;

/**
 * An {@code If-Match} precondition did not hold. Rendered as 412.
 *
 * <p>Means "the thing you read has changed since you read it", not "you may
 * not". The correct client response is to re-read and decide again, which is why
 * this is distinct from both 409 and 403.
 */
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException(String message) {
        super(message);
    }
}
