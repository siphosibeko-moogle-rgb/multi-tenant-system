package com.example.inventory.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

/**
 * Authentication configuration, bound from {@code app.auth.*}.
 *
 * @param signingKey the HMAC secret. Required, with no default anywhere — a
 *                   default signing key is a signing key an attacker also has.
 *                   Must be at least 32 bytes for HS256; shorter keys are
 *                   rejected at startup by {@code SecurityConfig}.
 * @param issuer     the {@code iss} claim, verified on the way back in
 * @param ttl        token lifetimes
 * @param rateLimit  limits on the unauthenticated endpoints
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        String signingKey,
        @DefaultValue("inventory-backend") String issuer,
        @DefaultValue TokenTtl ttl,
        @DefaultValue RateLimit rateLimit) {

    /** HS256: symmetric, appropriate while one process both issues and verifies. */
    public static final MacAlgorithm SIGNING_ALGORITHM = MacAlgorithm.HS256;

    /** HS256 requires a key at least as long as the hash output. */
    public static final int MINIMUM_KEY_BYTES = 32;

    /**
     * @param access  15 minutes, per the contract in docs/openapi.yaml. Short
     *                because an access token cannot be revoked — nothing is
     *                looked up when one is presented, which is the point.
     * @param refresh 30 days, revocable, single-use, rotated on every use.
     */
    public record TokenTtl(
            @DefaultValue("15m") Duration access,
            @DefaultValue("30d") Duration refresh) {
    }

    /**
     * @param registrationsPerHour  per client address. Registration is
     *                              unauthenticated and creates rows, so it is
     *                              the cheapest way to fill the database.
     * @param loginAttemptsPerMinute per client address. Slows credential
     *                              stuffing without locking a real user out for
     *                              long.
     */
    public record RateLimit(
            @DefaultValue("5") int registrationsPerHour,
            @DefaultValue("10") int loginAttemptsPerMinute) {
    }
}
