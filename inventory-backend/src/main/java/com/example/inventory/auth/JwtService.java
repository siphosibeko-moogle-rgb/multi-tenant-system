package com.example.inventory.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthProperties.TokenTtl;

/**
 * Issues the two kinds of token this system uses.
 *
 * <p>Both are signed JWTs, and both carry {@code tid}. That claim is the only
 * source of tenant identity anywhere in the application (CLAUDE.md T1), which is
 * why it has to be inside the signature rather than alongside it.
 *
 * <table border="1">
 * <caption>Tokens</caption>
 * <tr><th></th><th>Access</th><th>Refresh</th></tr>
 * <tr><td>TTL</td><td>15 minutes</td><td>30 days</td></tr>
 * <tr><td>Claims</td><td>{@code sub}, {@code tid}, {@code role}, {@code jti}</td>
 *     <td>{@code sub}, {@code tid}, {@code jti}, {@code typ=refresh}</td></tr>
 * <tr><td>Stored server-side</td><td>no</td><td>yes — SHA-256 of the token</td></tr>
 * </table>
 *
 * <h2>Why the refresh token is a JWT and not an opaque string</h2>
 *
 * <p>An opaque refresh token would have to be looked up before its tenant is
 * known, and {@code refresh_tokens} is an RLS-protected, tenant-scoped table.
 * The login role cannot help — V3 grants it {@code tenants} and {@code users}
 * only, deliberately. So the lookup would need either an unscoped read of
 * {@code refresh_tokens} (a third hole in the isolation story) or a scan across
 * tenants (impossible under RLS by design).
 *
 * <p>Signing the tenant into the refresh token removes the problem instead of
 * working around it: {@code /auth/refresh} verifies the signature, reads
 * {@code tid} from the verified claims, binds it, and only then looks the hash
 * up on the ordinary application pool with RLS fully engaged. The token is
 * self-describing but not self-authorising — the stored hash still decides
 * whether it is live.
 *
 * <h2>The signing key</h2>
 *
 * <p>One symmetric HMAC key, from configuration. Symmetric is appropriate while
 * the issuer and the verifier are the same process; the moment anything else
 * needs to verify these tokens, this should become an asymmetric key pair with
 * a published JWKS. Key rotation is not implemented — see CLAUDE.md section 12.
 */
@Service
public class JwtService {

    /** Marks a refresh token, so one cannot be presented as an access token. */
    static final String TOKEN_TYPE_CLAIM = "typ";
    static final String REFRESH_TOKEN_TYPE = "refresh";
    static final String TENANT_CLAIM = "tid";
    static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final TokenTtl ttl;
    private final String issuer;

    public JwtService(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.ttl = properties.ttl();
        this.issuer = properties.issuer();
    }

    public IssuedToken issueAccessToken(UUID tenantId, UUID userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl.access()))
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .claim(TENANT_CLAIM, tenantId.toString())
                .claim(ROLE_CLAIM, role)
                .build();
        return new IssuedToken(encode(claims), ttl.access());
    }

    /**
     * @param jti the id recorded against the stored hash, so a specific token can
     *            be revoked without invalidating the signature scheme
     */
    public IssuedToken issueRefreshToken(UUID tenantId, UUID userId, UUID jti) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl.refresh()))
                .subject(userId.toString())
                .id(jti.toString())
                .claim(TENANT_CLAIM, tenantId.toString())
                // Without this, an access token would satisfy /auth/refresh and a
                // refresh token would satisfy every other endpoint. The two have
                // very different lifetimes, so conflating them turns a 15-minute
                // exposure into a 30-day one.
                .claim(TOKEN_TYPE_CLAIM, REFRESH_TOKEN_TYPE)
                .build();
        return new IssuedToken(encode(claims), ttl.refresh());
    }

    private String encode(JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(AuthProperties.SIGNING_ALGORITHM).build(), claims)).getTokenValue();
    }

    public record IssuedToken(String value, Duration ttl) {
    }
}
