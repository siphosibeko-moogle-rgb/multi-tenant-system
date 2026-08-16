package com.example.inventory.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant bound to the current thread.
 *
 * <p>Set once per request by {@link TenantFilter} from the verified {@code tid}
 * claim, read by {@link TenantConnectionProvider} when a connection is checked
 * out, and cleared unconditionally when the request ends.
 *
 * <h2>The one exception to T1</h2>
 *
 * <p>{@link #bindForRegistration(UUID)} exists for {@code POST /auth/register-tenant}
 * and for nothing else. Registration has to write a {@code tenants} row before
 * any token for that tenant can exist, so there is no claim to read — the id is
 * generated server-side and bound directly. See {@code TenantRegistrationService}
 * and CLAUDE.md section 12 for why that is safe.
 *
 * <p>Every other caller must go through {@link TenantFilter}. A tenant id that
 * came from a request body, a header or a query parameter must never reach
 * {@link #bind}.
 *
 * <h2>Why ThreadLocal</h2>
 *
 * <p>The alternative is passing a tenant id down every call chain, which fails
 * the moment one method forgets. This fails closed instead: nothing bound means
 * {@code app.tenant_id} is empty, and RLS returns zero rows rather than
 * everything.
 */
public final class TenantContext {

    /**
     * Deliberately not an InheritableThreadLocal. A child thread inheriting a
     * tenant is a leak waiting to happen — an @Async task or a parallel stream
     * would silently carry one request's tenant into work that outlives it.
     * Code that needs a tenant on another thread must bind it explicitly.
     */
    private static final ThreadLocal<TenantIdentity> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * Who the current request is acting as.
     *
     * @param tenantId the {@code tid} claim — never a request-supplied value
     * @param userId   the {@code sub} claim; null during registration, before a
     *                 user row exists
     * @param role     the {@code role} claim; null during registration
     */
    public record TenantIdentity(UUID tenantId, UUID userId, String role) {
    }

    /** Binds a tenant read from a verified token claim. */
    public static void bind(TenantIdentity identity) {
        CURRENT.set(identity);
    }

    /**
     * Binds a tenant id generated server-side, for registration only.
     *
     * <p>Separate from {@link #bind} so that the exception to T1 is greppable:
     * a search for callers of this method should return exactly one, in
     * {@code TenantRegistrationService}. If it ever returns two, the second one
     * is the bug.
     */
    public static void bindForRegistration(UUID newTenantId) {
        CURRENT.set(new TenantIdentity(newTenantId, null, null));
    }

    public static Optional<TenantIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<UUID> currentTenantId() {
        return current().map(TenantIdentity::tenantId);
    }

    public static Optional<UUID> currentUserId() {
        return current().map(TenantIdentity::userId);
    }

    /**
     * Required in a finally block by every binder. Threads are pooled: a context
     * left behind is the next request's tenant.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
