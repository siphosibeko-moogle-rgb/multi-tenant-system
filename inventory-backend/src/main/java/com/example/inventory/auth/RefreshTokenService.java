package com.example.inventory.auth;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.UnauthorizedException;

/**
 * Rotates refresh tokens, and revokes aggressively when one is replayed.
 *
 * <h2>The flow, and why it can use the application pool</h2>
 *
 * <ol>
 *   <li>Verify the JWT signature. Everything after this point works from
 *       claims that are known to be ours.</li>
 *   <li>Read {@code tid} from the <em>verified</em> claims and bind it. This is
 *       still T1 — the tenant comes from a signed token, exactly as it does for
 *       an access token. The refresh endpoint is unauthenticated only in the
 *       sense that no <em>access</em> token is required.</li>
 *   <li>Look the token's hash up in {@code refresh_tokens} on the RLS-bound
 *       pool. A token signed for tenant A cannot reach tenant B's rows even if
 *       the row id were guessed, because the policy is already in force.</li>
 * </ol>
 *
 * <h2>Replay revokes everything for that user</h2>
 *
 * <p>Presenting a token that exists but is already revoked means one of two
 * things: the legitimate client retried, or someone stole a token and is using
 * it after the real client already rotated. These are indistinguishable from
 * here, and the safe reading is theft.
 *
 * <p>The milestone calls for revoking "the token family". {@code refresh_tokens}
 * has no family column, and rather than add one this revokes every live token
 * for that user — a superset of the family, so it fails in the safe direction
 * and costs a re-login on the user's other devices. If per-device families are
 * wanted later, that is a column and a migration, not a redesign.
 */
@Service
public class RefreshTokenService {

    private final JwtDecoder jwtDecoder;
    private final JdbcTemplate appJdbc;
    private final TransactionTemplate transactions;
    private final TokenIssuer tokenIssuer;
    private final CurrentUserService currentUserService;

    /**
     * Takes the <em>refresh</em> decoder, not the primary one. The primary
     * decoder rejects {@code typ=refresh} so that a refresh token cannot
     * authenticate an ordinary request; this endpoint is handed nothing else.
     */
    public RefreshTokenService(@Qualifier("refreshTokenDecoder") JwtDecoder jwtDecoder,
                               @Qualifier("appDataSource") DataSource appDataSource,
                               TransactionTemplate transactions,
                               TokenIssuer tokenIssuer,
                               CurrentUserService currentUserService) {
        this.jwtDecoder = jwtDecoder;
        this.appJdbc = new JdbcTemplate(appDataSource);
        this.transactions = transactions;
        this.tokenIssuer = tokenIssuer;
        this.currentUserService = currentUserService;
    }

