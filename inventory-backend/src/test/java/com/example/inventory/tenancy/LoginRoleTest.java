package com.example.inventory.tenancy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.example.inventory.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that {@code inventory_login} — the role V3 creates for the
 * login/tenant-resolution path — can do the one thing it exists for and nothing
 * else.
 *
 * <p>The role is a deliberate hole in the isolation story: it reads
 * {@code tenants} and {@code users} <em>unscoped</em>, because authentication has
 * to discover which tenant an email belongs to before {@code app.tenant_id} can
 * possibly be known. A hole that size is only safe if its edges are exact, so
 * every edge is asserted here rather than assumed from the migration text.
 *
 * <h2>Two gates, asserted separately</h2>
 *
 * <p>Privileges and policies are independent mechanisms and they fail
 * differently, which matters for what these tests assert:
 *
 * <ul>
 *   <li><strong>No privilege</strong> on a table raises SQLSTATE 42501,
 *       {@code permission denied for table …}. Loud.</li>
 *   <li><strong>No matching policy</strong> returns zero rows. Silent.</li>
 * </ul>
 *
 * <p>For the tables this role must not touch, a privilege failure is the outcome
 * we want and an empty result is <em>not good enough</em> — an empty result would
 * mean the role can reach the table and is merely being filtered, one policy edit
 * away from reading it. So {@link ReachOfTheRole#cannotReadAnyOtherTenantScopedTable()}
 * asserts on the error text specifically, not on emptiness.
 *
 * <p>This is the mirror image of {@link TenantIsolationTest}, where counts are the
 * only honest assertion. Same reasoning applied to a different mechanism: assert
 * on whatever the failure mode actually produces.
 *
 * <h2>Demonstrated, not assumed</h2>
 *
 * <p>Three mutations were applied to the fixture and each turned the expected
 * tests red on PG 16.14. Re-run them by hand after changing V3 or this class.
 *
 * <table border="1">
 * <caption>Mutation results</caption>
 * <tr><th>Mutation</th><th>Caught by</th><th>What it showed</th></tr>
 * <tr>
 *   <td>{@code GRANT SELECT ON products TO inventory_login}</td>
 *   <td>{@link ReachOfTheRole#cannotReadAnyOtherTenantScopedTable()}</td>
 *   <td>The query stopped throwing and returned 0. This is the exact degradation
 *       the test guards against — the loud privilege failure becomes a silent
 *       empty result, and only the absence of an exception distinguishes them.</td>
 * </tr>
 * <tr>
 *   <td>{@code login_read} recreated without {@code TO inventory_login}</td>
 *   <td>{@link #loginReadDoesNotApplyToTheApplicationRole()} <em>and</em>
 *       {@code TenantIsolationTest}</td>
 *   <td>This class caught the cause ({@code {public}} in the role list); the
 *       sweep independently caught the consequence — {@code users} leaking
 *       across tenants and to an unbound connection.</td>
 * </tr>
 * <tr>
 *   <td>{@code GRANT INSERT ON tenants TO inventory_login}</td>
 *   <td>{@link ReachOfTheRole#privilegeCatalogueAgreesWithV3()} and
 *       {@link ReachOfTheRole#cannotWriteToTheTwoTablesItCanRead()}</td>
 *   <td>The write was still refused, but by RLS rather than by privilege —
 *       {@code new row violates row-level security policy}. V3's second layer
 *       holding exactly as its section 4 claims: {@code login_read} is
 *       {@code FOR SELECT}, so it contributes no {@code WITH CHECK}, and
 *       {@code tenant_self}'s is unsatisfiable on an unbound connection.</td>
 * </tr>
 * </table>
 */
@DisplayName("The login role (V3)")
class LoginRoleTest extends AbstractIntegrationTest {

    /**
     * Everything the login role must not reach. This is the full tenant-scoped
     * list from V1 section 10, minus the two tables V3 deliberately grants.
     */
    private static final List<String> FORBIDDEN_TABLES = List.of(
            "refresh_tokens", "locations", "categories", "suppliers", "products",
            "product_suppliers", "stock_movements", "product_stock", "stocktakes",
            "stocktake_lines", "sales", "sale_items", "purchase_orders", "purchase_order_items",
            "supplier_lead_time_observations", "demand_daily", "forecasts", "forecast_accuracy",
            "reorder_recommendations", "audit_log");

    private static final UUID TENANT = newTenantId();
    private static final String EMAIL = "login-probe-" + UUID.randomUUID() + "@example.test";

    private static boolean seeded;

    /**
     * The unscoped, read-only login pool from {@code DataSourceConfig}.
     *
     * <p>The {@code @Qualifier} is not decoration. Spring resolves by type first
     * and consults {@code @Primary} <em>before</em> falling back to matching the
     * field name, so a bare {@code @Autowired DataSource loginDataSource} injects
     * the <strong>application</strong> pool — it compiles, starts, and quietly
     * runs every login query on the RLS-bound connection, where an unbound
     * {@code users} lookup matches no rows and every login fails as "unknown
     * email". This test caught exactly that mistake while it was being written.
     */
    @Autowired
    @Qualifier("loginDataSource")
    private DataSource loginDataSource;

    /**
     * One tenant with one user, so the unscoped read has something to find.
     *
     * <p>{@code @BeforeEach} rather than {@code @BeforeAll} for the same reason as
     * {@link TenantIsolationTest}: Spring does not load the context — and so
     * Flyway does not migrate — during JUnit's {@code beforeAll} callback.
     */
    @BeforeEach
    void seedOneTenant() throws SQLException {
        if (seeded) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'lg-%s', 'Login Probe')"
                    .formatted(TENANT, TENANT.toString().substring(0, 8)));
            stmt.execute("""
                    INSERT INTO users (tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'Login Probe User', 'owner', 'active')
                    """.formatted(TENANT, EMAIL));
        }
        seeded = true;
    }

    // ------------------------------------------------------------------
    // Role attributes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("is neither a superuser nor BYPASSRLS, and does not inherit the app role")
    void roleAttributesAreAsNarrowAsV3Claims() {
        onLoginConnection(jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                    .as("the login pool must connect as the login role, not something else")
                    .isEqualTo(LOGIN_ROLE);

            var attrs = jdbc.queryForMap(
                    "SELECT rolsuper, rolbypassrls, rolinherit FROM pg_roles WHERE rolname = current_user");

            assertThat((Boolean) attrs.get("rolsuper"))
                    .as("a superuser ignores every policy in the schema")
                    .isFalse();
            assertThat((Boolean) attrs.get("rolbypassrls"))
                    .as("BYPASSRLS on THIS role would be the worst case of all: it reads users "
                            + "unscoped already, and BYPASSRLS would extend that to every table")
                    .isFalse();
            assertThat((Boolean) attrs.get("rolinherit"))
                    .as("NOINHERIT — no privileges may arrive through role membership")
                    .isFalse();

            assertThat(jdbc.queryForObject(
                    "SELECT pg_has_role(current_user, 'inventory_app', 'MEMBER')", Boolean.class))
                    .as("the login role must not be a member of the application role by any path")
                    .isFalse();
        });
    }

    // ------------------------------------------------------------------
    // What it CAN do
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reads tenants and users with no tenant bound — this is the whole point")
    void canReadTenantsAndUsersUnbound() {
        onLoginConnection(jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_tenant_id() IS NULL", Boolean.class))
                    .as("nothing is bound: this is the pre-authentication state login runs in")
                    .isTrue();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE email = ?", Long.class, EMAIL))
                    .as("the email lookup that makes login possible must return the row")
                    .isEqualTo(1L);

            assertThat(jdbc.queryForObject(
                    "SELECT tenant_id FROM users WHERE email = ?", UUID.class, EMAIL))
                    .as("and it must resolve to the right tenant")
                    .isEqualTo(TENANT);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM tenants WHERE id = ?", Long.class, TENANT))
                    .as("the tenant row itself must be readable, to check status before issuing a token")
                    .isEqualTo(1L);
        });
    }

    // ------------------------------------------------------------------
    // What it CANNOT do
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Reach of the role")
    class ReachOfTheRole {

        @Test
        @DisplayName("cannot read ANY other tenant-scoped table — and fails on privilege, not emptiness")
        void cannotReadAnyOtherTenantScopedTable() {
            SoftAssertions.assertSoftly(softly -> {
                for (String table : FORBIDDEN_TABLES) {
                    softly.assertThatThrownBy(() -> onFreshLoginConnection(
                                    jdbc -> jdbc.queryForObject(
                                            "SELECT count(*) FROM " + table, Long.class)))
                            .as("%s: must be refused at the privilege layer. An empty result here "
                                    + "would mean the role can reach the table and is only being "
                                    + "filtered by policy — one migration away from reading it.",
                                    table)
                            .rootCause()
                            .hasMessageContaining("permission denied for table " + table);
                }
            });
        }

        @Test
        @DisplayName("cannot write to tenants or users")
        void cannotWriteToTheTwoTablesItCanRead() {
            SoftAssertions.assertSoftly(softly -> {
                softly.assertThatThrownBy(() -> onFreshLoginConnection(jdbc -> jdbc.update(
                                "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                                UUID.randomUUID(), "smuggled", "Smuggled Tenant")))
                        .as("login_read is FOR SELECT; there is no INSERT grant and no WITH CHECK "
                                + "that could accept this row")
                        .rootCause()
                        .hasMessageContaining("permission denied for table tenants");

                softly.assertThatThrownBy(() -> onFreshLoginConnection(jdbc -> jdbc.update(
                                "UPDATE users SET full_name = ? WHERE email = ?", "Renamed", EMAIL)))
                        .as("a role that could rewrite users could rewrite password hashes")
                        .rootCause()
                        .hasMessageContaining("permission denied for table users");

                softly.assertThatThrownBy(() -> onFreshLoginConnection(jdbc -> jdbc.update(
                                "DELETE FROM users WHERE email = ?", EMAIL)))
                        .as("nor delete them")
                        .rootCause()
                        .hasMessageContaining("permission denied for table users");
            });
        }

        @Test
        @DisplayName("holds no privilege it was not granted, by PostgreSQL's own accounting")
        void privilegeCatalogueAgreesWithV3() {
            // Asserted from the catalogue rather than by attempting each verb:
            // this catches a privilege granted but not yet exercised by any test,
            // including one arriving through a future ALTER DEFAULT PRIVILEGES.
            onLoginConnection(jdbc -> SoftAssertions.assertSoftly(softly -> {
                for (String verb : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                    for (String table : List.of("tenants", "users")) {
                        softly.assertThat(jdbc.queryForObject(
                                        "SELECT has_table_privilege(current_user, ?, ?)",
                                        Boolean.class, table, verb))
                                .as("%s on %s must not be granted to the login role", verb, table)
                                .isFalse();
                    }
                }
                for (String table : List.of("tenants", "users")) {
                    softly.assertThat(jdbc.queryForObject(
                                    "SELECT has_table_privilege(current_user, ?, 'SELECT')",
                                    Boolean.class, table))
                            .as("SELECT on %s is the one privilege this role should hold", table)
                            .isTrue();
                }
            }));
        }
    }

    // ------------------------------------------------------------------
    // The new policies must not widen anything for the application role
    // ------------------------------------------------------------------

    @Test
    @DisplayName("login_read is scoped to the login role and invisible to the app role")
    void loginReadDoesNotApplyToTheApplicationRole() {
        // The mechanism worth pinning down: permissive policies OR together, but
        // only those whose role list matches the current role. If login_read had
        // been created without TO inventory_login, it would OR with tenant_self
        // for EVERY role and silently make tenants and users world-readable to
        // the application pool. TenantIsolationTest would catch the consequence;
        // this catches the cause, and names it.
        var policies = new JdbcTemplate(appDataSource).queryForList(
                "SELECT tablename, policyname, roles::text, cmd FROM pg_policies "
                        + "WHERE schemaname = 'public' AND policyname = 'login_read' "
                        + "ORDER BY tablename");

        assertThat(policies)
                .as("V3 creates login_read on exactly tenants and users")
                .hasSize(2);

        SoftAssertions.assertSoftly(softly -> {
            for (var policy : policies) {
                softly.assertThat((String) policy.get("roles"))
                        .as("%s.login_read must name inventory_login and nothing else — "
                                + "{public} here would open the table to every role",
                                policy.get("tablename"))
                        .isEqualTo("{" + LOGIN_ROLE + "}");
                softly.assertThat((String) policy.get("cmd"))
                        .as("%s.login_read must be SELECT-only, so it contributes no WITH CHECK",
                                policy.get("tablename"))
                        .isEqualTo("SELECT");
            }
        });
    }

    /** The app pool, used here only to read {@code pg_policies}. */
    @Autowired
    private DataSource appDataSource;

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /**
     * Runs {@code work} on the login pool inside a transaction that is always
     * rolled back.
     *
     * <p>No tenant is ever bound here, deliberately: the unbound state is the
     * state this role runs in.
     */
    /**
     * As {@link #onLoginConnection}, but for probes that are <em>expected</em> to
     * fail, one connection each.
     *
     * <p>Sharing a connection across failing probes does not work, and the way it
     * breaks is worth knowing: the first refused statement aborts the
     * transaction, and every statement after it comes back carrying the
     * <em>first</em> error rather than its own. A twenty-table sweep then reports
     * twenty copies of the first table's message, which reads like twenty
     * failures of one kind and hides whatever the other nineteen would have said.
     * That is how this was first written, and it made a passing security property
     * look like a broken one.
     */
    private void onFreshLoginConnection(Consumer<JdbcTemplate> work) {
        onLoginConnection(work);
    }

    private void onLoginConnection(Consumer<JdbcTemplate> work) {
        try (Connection conn = loginDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.accept(new JdbcTemplate(new SingleConnectionDataSource(conn, true)));
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not borrow a login connection", e);
        }
    }
}
