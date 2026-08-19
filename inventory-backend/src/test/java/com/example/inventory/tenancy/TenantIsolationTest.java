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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.example.inventory.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The isolation sweep required by CLAUDE.md section 5: two seeded tenants, and
 * for every tenant-scoped table, tenant A sees zero of tenant B's rows and cannot
 * write a row carrying B's id — plus the case that catches the worst
 * misconfiguration of all, a connection with no tenant bound seeing everything.
 *
 * <h2>Why these assertions are counts and not expected exceptions</h2>
 *
 * <p>An isolation failure here does not throw. PostgreSQL does not refuse a query
 * that a policy fails to constrain; it returns the rows. Every realistic way of
 * breaking this — a table that was added without {@code ENABLE ROW LEVEL SECURITY},
 * a policy dropped by a later migration, {@code FORCE} left off so the owner
 * bypasses it, a role granted {@code BYPASSRLS}, {@code app.tenant_id} never set
 * on the connection — produces <em>more rows</em>, silently, through a query that
 * succeeds. So every assertion below is on a row count. There is no
 * {@code assertThatThrownBy} anywhere in the read path, and there must never be:
 * a test that waits for an exception would pass on a completely unprotected
 * database.
 *
 * <p>The one place an exception <em>is</em> the correct assertion is the write
 * path — {@code WITH CHECK} genuinely does raise — and that test is separated out
 * below so the distinction stays visible.
 *
 * <h2>The vacuity trap</h2>
 *
 * <p>"Tenant A sees zero of B's rows" passes trivially if tenant A sees nothing at
 * all: a broken connection, an unseeded database, a policy that denies everything
 * would all satisfy it. Every isolation assertion here is therefore paired with a
 * positive one — A must see its own rows — and {@link #seededTablesAreActuallySeeded()}
 * fails the whole class if the fixture did not land. Without that pairing this
 * test is decoration.
 *
 * <p>These assertions have been seen to fail, not just to pass. Adding
 * {@code ALTER TABLE categories DISABLE ROW LEVEL SECURITY} to the fixture turns
 * exactly four of them red — {@link #tenantASeesZeroRowsOfTenantB()},
 * {@link #tenantASeesNothingBelongingToAnyoneElse()},
 * {@link #withNoTenantBoundEveryTableIsEmpty()} and the cross-tenant write — and
 * every one of the three read failures reports a row count, with nothing thrown
 * and nothing logged. That is worth re-running by hand after any change to this
 * class: a sweep like this is only as good as its last demonstrated failure.
 *
 * <h2>How the tenant is bound</h2>
 *
 * <p>{@code set_config('app.tenant_id', ?, true)} — the {@code true} is
 * transaction-local, so the setting dies with the transaction and cannot ride a
 * pooled connection into the next test (CLAUDE.md T3). Session-level
 * {@code set_config(..., false)} here would make the pool itself the leak, and
 * the tests that follow would pass or fail depending on execution order.
 */
@DisplayName("Tenant isolation")
class TenantIsolationTest extends AbstractIntegrationTest {

    /**
     * Every tenant-scoped table from section 10 of V1__baseline.sql, all of which
     * the fixture below seeds for both tenants. Listed literally rather than read
     * out of the catalogue: a list derived from the database would quietly shrink
     * to match a table that had lost its policy.
     */
    private static final List<String> TENANT_SCOPED_TABLES = List.of(
            "users", "refresh_tokens", "locations", "categories", "suppliers", "products",
            "product_suppliers", "stock_movements", "product_stock", "stocktakes",
            "stocktake_lines", "sales", "sale_items", "purchase_orders", "purchase_order_items",
            "supplier_lead_time_observations", "demand_daily", "forecasts", "forecast_accuracy",
            "reorder_recommendations", "audit_log",
            // V5. Added in the change that creates it, per T11 — not as an
            // end-of-milestone tidy-up, because a table that misses this sweep
            // breaks nothing and stays uncovered until someone reads another
            // tenant's row in production.
            "sale_returns");

    private static final UUID TENANT_A = newTenantId();
    private static final UUID TENANT_B = newTenantId();

    /** The RLS-bound application pool — inventory_app, NOSUPERUSER, NOBYPASSRLS. */
    @Autowired
    private DataSource appDataSource;

    // ------------------------------------------------------------------
    // Fixture
    // ------------------------------------------------------------------

    /** Guards the fixture so it runs once per class, not once per test. */
    private static boolean seeded;

    /**
     * Seeds both tenants over the owner connection.
     *
     * <p>The owner is the container superuser, so it bypasses RLS entirely and can
     * write rows for two different tenants in one pass. That is the right tool for
     * a fixture and the wrong tool for an assertion: nothing in this class reads
     * through this connection, because a superuser sees everything and would
     * therefore confirm any schema at all.
     *
     * <p><strong>Not {@code @BeforeAll}</strong>, which is the obvious choice and
     * does not work. Spring's {@code SpringExtension} does not load the application
     * context as part of its {@code beforeAll} callback — the context, and
     * therefore Flyway, comes up when the first test instance needs injecting. A
     * {@code @BeforeAll} fixture consequently runs against a migrated container
     * only by luck of class ordering, and against an empty one fails with
     * {@code relation "tenants" does not exist}. {@code @BeforeEach} plus a static
     * flag is ordering-independent.
     */
    @BeforeEach
    void seedTwoTenants() throws SQLException {
        if (seeded) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            seedTenant(stmt, TENANT_A, "alpha");
            seedTenant(stmt, TENANT_B, "beta");
        }
        seeded = true;
    }

    /**
     * One row in every tenant-scoped table, wired together so the composite
     * foreign keys are satisfied.
     *
     * <p>String-formatted rather than parameterised: every value interpolated is a
     * UUID this class generated or a literal written here, and twenty
     * PreparedStatements would obscure what is actually a readable script.
     */
    private static void seedTenant(Statement stmt, UUID tenant, String label) throws SQLException {
        String t = tenant.toString();
        // Deterministic per-tenant child ids, so the script can reference them
        // without round-tripping RETURNING for each insert.
        UUID user = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID stocktake = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        UUID purchaseOrder = UUID.randomUUID();

        // The tenant itself. The slug is globally unique, so it carries the
        // random id rather than just the label.
        stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'tn-%s', '%s Trading')"
                .formatted(t, tenant.toString().substring(0, 8), label));

        stmt.execute("""
                INSERT INTO users (id, tenant_id, email, full_name, role, status)
                VALUES ('%s', '%s', '%s@example.test', 'Seed User', 'owner', 'active')
                """.formatted(user, t, label + "-" + user.toString().substring(0, 8)));

        // token_hash is globally unique, not per tenant.
        stmt.execute("""
                INSERT INTO refresh_tokens (tenant_id, user_id, token_hash, expires_at)
                VALUES ('%s', '%s', 'hash-%s', now() + interval '1 day')
                """.formatted(t, user, UUID.randomUUID()));

        stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                + "VALUES ('%s', '%s', 'Main Store', true)".formatted(location, t));

        stmt.execute("INSERT INTO categories (id, tenant_id, name) VALUES ('%s', '%s', 'Dry Goods')"
                .formatted(category, t));

        stmt.execute("INSERT INTO suppliers (id, tenant_id, name) VALUES ('%s', '%s', 'Acme Wholesale')"
                .formatted(supplier, t));

        stmt.execute("""
                INSERT INTO products (id, tenant_id, sku, name, category_id, cost_price, selling_price)
                VALUES ('%s', '%s', 'SKU-001', 'Maize Meal 10kg', '%s', 45.00, 79.99)
                """.formatted(product, t, category));

        stmt.execute("""
                INSERT INTO product_suppliers (tenant_id, product_id, supplier_id, unit_cost, is_preferred)
                VALUES ('%s', '%s', '%s', 45.00, true)
                """.formatted(t, product, supplier));

        // Inserting here also populates product_stock through trg_apply_stock_movement,
        // which is why product_stock is never inserted into directly (CLAUDE.md T5).
        stmt.execute("""
                INSERT INTO stock_movements
                    (tenant_id, product_id, location_id, movement_type, quantity_delta, unit_cost)
                VALUES ('%s', '%s', '%s', 'purchase_receipt', 100, 45.00)
                """.formatted(t, product, location));

        stmt.execute("INSERT INTO stocktakes (id, tenant_id, location_id) VALUES ('%s', '%s', '%s')"
                .formatted(stocktake, t, location));

        stmt.execute("""
                INSERT INTO stocktake_lines (tenant_id, stocktake_id, product_id, expected_qty, counted_qty)
                VALUES ('%s', '%s', '%s', 100, 98)
                """.formatted(t, stocktake, product));

        stmt.execute("""
                INSERT INTO sales (id, tenant_id, location_id, sale_number, status, total_amount)
                VALUES ('%s', '%s', '%s', 'S-0001', 'completed', 79.99)
                """.formatted(sale, t, location));

        stmt.execute("""
                INSERT INTO sale_items (tenant_id, sale_id, product_id, quantity, unit_price)
                VALUES ('%s', '%s', '%s', 1, 79.99)
                """.formatted(t, sale, product));

        // V5. Seeded for BOTH tenants, like every other row here — the sweep's
        // assertion is that A sees zero of B's, and that is vacuous unless B
        // actually has one.
        stmt.execute("""
                INSERT INTO sale_returns
                    (tenant_id, sale_id, product_id, quantity, restocked, return_group_id)
                VALUES ('%s', '%s', '%s', 1, true, gen_random_uuid())
                """.formatted(t, sale, product));

        stmt.execute("""
                INSERT INTO purchase_orders (id, tenant_id, supplier_id, location_id, po_number, status)
                VALUES ('%s', '%s', '%s', '%s', 'PO-0001', 'ordered')
                """.formatted(purchaseOrder, t, supplier, location));

        stmt.execute("""
                INSERT INTO purchase_order_items
                    (tenant_id, purchase_order_id, product_id, quantity_ordered, unit_cost)
                VALUES ('%s', '%s', '%s', 50, 45.00)
                """.formatted(t, purchaseOrder, product));

        stmt.execute("""
                INSERT INTO supplier_lead_time_observations
                    (tenant_id, supplier_id, purchase_order_id, ordered_at, received_at, lead_time_days)
                VALUES ('%s', '%s', '%s', now() - interval '7 days', now(), 7)
                """.formatted(t, supplier, purchaseOrder));

        stmt.execute("""
                INSERT INTO demand_daily (tenant_id, product_id, location_id, day, units_sold, sales_count)
                VALUES ('%s', '%s', '%s', current_date - 1, 8, 3)
                """.formatted(t, product, location));

        stmt.execute("""
                INSERT INTO forecasts
                    (tenant_id, product_id, location_id, method, history_days,
                     avg_daily_demand, horizon_days, forecast_qty)
                VALUES ('%s', '%s', '%s', 'moving_average', 90, 1.1000, 30, 33.000)
                """.formatted(t, product, location));

        // Takes the identity id from the row just inserted, avoiding a RETURNING
        // round trip for the one table whose parent key is not a UUID.
        stmt.execute("""
                INSERT INTO forecast_accuracy
                    (tenant_id, forecast_id, product_id, period_start, period_end,
                     predicted_qty, actual_qty)
                SELECT tenant_id, id, product_id, current_date - 30, current_date, forecast_qty, 30.000
                FROM forecasts WHERE tenant_id = '%s'
                """.formatted(t));

        stmt.execute("""
                INSERT INTO reorder_recommendations
                    (tenant_id, product_id, location_id, supplier_id,
                     quantity_on_hand, reorder_point, recommended_qty)
                VALUES ('%s', '%s', '%s', '%s', 12.000, 20.000, 50.000)
                """.formatted(t, product, location, supplier));

        stmt.execute("""
                INSERT INTO audit_log (tenant_id, user_id, action, entity_type, entity_id)
                VALUES ('%s', '%s', 'product.created', 'product', '%s')
                """.formatted(t, user, product));
    }

    // ------------------------------------------------------------------
    // Preconditions — if any of these fail, every assertion below is vacuous
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the application connection is actually subject to RLS")
    void applicationConnectionIsSubjectToRowLevelSecurity() {
        withTenant(TENANT_A, jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                    .as("reads must go through the restricted role, not the owner")
                    .isEqualTo(APP_ROLE);

            assertThat(jdbc.queryForObject(
                    "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                    Boolean.class))
                    .as("a superuser or BYPASSRLS role ignores every policy, which would make "
                            + "every other assertion in this class pass on a broken schema")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("the fixture actually landed — tenant A can see its own rows in every table")
    void seededTablesAreActuallySeeded() {
        withTenant(TENANT_A, jdbc -> SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(count(jdbc, table))
                        .as("%s: tenant A must see its own seeded row — a zero here means the "
                                + "isolation assertions are passing because nothing is visible "
                                + "at all, not because isolation works", table)
                        .isPositive();
            }
        }));
    }

    // ------------------------------------------------------------------
    // The isolation assertions — row counts, never exceptions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bound to tenant A, every table returns zero of tenant B's rows")
    void tenantASeesZeroRowsOfTenantB() {
        withTenant(TENANT_A, jdbc -> SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(countWhereTenantIs(jdbc, table, TENANT_B))
                        .as("%s: tenant B's rows must be invisible to tenant A", table)
                        .isZero();
            }
        }));
    }

    @Test
    @DisplayName("bound to tenant A, no table returns a row belonging to ANY other tenant")
    void tenantASeesNothingBelongingToAnyoneElse() {
        // Broader than the test above: B is the tenant this class seeded, but the
        // container is shared with every other test class in the run, so other
        // tenants exist. None of them should be visible either, and a policy that
        // special-cased its way to passing the B check would fail here.
        withTenant(TENANT_A, jdbc -> SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM " + table + " WHERE tenant_id <> ?",
                                Long.class, TENANT_A))
                        .as("%s: leaked rows from a tenant other than A", table)
                        .isZero();
            }
        }));
    }

    @Test
    @DisplayName("with NO tenant bound, every table returns empty rather than everything")
    void withNoTenantBoundEveryTableIsEmpty() {
        // The nightmare case. A policy written as `tenant_id = current_tenant_id()`
        // yields NULL — and therefore no rows — when the setting is absent, which
        // is correct. A policy written with a COALESCE fallback, or a table that
        // never had RLS enabled, returns the entire table to an unauthenticated
        // connection instead. Both succeed; only the row count tells them apart.
        withNoTenant(jdbc -> {
            assertThat(jdbc.queryForObject("SELECT current_tenant_id() IS NULL", Boolean.class))
                    .as("no tenant should be bound — if one is, it rode in on a pooled "
                            + "connection from an earlier test and T3 is broken")
                    .isTrue();

            SoftAssertions.assertSoftly(softly -> {
                for (String table : TENANT_SCOPED_TABLES) {
                    softly.assertThat(count(jdbc, table))
                            .as("%s: an unbound connection must see nothing, not everything", table)
                            .isZero();
                }
                softly.assertThat(count(jdbc, "tenants"))
                        .as("tenants: an unbound connection must not enumerate the customer list")
                        .isZero();
            });
        });
    }

    @Test
    @DisplayName("the tenants table shows a tenant only itself")
    void tenantsTableShowsOnlyYourOwnTenant() {
        withTenant(TENANT_A, jdbc -> {
            assertThat(jdbc.queryForList("SELECT id FROM tenants", UUID.class))
                    .as("tenant_self policy: exactly your own row, never the customer list")
                    .containsExactly(TENANT_A);
        });
    }

    // ------------------------------------------------------------------
    // The write path — here an exception IS the correct assertion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-tenant writes")
    class CrossTenantWrites {

        @Test
        @DisplayName("tenant A cannot insert a row carrying tenant B's id")
        void insertingAnotherTenantsIdIsRejected() {
            // Unlike every read above, this one genuinely raises: the WITH CHECK
            // half of the policy rejects the row rather than filtering it. An
            // exception is the right assertion precisely because a silent failure
            // is not available here — a rejected INSERT cannot come back as
            // "wrote fewer rows".
            //
            // The assertion is on the ROOT cause, not the message Spring produces.
            // PostgreSQL raises SQLSTATE 42501 (insufficient_privilege), which
            // Spring's SQLStateSQLExceptionTranslator maps to BadSqlGrammarException
            // — a name that describes the wrong thing entirely, with a message that
            // mentions only the statement. M1's GlobalExceptionHandler needs to know
            // this: a cross-tenant write arrives looking like a syntax error, and
            // anything keying off the exception type alone will misclassify it.
            withTenant(TENANT_A, jdbc ->
                    assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO categories (tenant_id, name) VALUES (?, ?)",
                            TENANT_B, "Smuggled Category"))
                            .as("WITH CHECK (tenant_id = current_tenant_id()) must reject this")
                            .rootCause()
                            .hasMessageContaining("violates row-level security policy"));
        }

        @Test
        @DisplayName("and the rejected row is not there afterwards")
        void theRejectedRowWasNotWritten() {
            // Belt and braces: proves the previous test's exception meant the write
            // was refused, not merely reported oddly after landing.
            withTenant(TENANT_B, jdbc ->
                    assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM categories WHERE name = ?",
                            Long.class, "Smuggled Category"))
                            .as("tenant B must not have acquired a category from tenant A")
                            .isZero());
        }
    }

    // ------------------------------------------------------------------
    // Registration — proving the design decision, not just asserting it
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Tenant registration on the application pool")
    class Registration {

        /**
         * POST /auth/register-tenant has to create a tenant and its first user
         * before any tenant exists to be bound to. V3 section 5 chooses to run
         * that on the ordinary application pool, with {@code app.tenant_id}
         * bound to the UUID the service is about to create — rather than on the
         * owner connection or a fourth write-capable role.
         *
         * <p>That choice is only sound if RLS actually accepts the inserts, so
         * this test is the evidence for it. If it ever fails, the decision has
         * to be revisited, not worked around.
         */
        @Test
        @DisplayName("succeeds when app.tenant_id is bound to the id being created")
        void registrationWorksOnTheApplicationPool() {
            UUID newTenant = newTenantId();

            withTenant(newTenant, jdbc -> {
                // tenants.tenant_self WITH CHECK (id = current_tenant_id())
                jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                        newTenant, "reg-" + newTenant.toString().substring(0, 8), "Newly Registered");

                // users.tenant_isolation WITH CHECK (tenant_id = current_tenant_id())
                jdbc.update("""
                        INSERT INTO users (tenant_id, email, full_name, role, status)
                        VALUES (?, ?, 'First Owner', 'owner', 'active')
                        """, newTenant, "owner-" + newTenant + "@example.test");

                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenants WHERE id = ?", Long.class, newTenant))
                        .as("the tenant row must be visible to the transaction that created it")
                        .isEqualTo(1L);
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE tenant_id = ?", Long.class, newTenant))
                        .as("and so must its first user")
                        .isEqualTo(1L);
            });
        }

        @Test
        @DisplayName("cannot create a tenant under an id other than the one bound")
        void registrationCannotCreateSomeoneElsesTenant() {
            // The containment that makes the app pool the right place for this:
            // a registration transaction is confined to the tenant it declared.
            // The owner-privileged alternative would have no such limit — every
            // policy is off for a superuser, so a bug in that path could write
            // anywhere, and nothing in the database would stop it.
            UUID declared = newTenantId();
            UUID someoneElse = newTenantId();

            withTenant(declared, jdbc ->
                    assertThatThrownBy(() -> jdbc.update(
                            "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                            someoneElse, "hijack-" + someoneElse.toString().substring(0, 8), "Hijacked"))
                            .as("WITH CHECK (id = current_tenant_id()) confines registration "
                                    + "to the tenant it declared")
                            .rootCause()
                            .hasMessageContaining("violates row-level security policy"));
        }

        @Test
        @DisplayName("a duplicate slug still fails, even though the tenant row is invisible")
        void duplicateSlugIsRejectedDespiteRlsHidingTheExistingRow() {
            // Worth pinning down because it is counter-intuitive. Bound to a new
            // tenant id, registration cannot SELECT the existing tenant to check
            // whether a slug is taken — RLS hides it. The UNIQUE index is
            // enforced physically and independently of RLS, so the collision
            // still surfaces. M1 maps this to 409 rather than pre-checking.
            UUID first = newTenantId();
            String slug = "dup-" + first.toString().substring(0, 8);

            withTenantCommitting(first, jdbc ->
                    jdbc.update("INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                            first, slug, "First Claimant"));

            UUID second = newTenantId();
            withTenant(second, jdbc -> {
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenants WHERE slug = ?", Long.class, slug))
                        .as("the existing tenant is invisible to this transaction — a pre-check "
                                + "would wrongly conclude the slug is free")
                        .isZero();

                assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO tenants (id, slug, name) VALUES (?, ?, ?)",
                        second, slug, "Second Claimant"))
                        .as("but the unique index still refuses it")
                        .rootCause()
                        .hasMessageContaining("duplicate key value violates unique constraint");
            });
        }
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static long countWhereTenantIs(JdbcTemplate jdbc, String table, UUID tenant) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, tenant);
    }

    /** Runs {@code work} on the application pool with {@code app.tenant_id} bound. */
    private void withTenant(UUID tenant, Consumer<JdbcTemplate> work) {
        onAppConnection(jdbc -> {
            jdbc.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            work.accept(jdbc);
        });
    }

    /** Runs {@code work} on the application pool with nothing bound at all. */
    private void withNoTenant(Consumer<JdbcTemplate> work) {
        onAppConnection(work);
    }

    /**
     * As {@link #withTenant}, but commits.
     *
     * <p>Needed only where a later transaction must see the result — the
     * duplicate-slug case, which is about a constraint that spans transactions.
     * Everything else rolls back, and should.
     */
    private void withTenantCommitting(UUID tenant, Consumer<JdbcTemplate> work) {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            jdbc.queryForObject(
                    "SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            work.accept(jdbc);
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not commit as tenant " + tenant, e);
        }
    }

    /**
     * Borrows one connection, runs everything inside a single transaction, and
     * rolls back.
     *
     * <p>One connection because {@code set_config(..., true)} is transaction-local
     * and a JdbcTemplate over the pool would be free to hand the next statement a
     * different, unbound connection. Rollback because the tenant binding must not
     * outlive the transaction, and because the cross-tenant write test would
     * otherwise leave a poisoned row behind.
     */
    private void onAppConnection(Consumer<JdbcTemplate> work) {
        try (Connection conn = appDataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                work.accept(new JdbcTemplate(new SingleConnectionDataSource(conn, true)));
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not borrow an application connection", e);
        }
    }
}
