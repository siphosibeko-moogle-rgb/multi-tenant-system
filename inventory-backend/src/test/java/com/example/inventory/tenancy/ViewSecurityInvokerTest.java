package com.example.inventory.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.inventory.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>A view over a tenant-scoped table returns every tenant's rows unless
 * it declares {@code security_invoker = true}.</strong>
 *
 * <p>This is a fourth way for tenant isolation to fail silently, alongside the
 * four CLAUDE.md §10 already lists, and it is not covered by any of T11's three
 * sweeps — they enumerate tenant-scoped <em>tables</em>, and a view has no
 * {@code relrowsecurity} and no policy of its own, so it was never a candidate
 * for a list whose entries are checked for RLS.
 *
 * <h2>The mechanism</h2>
 *
 * <p>A PostgreSQL view executes with the privileges and the RLS context of its
 * <em>owner</em> unless {@code security_invoker = true} is set, and the default
 * is {@code false}. Every view here is created by Flyway as
 * {@code inventory_owner}, which is a superuser — and a superuser bypasses
 * row-level security entirely. So the application role reads the view, its
 * {@code app.tenant_id} is bound correctly, and the policy is simply not
 * consulted.
 *
 * <h2>Two tests, and the second is the one that generalises</h2>
 *
 * <p>{@link #theStockStatusViewIsScopedToTheBoundTenant} is the count-based
 * assertion §10 asks for, against the view that had the bug.
 * {@link #everyViewInTheSchemaRunsAsTheInvoker} is the one that matters more:
 * it reads {@code pg_class} for <em>every</em> view in the schema and fails on
 * any that omits the option. A test naming one view protects one view; the next
 * view added would repeat the bug with nothing to notice.
 *
 * <p>That listing is derived from the catalogue rather than hand-written, which
 * is the opposite of T11's rule — and deliberately so. T11's lists are written
 * out by hand because a list read from the database would shrink to match a
 * table that had lost its policy, and would agree with the bug. Here the
 * catalogue is the <em>subject</em>, not the expectation: the query asks "what
 * views exist", and the assertion is applied to all of them. A view that
 * vanished would not silently pass, because {@link #everyViewInTheSchemaRunsAsTheInvoker}
 * also asserts the schema has views at all.
 */
@DisplayName("Views run as the invoker, not the owner")
class ViewSecurityInvokerTest extends AbstractIntegrationTest {

    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID locationA;
    private static UUID locationB;
    private static UUID productA;
    private static UUID productB;
    private static boolean seeded;

    /**
     * Two tenants, each with stock. Both must hold rows: "A sees none of B's"
     * passes trivially when A sees nothing at all (§10), so the positive twin is
     * built into the fixture rather than added as an afterthought.
     */
    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantA = newTenantId();
        tenantB = newTenantId();
        locationA = UUID.randomUUID();
        locationB = UUID.randomUUID();
        productA = UUID.randomUUID();
        productB = UUID.randomUUID();

        try (Connection conn = owner(); Statement stmt = conn.createStatement()) {
            seedTenant(stmt, tenantA, locationA, productA, "va");
            seedTenant(stmt, tenantB, locationB, productB, "vb");
        }
        seeded = true;
    }

    private static void seedTenant(Statement stmt, UUID tenantId, UUID locationId,
                                   UUID productId, String prefix) throws SQLException {
        String tag = tenantId.toString().substring(0, 8);
        stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', '%s-%s', 'View Shop')"
                .formatted(tenantId, prefix, tag));
        stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
        stmt.execute("""
                INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                VALUES ('%s', '%s', 'VIEW-%s', 'Viewed Product', 3.00, 9.00)
                """.formatted(productId, tenantId, tag));
        stmt.execute("""
                INSERT INTO product_stock (tenant_id, product_id, location_id, quantity_on_hand)
                VALUES ('%s', '%s', '%s', 12)
                """.formatted(tenantId, productId, locationId));
    }

    /** The container superuser — fixtures only, never an assertion (§10). */
    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** The restricted application role, exactly as production connects. */
    private static Connection app() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("v_stock_status shows the bound tenant its own rows, and none of the other's")
    void theStockStatusViewIsScopedToTheBoundTenant() throws SQLException {
        try (Connection conn = app(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT set_config('app.tenant_id', '%s', false)".formatted(tenantA));

            // The positive twin FIRST. Everything below passes against a broken
            // connection or an unseeded fixture without it.
            assertThat(countInView(stmt, tenantA))
                    .as("tenant A must see its own stock in the view, or the "
                            + "assertions below prove nothing")
                    .isEqualTo(1);

            // Row counts, never expected exceptions. An isolation failure does
            // not throw; it answers successfully with more rows than it should.
            assertThat(countInView(stmt, tenantB))
                    .as("tenant B's stock must be invisible. A non-zero here means "
                            + "the view is running as its owner — a superuser — and "
                            + "the tenant policy is not being consulted at all.")
                    .isZero();

            assertThat(countForeignRows(stmt, tenantA))
                    .as("and nothing belonging to ANY other tenant, which is broader "
                            + "than the B case and catches a policy that got B right "
                            + "by accident")
                    .isZero();
        }
    }

    @Test
    @DisplayName("with no tenant bound the view is empty, not everything")
    void anUnboundConnectionSeesNothing() throws SQLException {
        try (Connection conn = app(); Statement stmt = conn.createStatement()) {
            // Deliberately no set_config. current_tenant_id() is NULL, the policy
            // matches no rows, and the honest answer is zero. "Everything" is the
            // answer a view running as its owner gives.
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM v_stock_status")) {
                rs.next();
                assertThat(rs.getLong(1))
                        .as("an unbound connection reading the whole catalogue is the "
                                + "most dangerous shape this failure takes")
                        .isZero();
            }
        }
    }

    /**
     * The generalising test. Fails on any view that omits the option, including
     * ones that do not exist yet.
     */
    @Test
    @DisplayName("EVERY view in the schema declares security_invoker = true")
    void everyViewInTheSchemaRunsAsTheInvoker() throws SQLException {
        List<String> offenders = new ArrayList<>();
        int views = 0;

        try (Connection conn = owner();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT c.relname,
                            COALESCE(array_to_string(c.reloptions, ','), '') AS options
                     FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind IN ('v', 'm')
                     ORDER BY c.relname
                     """)) {
            while (rs.next()) {
                views++;
                if (!rs.getString("options").contains("security_invoker=true")) {
                    offenders.add(rs.getString("relname"));
                }
            }
        }

        assertThat(views)
                .as("no views found — this test would pass vacuously, so something "
                        + "has gone wrong with the migration rather than with the views")
                .isGreaterThan(0);

        assertThat(offenders)
                .as("these views execute with their OWNER's privileges and RLS "
                        + "context. The owner is inventory_owner, a superuser, which "
                        + "bypasses row-level security entirely — so each of these "
                        + "returns every tenant's rows to any caller. Add "
                        + "`WITH (security_invoker = true)` in R__views.sql.")
                .isEmpty();
    }

    /** Rows in the view belonging to {@code tenantId}. */
    private static long countInView(Statement stmt, UUID tenantId) throws SQLException {
        return countWhere(stmt, "tenant_id = '%s'".formatted(tenantId));
    }

    /** Rows in the view belonging to anyone OTHER than {@code tenantId}. */
    private static long countForeignRows(Statement stmt, UUID tenantId) throws SQLException {
        return countWhere(stmt, "tenant_id <> '%s'".formatted(tenantId));
    }

    private static long countWhere(Statement stmt, String predicate) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT count(*) FROM v_stock_status WHERE " + predicate)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
