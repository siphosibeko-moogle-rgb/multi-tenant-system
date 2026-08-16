package com.example.inventory.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthDtos.AuthTokens;
import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.JwtService.IssuedToken;

/**
 * Issues an access/refresh pair and records the refresh token server-side.
 *
 * <p>Callers must already have a tenant bound — registration binds the id it
 * generated, login binds the tenant it resolved — because the
 * {@code refresh_tokens} insert goes through the RLS-bound application pool and
 * would otherwise be refused by {@code WITH CHECK}.
 *
 * <h2>Only the hash is stored</h2>
 *
 * <p>{@code refresh_tokens.token_hash} holds SHA-256 of the token, never the
 * token. A database dump, a backup or a leaked query log therefore yields
 * nothing usable: the stored value cannot be presented as a credential.
 *
 * <p>SHA-256 rather than bcrypt here, deliberately, and the reasoning is the
 * opposite of the one for passwords. A refresh token is 200+ bits of server-
 * generated entropy inside a signed JWT, so it is not guessable and there is
 * nothing for a slow hash to defend against; meanwhile every refresh does this
 * lookup, and bcrypt would put a deliberate 100ms on a hot path. Passwords are
 * low-entropy and human-chosen, which is why they get bcrypt and this does not.
 */
@Service
public class TokenIssuer {

    private final JwtService jwtService;
    private final JdbcTemplate appJdbc;

    public TokenIssuer(JwtService jwtService, @Qualifier("appDataSource") DataSource appDataSource) {
        this.jwtService = jwtService;
        this.appJdbc = new JdbcTemplate(appDataSource);
    }

    /**
     * @param deviceLabel optional client hint, stored for the "signed-in devices"
     *                    list. Never used for authorisation — it is caller-supplied.
     */
    public AuthTokens issueFor(UUID tenantId, UUID userId, String role,
                               CurrentUser user, String deviceLabel) {
        IssuedToken access = jwtService.issueAccessToken(tenantId, userId, role);

        UUID jti = UUID.randomUUID();
        IssuedToken refresh = jwtService.issueRefreshToken(tenantId, userId, jti);

        appJdbc.update("""
                INSERT INTO refresh_tokens (id, tenant_id, user_id, token_hash, device_label, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                jti,
                tenantId,
                userId,
                sha256(refresh.value()),
                deviceLabel,
                java.sql.Timestamp.from(Instant.now().plus(refresh.ttl())));

        return AuthTokens.of(access.value(), refresh.value(), access.ttl().toSeconds(), user);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JDK and must be present", e);
        }
    }
}
