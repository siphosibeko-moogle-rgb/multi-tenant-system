package com.example.inventory.auth;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.AuthDtos.LoginRequest;
import com.example.inventory.auth.AuthDtos.TenantSummary;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.UnauthorizedException;

/**
 * Authenticates an email and password, and resolves which tenant they belong to.
 *
 * <h2>Why this class needs the login pool</h2>
 *
 * <p>This is the chicken-and-egg case V3 exists for. The caller supplies an
 * email; the tenant is the <em>answer</em>. On the application pool with nothing
 * bound, {@code current_tenant_id()} is NULL and the {@code users} policy matches
 * no rows, so the lookup could never succeed. {@code loginDataSource} connects as
 * {@code inventory_login}, whose {@code login_read} policy returns every row of
 * {@code tenants} and {@code users} and nothing else at all.
 *
 * <p>Once the tenant is known, everything switches back to the application pool
 * with {@code app.tenant_id} bound. The login pool is used for exactly one
 * SELECT and is read-only besides.
 *
 * <h2>Wrong password and unknown email must be indistinguishable</h2>
 *
 * <p>In the response body and in the timing — a milestone acceptance criterion,
 * and the reason for the deliberate dummy hash below. Skipping bcrypt when no
 * user is found would leak account existence through a response-time difference
 * of two orders of magnitude, which is trivially measurable over a network and
 * turns the login endpoint into an account enumerator.
 */
@Service
public class LoginService {

    /**
     * A real bcrypt hash of a value nobody knows, verified against when no user
     * matches, purely to spend the same time as a genuine check.
     *
     * <p>It has to be a valid hash of the same cost as the encoder's, or the
     * comparison short-circuits and the timing tell reappears.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JdbcTemplate loginJdbc;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final CurrentUserService currentUserService;

    public LoginService(@Qualifier("loginDataSource") DataSource loginDataSource,
                        PasswordEncoder passwordEncoder,
                        TokenIssuer tokenIssuer,
                        CurrentUserService currentUserService) {
        this.loginJdbc = new JdbcTemplate(loginDataSource);
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.currentUserService = currentUserService;
    }

    /** A candidate account, as seen unscoped through the login pool. */
    private record Candidate(UUID userId, UUID tenantId, String slug, String tenantName,
                             String passwordHash, String role, String status,
                             String currencyCode, String timezone, String fullName) {
    }

    public LoginOutcome login(LoginRequest request) {
        List<Candidate> candidates = findCandidates(request.email(), request.tenantSlug());

        if (candidates.size() > 1) {
            // Email is unique per tenant, not globally, so one address can be a
            // real account in several businesses. Asking which is not an
            // information leak: the caller already proved nothing, and the list
            // is only reachable by someone who knows the address. It is returned
            // BEFORE the password check for that reason — checking first would
            // mean checking against an arbitrary one of several hashes.
            return new LoginOutcome.MultipleTenants(candidates.stream()
                    .map(c -> new TenantSummary(c.tenantId(), c.slug(), c.tenantName()))
                    .toList());
        }

        Candidate candidate = candidates.isEmpty() ? null : candidates.get(0);

        // Always spend the bcrypt time, even with no candidate and even for a
        // disabled account. See the class javadoc.
        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                candidate == null || candidate.passwordHash() == null
                        ? DUMMY_HASH : candidate.passwordHash());

        if (candidate == null || !passwordMatches || !"active".equals(candidate.status())) {
            // One message for every failure: unknown email, wrong password,
            // invited-but-not-activated, disabled. Distinguishing them here is
            // exactly the enumeration this endpoint must not offer.
            throw new UnauthorizedException("Invalid email or password");
        }

        TenantContext.bind(new TenantContext.TenantIdentity(
                candidate.tenantId(), candidate.userId(), candidate.role()));
        try {
            CurrentUser user = currentUserService.describe(
                    candidate.tenantId(), candidate.userId(), candidate.fullName(),
                    request.email(), candidate.role(), candidate.slug(), candidate.tenantName(),
                    candidate.currencyCode(), candidate.timezone());

            return new LoginOutcome.Authenticated(tokenIssuer.issueFor(
                    candidate.tenantId(), candidate.userId(), candidate.role(),
                    user, request.deviceLabel()));
        } finally {
            TenantContext.clear();
        }
    }

    private List<Candidate> findCandidates(String email, String tenantSlug) {
        String sql = """
                SELECT u.id, u.tenant_id, t.slug, t.name AS tenant_name, u.password_hash,
                       u.role::text AS role, u.status::text AS status,
                       t.currency_code, t.timezone, u.full_name
                FROM users u
                JOIN tenants t ON t.id = u.tenant_id
                WHERE u.email = ?::citext
                  AND u.deleted_at IS NULL
                  AND t.status <> 'closed'
                """;
        Object[] args;
        if (tenantSlug == null || tenantSlug.isBlank()) {
            args = new Object[] {email};
        } else {
            sql += " AND t.slug = ?::citext";
            args = new Object[] {email, tenantSlug};
        }

        return loginJdbc.query(sql, (rs, i) -> new Candidate(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("slug"),
                rs.getString("tenant_name"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getString("currency_code"),
                rs.getString("timezone"),
                rs.getString("full_name")), args);
    }

    /** Either a token pair, or the 300 "which tenant?" response. */
    public sealed interface LoginOutcome {
        record Authenticated(AuthTokens tokens) implements LoginOutcome {
        }

        record MultipleTenants(List<TenantSummary> tenants) implements LoginOutcome {
        }
    }
}
