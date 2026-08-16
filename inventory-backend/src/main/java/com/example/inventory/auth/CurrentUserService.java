package com.example.inventory.auth;

import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.inventory.auth.AuthDtos.CurrentUser;
import com.example.inventory.auth.AuthDtos.TenantSummary;

/**
 * Assembles the {@code CurrentUser} payload — the {@code /me} response, and the
 * {@code user} block returned with every token pair.
 *
 * <p>Reads through the <strong>application</strong> pool, so a tenant must be
 * bound. That is intentional even though the login pool could technically answer
 * part of it: this query is tenant-scoped by nature, and routing it through the
 * unscoped pool would widen that pool's job from "resolve a login" to "read user
 * data", which is how a narrow role stops being narrow.
 *
 * <p>The RLS policy is doing real work here rather than decorating an
 * application {@code WHERE} clause: even with a tenant id passed explicitly, a
 * mismatched pairing returns nothing, because the policy filters first.
 */
@Service
public class CurrentUserService {

    private final JdbcTemplate appJdbc;

    public CurrentUserService(@Qualifier("appDataSource") DataSource appDataSource) {
        this.appJdbc = new JdbcTemplate(appDataSource);
    }

    public Optional<CurrentUser> load(UUID tenantId, UUID userId) {
        var rows = appJdbc.query("""
                SELECT u.id, u.email::text AS email, u.full_name, u.role::text AS role,
                       t.id AS tenant_id, t.slug::text AS slug, t.name AS tenant_name,
                       t.currency_code, t.timezone,
                       (SELECT l.id FROM locations l
                         WHERE l.tenant_id = t.id AND l.is_default
                         LIMIT 1) AS default_location_id
                FROM users u
                JOIN tenants t ON t.id = u.tenant_id
                WHERE u.id = ? AND u.tenant_id = ? AND u.deleted_at IS NULL
                """,
                (rs, i) -> new CurrentUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("role"),
                        new TenantSummary(
                                rs.getObject("tenant_id", UUID.class),
                                rs.getString("slug"),
                                rs.getString("tenant_name")),
                        rs.getString("currency_code"),
                        rs.getString("timezone"),
                        rs.getObject("default_location_id", UUID.class)),
                userId, tenantId);

        return rows.stream().findFirst();
    }

    /**
     * Builds the payload from values already in hand.
     *
     * <p>Login has just read every field from the login pool, so re-reading them
     * through the application pool would be a second round trip for data it is
     * holding. Only the default location — which the login pool cannot see, and
     * must not be granted — needs fetching.
     */
    CurrentUser describe(UUID tenantId, UUID userId, String fullName, String email, String role,
                         String slug, String tenantName, String currencyCode, String timezone) {
        UUID defaultLocationId = appJdbc.query(
                "SELECT id FROM locations WHERE tenant_id = ? AND is_default LIMIT 1",
                (rs, i) -> rs.getObject("id", UUID.class), tenantId)
                .stream().findFirst().orElse(null);

        return new CurrentUser(userId, email, fullName, role,
                new TenantSummary(tenantId, slug, tenantName),
                currencyCode, timezone, defaultLocationId);
    }
}
