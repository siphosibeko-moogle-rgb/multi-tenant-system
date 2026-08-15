package com.example.inventory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the database the application actually booted against is the one the
 * schema intends: migrations applied, row-level security switched on and forced
 * everywhere, a tenant_isolation policy on every tenant-scoped table, and an
 * application role that cannot escape any of it.
 *
 * <p>This is the M0 safety net. M1 builds tenant isolation on the assumption that
 * all of the above holds; if any of it silently regressed, every later isolation
 * test would be testing nothing.
 */
@DisplayName("Schema smoke test")
class SchemaSmokeTest extends AbstractIntegrationTest {

    /**
     * The tenant-scoped tables, mirroring the array in section 10 of
     * V1__baseline.sql. Written out rather than derived from the database on
     * purpose: deriving the list from what exists would make the test agree with
     * whatever it found, including a table that lost its policy.
     */
    private static final List<String> TENANT_SCOPED_TABLES = List.of(
            "users", "refresh_tokens", "locations", "categories", "suppliers", "products",
            "product_suppliers", "stock_movements", "product_stock", "stocktakes",
            "stocktake_lines", "sales", "sale_items", "purchase_orders", "purchase_order_items",
            "supplier_lead_time_observations", "demand_daily", "forecasts", "forecast_accuracy",
            "reorder_recommendations", "audit_log");

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // Migrations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every migration on the classpath is applied and succeeded")
    void allMigrationsApplied() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, description, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history)
                .as("flyway_schema_history should not be empty — did Flyway run at all?")
                .isNotEmpty();

        assertThat(history)
                .as("no migration may be recorded as failed")
                .allSatisfy(row -> assertThat(row.get("success")).isEqualTo(Boolean.TRUE));

        Set<String> applied = history.stream()
                .map(row -> String.valueOf(row.get("version")))
                .collect(Collectors.toSet());

        assertThat(applied)
                .as("V1 (baseline) and V2 (application role) must both be applied")
                .contains("1", "2");
    }

    @Test
    @DisplayName("migrations were applied by the schema owner, not the application role")
    void migrationsWereAppliedByTheOwner() {
        // flyway_schema_history.installed_by records the database user that ran
        // each migration, which makes this directly checkable rather than a
        // matter of trusting configuration.
        //
        // Verified empirically in FlywayBindingTest: Boot binds Flyway to the
        // PRIMARY datasource unless spring.flyway.url/user/password are set. M0
        // sets them, so migrations run as the owner. When M1 adds the RLS-bound
        // application pool as a second datasource, this assertion is what catches
        // it if that explicit binding is ever dropped.
        List<String> installedBy = jdbc.queryForList(
                "SELECT DISTINCT installed_by FROM flyway_schema_history", String.class);

        assertThat(installedBy)
                .as("migrations must never be applied by the RLS-bound application role")
                .doesNotContain(APP_ROLE);

        assertThat(installedBy)
                .as("every migration should have been applied by one role: the owner")
                .hasSize(1)
                .doesNotContain(jdbc.queryForObject("SELECT current_user", String.class));
    }

    @Test
    @DisplayName("the baseline actually created the tenant-scoped tables")
    void baselineTablesExist() {
        Set<String> present = new HashSet<>(jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class));

        assertThat(present)
                .as("tables declared tenant-scoped by V1 must exist")
                .containsAll(TENANT_SCOPED_TABLES);
        assertThat(present).contains("tenants");
    }

    // ------------------------------------------------------------------
    // Row-level security — the enforcement boundary (CLAUDE.md T2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every tenant-scoped table has RLS both enabled and forced")
    void rowLevelSecurityIsEnabledAndForced() {
        // pg_tables.rowsecurity answers "enabled". Only pg_class.relforcerowsecurity
        // answers "forced", and forced is the half that matters here: without it
        // the table owner bypasses the policy, so a mistake in a migration or a
        // service running as owner would leak silently.
        Map<String, Boolean> enabled = jdbc.queryForList(
                        "SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname = 'public'")
                .stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("tablename"),
                        row -> (Boolean) row.get("rowsecurity")));

        Map<String, Boolean> forced = jdbc.queryForList(
                        "SELECT c.relname, c.relforcerowsecurity FROM pg_class c "
                                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                                + "WHERE n.nspname = 'public' AND c.relkind = 'r'")
                .stream()
                .collect(Collectors.toMap(
                        row -> (String) row.get("relname"),
                        row -> (Boolean) row.get("relforcerowsecurity")));

        SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(enabled.get(table))
                        .as("%s: pg_tables.rowsecurity", table)
                        .isTrue();
                softly.assertThat(forced.get(table))
                        .as("%s: pg_class.relforcerowsecurity", table)
                        .isTrue();
            }
            // tenants is scoped too, by its own tenant_self policy rather than
            // tenant_isolation, but it must still be enabled and forced.
            softly.assertThat(enabled.get("tenants"))
                    .as("tenants: pg_tables.rowsecurity").isTrue();
            softly.assertThat(forced.get("tenants"))
                    .as("tenants: pg_class.relforcerowsecurity").isTrue();
        });
    }

    @Test
    @DisplayName("every tenant-scoped table carries the tenant_isolation policy")
    void tenantIsolationPolicyExistsOnEveryTable() {
        Map<String, Set<String>> policiesByTable = jdbc.queryForList(
                        "SELECT tablename, policyname FROM pg_policies WHERE schemaname = 'public'")
                .stream()
                .collect(Collectors.groupingBy(
                        row -> (String) row.get("tablename"),
                        Collectors.mapping(row -> (String) row.get("policyname"), Collectors.toSet())));

        SoftAssertions.assertSoftly(softly -> {
            for (String table : TENANT_SCOPED_TABLES) {
                softly.assertThat(policiesByTable.getOrDefault(table, Set.of()))
                        .as("%s should carry the tenant_isolation policy", table)
                        .contains("tenant_isolation");
            }
            softly.assertThat(policiesByTable.getOrDefault("tenants", Set.of()))
                    .as("tenants is scoped by tenant_self, not tenant_isolation")
                    .contains("tenant_self");
        });
    }

    // ------------------------------------------------------------------
    // The application role created by V2
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the application connects as a role that RLS actually applies to")
    void applicationRoleIsNeitherSuperuserNorBypassRls() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class))
                .as("the application datasource must not be the schema owner")
                .isEqualTo(APP_ROLE);

        Map<String, Object> role = jdbc.queryForMap(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");

        assertThat((Boolean) role.get("rolsuper"))
                .as("a superuser ignores every RLS policy in the schema")
                .isFalse();
        assertThat((Boolean) role.get("rolbypassrls"))
                .as("BYPASSRLS would silently disable tenant isolation entirely")
                .isFalse();
    }

    @Test
    @DisplayName("the ledger is append-only for the application role")
    void applicationRoleCannotUpdateOrDeleteStockMovements() {
        // CLAUDE.md T4. V1 installs a trigger that raises on UPDATE/DELETE; V2
        // additionally revokes the privileges, so the attempt is refused before
        // it ever reaches the trigger.
        Map<String, Object> privileges = jdbc.queryForMap(
                "SELECT has_table_privilege(current_user, 'stock_movements', 'SELECT') AS may_select, "
                        + "has_table_privilege(current_user, 'stock_movements', 'INSERT') AS may_insert, "
                        + "has_table_privilege(current_user, 'stock_movements', 'UPDATE') AS may_update, "
                        + "has_table_privilege(current_user, 'stock_movements', 'DELETE') AS may_delete");

        assertThat((Boolean) privileges.get("may_select")).as("reads are needed").isTrue();
        assertThat((Boolean) privileges.get("may_insert")).as("appends are the only writes").isTrue();
        assertThat((Boolean) privileges.get("may_update")).as("UPDATE must be revoked").isFalse();
        assertThat((Boolean) privileges.get("may_delete")).as("DELETE must be revoked").isFalse();
    }

    @Test
    @DisplayName("with no tenant bound, tenant-scoped reads return nothing")
    void withNoTenantBoundQueriesReturnEmpty() {
        // The failure mode this guards against is a policy that evaluates to
        // TRUE when app.tenant_id is unset — which would return every tenant's
        // rows rather than none. Asserted here on an empty schema so that M1's
        // TenantIsolationTest inherits a database already known to behave.
        assertThat(jdbc.queryForObject("SELECT current_tenant_id() IS NULL", Boolean.class))
                .as("no tenant should be bound on a fresh connection")
                .isTrue();

        for (String table : List.of("products", "stock_movements", "sales", "users")) {
            Integer visible = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            assertThat(visible)
                    .as("%s must return zero rows when no tenant is bound", table)
                    .isZero();
        }
    }
}
