package com.example.inventory.web;

import java.time.Duration;

/** Too many requests from this caller. Rendered as 429 with {@code Retry-After}. */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