    public AuthTokens refresh(String presentedToken, String deviceLabel) {
        Jwt jwt = verifyRefreshToken(presentedToken);

        UUID tenantId = UUID.fromString(jwt.getClaimAsString(JwtService.TENANT_CLAIM));
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID jti = UUID.fromString(jwt.getId());

        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, userId, null));
        Outcome outcome;
        try {
            outcome = transactions.execute(status ->
                    rotate(presentedToken, tenantId, userId, jti, deviceLabel));
        } finally {
            TenantContext.clear();
        }

        // Rejections are thrown out here, AFTER the transaction has committed,
        // never from inside it.
        //
        // This is not stylistic. Throwing from within the callback rolls the
        // transaction back, and the replay branch below performs a revocation
        // that must survive — the first version of this method revoked the
        // token family and then threw, so the rollback quietly undid the
        // revocation and the stolen token stayed live. AuthFlowTest caught it.
        return switch (outcome) {
            case Outcome.Rotated rotated -> rotated.tokens();
            case Outcome.Rejected rejected -> throw new UnauthorizedException(rejected.reason());
        };
    }

    /**
     * The result of an attempted rotation.
     *
     * <p>Returned rather than thrown so that any state change made along the way
     * — specifically the family revocation on replay — is committed.
     */
    private sealed interface Outcome {
        record Rotated(AuthTokens tokens) implements Outcome {
        }

        record Rejected(String reason) implements Outcome {
        }
    }

    private Jwt verifyRefreshToken(String presentedToken) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(presentedToken);
        } catch (JwtException e) {
            // Covers a bad signature, an expired token and a malformed one. The
            // client is told the same thing for all three: a token that does not
            // verify is not a token, and saying which way it failed only helps
            // someone probing the signing key.
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (!JwtService.REFRESH_TOKEN_TYPE.equals(
                jwt.getClaimAsString(JwtService.TOKEN_TYPE_CLAIM))) {
            // An access token is signed by the same key and would otherwise sail
            // through, letting a 15-minute credential act as a 30-day one.
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (jwt.getId() == null || jwt.getSubject() == null
                || jwt.getClaimAsString(JwtService.TENANT_CLAIM) == null) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        return jwt;
    }

    private Outcome rotate(String presentedToken, UUID tenantId, UUID userId,
                           UUID jti, String deviceLabel) {
        String presentedHash = TokenIssuer.sha256(presentedToken);

        var rows = appJdbc.queryForList("""
                SELECT id, revoked_at, expires_at < now() AS expired
                FROM refresh_tokens
                WHERE id = ? AND token_hash = ? AND user_id = ?
                """, jti, presentedHash, userId);

        if (rows.isEmpty()) {
            // Correctly signed but unknown to us: already rotated away and
            // pruned, or issued against a different database. Not a replay we
            // can attribute, so nothing is revoked.
            return new Outcome.Rejected("Invalid refresh token");
        }

        Map<String, Object> row = rows.get(0);
        if (row.get("revoked_at") != null) {
            // The replay. This revocation is the reason this method returns
            // instead of throwing — it has to commit.
            revokeEveryTokenFor(userId);
            return new Outcome.Rejected("Refresh token has already been used");
        }
        if (Boolean.TRUE.equals(row.get("expired"))) {
            return new Outcome.Rejected("Refresh token has expired");
        }

        // Single use: the presented token dies here, whether or not the caller
        // ever receives the replacement.
        appJdbc.update("UPDATE refresh_tokens SET revoked_at = now() WHERE id = ?", jti);

        var user = currentUserService.load(tenantId, userId).orElse(null);
        if (user == null) {
            return new Outcome.Rejected("Invalid refresh token");
        }

        return new Outcome.Rotated(
                tokenIssuer.issueFor(tenantId, userId, user.role(), user, deviceLabel));
    }

    /**
     * Revokes every live refresh token for the authenticated user — "sign out
     * everywhere", and what a bodyless {@code POST /auth/logout} does.
     *
     * <p>The caller is already authenticated, so the tenant comes from the
     * verified claim via {@code TenantFilter}; it is passed in only so this
     * method does not have to reach into a ThreadLocal it did not set.
     */
    public void logoutEverySession(UUID tenantId, UUID userId) {
        revokeEveryTokenFor(userId);
    }

    private void revokeEveryTokenFor(UUID userId) {
        appJdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now() "
                        + "WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    /** Revokes the presented token only. Logout is not a security event. */
    public void logout(String presentedToken) {
        Jwt jwt;
        try {
            jwt = verifyRefreshToken(presentedToken);
        } catch (UnauthorizedException e) {
            // Logging out with a token that no longer verifies has already
            // achieved what the caller wanted. 204 either way.
            return;
        }

        UUID tenantId = UUID.fromString(jwt.getClaimAsString(JwtService.TENANT_CLAIM));
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID jti = UUID.fromString(jwt.getId());

        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, userId, null));
        try {
            appJdbc.update(
                    "UPDATE refresh_tokens SET revoked_at = now() "
                            + "WHERE id = ? AND user_id = ? AND revoked_at IS NULL", jti, userId);
        } finally {
            TenantContext.clear();
        }
    }
}
