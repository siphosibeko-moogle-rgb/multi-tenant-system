package com.example.inventory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The negative control for {@link FlywayBindingTest}.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@link FlywayBindingTest} asserts that migrations run as {@code inventory_owner}
 * when {@code spring.flyway.url/user/password} are set. On its own that assertion
 * proves nothing: it would pass just as happily if Boot ignored those properties
 * and something else happened to produce an owner connection. A guard nobody has
 * ever seen fail is not a guard.
 *
 * <p>So this test removes the three properties and changes nothing else — same
 * {@code DataSourceConfig}, same two pools, same {@code @Primary} on the
 * application role. If the explicit configuration is what does the work,
 * migrations here must run as the primary pool's role, {@code inventory_app}.
 * That is the assertion below, and it is deliberately an assertion on success
 * rather than on an exception.
 *
 * <h2>The failure this documents is silent, not loud</h2>
 *
 * <p>CLAUDE.md section 9 originally recorded that the manual control "failed
 * loudly" with {@code password authentication failed for user "inventory_app"} —
 * but only because the app role did not exist yet on that fresh container. That
 * is an accident of timing, and relying on it would be relying on the wrong
 * thing.
 *
 * <p>This control removes the accident: the roles are created up front, with
 * valid passwords and enough rights to run the migration. Flyway then connects
 * happily as {@code inventory_app} and applies the migration
 * <strong>successfully</strong>, recording {@code installed_by = inventory_app}.
 * Nothing throws. Nothing is logged as a problem. The only evidence is the
 * history table — which is exactly why the guard in the other tests is an
 * assertion on {@code installed_by} rather than a reliance on a crash.
 *
 * <h2>Why a separate migration location</h2>
 *
 * <p>{@code db/control} holds one trivial CREATE TABLE. Pointing this at the real
 * {@code db/migration} would conflate two questions: which connection Flyway
 * binds to, and whether the app role happens to hold the privileges V1–V3 need.
 * It does not — V2 and V3 both run {@code ALTER ROLE ... NOBYPASSRLS}, which
 * requires a superuser — so the run would fail for a reason unrelated to binding
 * and the control would prove nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Flyway binding with two datasources and NO explicit spring.flyway.* connection")
class FlywayPrimaryBindingControlTest {

    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "control_owner_pw";
    private static final String APP_ROLE = "inventory_app";
    private static final String APP_PASSWORD = "control_app_pw";
    private static final String LOGIN_ROLE = "inventory_login";
    private static final String LOGIN_PASSWORD = "control_login_pw";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("inventory")
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
        createRoles();
    }

    /**
     * Creates both roles before the Spring context boots, so that a Flyway run
     * bound to the primary pool succeeds instead of failing on authentication.
     *
     * <p>The CREATE grant on the app role is what makes the control honest:
     * without it this test would pass by throwing, and would then be evidence
     * that the misbinding is loud — the precise wrong conclusion.
     */
    private static void createRoles() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "' "
                    + "NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
            stmt.execute("GRANT CREATE, USAGE ON SCHEMA public TO " + APP_ROLE);

            // This control runs db/control rather than db/migration, so V3 never
            // executes and nothing else would create the login role — but
            // DataSourceConfig declares that pool unconditionally.
            stmt.execute("CREATE ROLE " + LOGIN_ROLE + " LOGIN PASSWORD '" + LOGIN_PASSWORD + "' "
                    + "NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT");
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + LOGIN_ROLE);
        } catch (Exception e) {
            throw new IllegalStateException("could not prepare the control database", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // The point of the control: spring.flyway.url / .user / .password are
        // NOT registered. Everything else matches FlywayBindingTest.
        registry.add("spring.flyway.locations", () -> "classpath:db/control");

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("app.datasource.login.url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.login.username", () -> LOGIN_ROLE);
        registry.add("app.datasource.login.password", () -> LOGIN_PASSWORD);
    }

    private static List<String> queryAsOwner(String sql) {
        List<String> values = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("owner query failed: " + sql, e);
        }
        return values;
    }

    @Test
    @DisplayName("without the explicit properties Flyway silently migrates as the PRIMARY pool's role")
    void withoutExplicitPropertiesFlywayUsesThePrimaryDataSource() {
        List<String> installedBy =
                queryAsOwner("SELECT DISTINCT installed_by FROM flyway_schema_history");

        assertThat(installedBy)
                .as("the control migration should have been applied — if this is empty, "
                        + "Flyway did not run and the control proves nothing")
                .isNotEmpty();

        assertThat(installedBy)
                .as("THIS is the hazard: with no explicit spring.flyway.* connection, Boot binds "
                        + "Flyway to the @Primary datasource, which is the RLS-bound application "
                        + "pool. If this ever starts reporting inventory_owner, the guard in "
                        + "FlywayBindingTest has stopped testing anything.")
                .containsExactly(APP_ROLE);
    }

    @Test
    @DisplayName("and it succeeds — the misbinding does not announce itself")
    void theMisbindingIsSilent() {
        assertThat(queryAsOwner(
                "SELECT bool_and(success)::text FROM flyway_schema_history WHERE version IS NOT NULL"))
                .as("the migration applied cleanly as the wrong role: no exception, no failed "
                        + "history row. Only installed_by records what happened.")
                .containsExactly("true");

        assertThat(queryAsOwner(
                "SELECT (to_regclass('public.control_probe') IS NOT NULL)::text"))
                .as("the table really was created, so this is a genuine successful run")
                .containsExactly("true");
    }
}
