package com.example.inventory.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.example.inventory.auth.AuthProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Fixed-window rate limiting for the two unauthenticated endpoints that matter:
 * {@code /auth/register-tenant}, which creates rows, and {@code /auth/login},
 * which is the obvious credential-stuffing target.
 *
 * <p>Brought forward from M9 into M1 deliberately. An unauthenticated endpoint
 * that writes to the database is not something to leave unprotected until the
 * hardening milestone — the cost of adding it now is this class.
 *
 * <h2>Deliberately no dependency</h2>
 *
 * <p>Bucket4j or Resilience4j would be the obvious choice and both are heavier
 * than the problem. This is a counter in a map with a window, which is all a
 * fixed-window limiter is.
 *
 * <h2>Known limitations — read before relying on this</h2>
 *
 * <ul>
 *   <li><strong>Per instance, not per cluster.</strong> Two application
 *       instances mean twice the effective limit. Distributed limiting needs
 *       shared state (Redis, or a table) and belongs in M9.</li>
 *   <li><strong>Keyed on the client address</strong>, which is shared by
 *       everyone behind a NAT and trivially rotated by anyone with a botnet.
 *       It raises the cost of the easy attack; it does not stop a determined
 *       one.</li>
 *   <li><strong>Fixed window, not sliding.</strong> A caller can spend the full
 *       allowance at the end of one window and again at the start of the next.
 *       Acceptable for these limits; not for anything finer-grained.</li>
 * </ul>
 *
 * <p>{@code X-Forwarded-For} is <em>not</em> consulted. It is caller-supplied,
 * so honouring it without a trusted proxy in front would let anyone reset their
 * own limit by inventing a header. When a proxy is deployed, configure
 * {@code server.forward-headers-strategy} and let the servlet container resolve
 * the address — do not parse it here.
 */
@Component
public class AuthRateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AuthProperties.RateLimit limits;

    public AuthRateLimiter(AuthProperties properties) {
        this.limits = properties.rateLimit();
    }

    private static final class Window {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant resetsAt;

        Window(Instant resetsAt) {
            this.resetsAt = resetsAt;
        }
    }

    public void checkRegistration(HttpServletRequest request) {
        check("register:" + clientKey(request), limits.registrationsPerHour(), Duration.ofHours(1),
                "Too many registration attempts. Try again later.");
    }

    public void checkLogin(HttpServletRequest request) {
        check("login:" + clientKey(request), limits.loginAttemptsPerMinute(), Duration.ofMinutes(1),
                "Too many login attempts. Try again shortly.");
    }

    private void check(String key, int permitted, Duration window, String message) {
        Instant now = Instant.now();

        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || now.isAfter(existing.resetsAt)) {
                return new Window(now.plus(window));
            }
            return existing;
        });

        if (current.count.incrementAndGet() > permitted) {
            Duration retryAfter = Duration.between(now, current.resetsAt);
            throw new RateLimitedException(
                    message, retryAfter.isNegative() ? Duration.ZERO : retryAfter);
        }

        // Unbounded growth would be a memory leak on an endpoint anyone can
        // reach, which would turn a rate limiter into a denial-of-service
        // vector. Expired windows are cleared opportunistically rather than by
        // a scheduled job.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now.isAfter(entry.getValue().resetsAt));
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }
}
