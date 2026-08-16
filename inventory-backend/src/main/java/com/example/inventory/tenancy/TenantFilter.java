package com.example.inventory.tenancy;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Binds {@link TenantContext} for the duration of a request, from the verified
 * {@code tid} claim and from nothing else.
 *
 * <h2>The whole contract of this class</h2>
 *
 * <p>This filter reads exactly one thing off the request: the
 * {@link Authentication} that the resource-server filter has already produced by
 * verifying the token's signature. It never touches {@code request.getHeader},
 * {@code getParameter}, the path, or the body. That is deliberate and it is
 * asserted by {@code TenantFilterClaimSourceTest} — a tenant id that a caller
 * could supply is a tenant id a caller could forge, and the entire isolation
 * story rests on that not being possible (CLAUDE.md T1).
 *
 * <p>If you are ever tempted to add an {@code X-Tenant-Id} header here for
 * testing or for an admin tool, the answer is no. Issue a token instead.
 *
 * <h2>Order</h2>
 *
 * <p>Runs after Spring Security's chain has authenticated the request, because
 * it depends on the {@code Jwt} being verified — an unverified token's claims
 * are attacker-controlled strings. Anything unauthenticated (the {@code /auth/*}
 * endpoints) simply passes through with nothing bound, which leaves
 * {@code app.tenant_id} empty and RLS returning zero rows.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class TenantFilter extends OncePerRequestFilter {

    static final String TENANT_CLAIM = "tid";
    static final String ROLE_CLAIM = "role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        bindFromVerifiedTokenOnly();
        try {
            chain.doFilter(request, response);
        } finally {
            // Unconditional. Servlet threads are pooled, so a context left
            // behind becomes the next request's tenant — including a request
            // that has no token at all.
            TenantContext.clear();
        }
    }

    /**
     * The one and only path by which a tenant is bound for a normal request.
     *
     * <p>Note what is <em>not</em> a parameter of this method: the request. It
     * cannot read a header or a body even by accident.
     */
    private static void bindFromVerifiedTokenOnly() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return;
        }

        String tenantClaim = jwt.getClaimAsString(TENANT_CLAIM);
        String subject = jwt.getSubject();
        if (tenantClaim == null || subject == null) {
            // A signed token missing tid is not a token this system issued for a
            // tenant. Binding nothing is the safe reading: the request proceeds
            // and sees zero rows, rather than proceeding with a guess.
            return;
        }

        TenantContext.bind(new TenantContext.TenantIdentity(
                UUID.fromString(tenantClaim),
                UUID.fromString(subject),
                jwt.getClaimAsString(ROLE_CLAIM)));
    }
}
