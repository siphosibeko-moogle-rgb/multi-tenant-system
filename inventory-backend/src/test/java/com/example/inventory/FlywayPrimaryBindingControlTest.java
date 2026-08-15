package com.example.inventory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * and something else happened to produce an owner connection, and it would pass
 * if the primary datasource were the owner too. A guard nobody has ever seen fail
 * is not a guard.
 *
 * <p>So this test removes the three properties and changes nothing else. If the
 * explicit configuration is what does the work, migrations here must run as the
 * <em>primary</em> pool's role — {@code inventory_app}. That is the assertion
 * below, and it is deliberately an assertion on success rather than on an
 * exception.
 *
 * <h2>The failure this documents is silent, not loud</h2>
 *
 * <p>CLAUDE.md section 9 records that the original manual control "failed loudly"
 * with {@code password authentication failed for user "inventory_app"} — but only
 * because the app role did not exist yet on that fresh container. That is an
 * accident of timing, and relying on it would be relying on the wrong thing.
 *
 * <p>This control removes the accident: the app role is created up front, with a
 * valid password and enough rights to run the migration. Flyway then connects
 * happily as {@code inventory_app} and applies the migration <strong>successfully</strong>,
 * recording {@code installed_by = inventory_app}. Nothing throws. Nothing is
 * logged as a problem. The only evidence is the history table — which is exactly
 * why the guard in the other tests is an assertion on {@code installed_by} rather
 * than a reliance on a crash.
 *
 * <h2>Why a separate migration location</h2>
 *
 * <p>{@code db/control} holds one trivial CREATE TABLE. Pointing this at the real
 * {@code db/migration} would conflate two questions: which connection Flyway
 * binds to, and whether the app role happens to hold the privileges V1 and V2
 * need. It does not — V2's {@code ALTER ROLE ... NOBYPASSRLS} requires a
 * superuser — so the run would fail for a reason unrelated to binding and the
 * control would prove nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FlywayPrimaryBindingControlTest.TwoPools.class)
@DisplayName("Flyway binding with two datasources and NO explicit spring.flyway.* connection")
class FlywayPrimaryBindingControlTest {

    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "control_owner_pw";
    private static final String APP_ROLE = "inventory_app";
    private static final String APP_PASSWORD = "control_app_pw";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("inventory")
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
        createApplicationRole();
    }

    /**
     * Creates the app role before the Spring context boots, so that a Flyway run
     * bound to the primary pool succeeds instead of failing on authentication.
     *
     * <p>The CREATE grant is what makes the control honest: without it this test
     * would pass by throwing, and would then be evidence that the misbinding is
     * loud — the precise wrong conclusion.
     */
    private static void createApplicationRole() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), OWNER_ROLE, OWNER_PASSWORD);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "' "
                    + "NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
            stmt.execute("GRANT CREATE, USAGE ON SCHEMA public TO " + APP_ROLE);
        } catch (Exception e) {
            throw new IllegalStateException("could not prepare the control database", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // The point of the control: spring.flyway.url / .user / .password are
        // NOT registered. Everything else matches FlywayBindingTest.
        registry.add("spring.flyway.locations", () -> "classpath:db/control");
    }

    /** Two pools, primary pointing at the application role — same shape as M1. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TwoPools {

        @Bean
        @Primary
        DataSource appDataSource() {
            return DataSourceBuilder.create()
                    .url(POSTGRES.getJdbcUrl())
                    .username(APP_ROLE)
                    .password(APP_PASSWORD)
                    .build();
        }

        @Bean
        DataSource ownerDataSource() {
            return DataSourceBuilder.create()
                    .url(POSTGRES.getJdbcUrl())
                    .username(OWNER_ROLE)
                    .password(OWNER_PASSWORD)
                    .build();
        }
    }

    @Autowired
    @Qualifier("ownerDataSource")
    private DataSource ownerDataSource;

    @Test
    @DisplayName("without the explicit properties Flyway silently migrates as the PRIMARY pool's role")
    void withoutExplicitPropertiesFlywayUsesThePrimaryDataSource() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);

        var installedBy = owner.queryForList(
                "SELECT DISTINCT installed_by FROM flyway_schema_history", String.class);

        assertThat(installedBy)
                .as("the control migration should have been applied — if this is empty, "
                        + "Flyway did not run and the control proves nothing")
                .isNotEmpty();

        assertThat(installedBy)
                .as("THIS is the M1 hazard: with no explicit spring.flyway.* connection, Boot "
                        + "binds Flyway to the @Primary datasource, which in M1 is the RLS-bound "
                        + "application pool. If this ever starts reporting inventory_owner, the "
                        + "guard in FlywayBindingTest has stopped testing anything.")
                .containsExactly(APP_ROLE);
    }

    @Test
    @DisplayName("and it succeeds — the misbinding does not announce itself")
    void theMisbindingIsSilent() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);

        Boolean succeeded = owner.queryForObject(
                "SELECT bool_and(success) FROM flyway_schema_history WHERE version IS NOT NULL",
                Boolean.class);

        assertThat(succeeded)
                .as("the migration applied cleanly as the wrong role: no exception, no failed "
                        + "history row. Only installed_by records what happened.")
                .isTrue();

        assertThat(owner.queryForObject(
                "SELECT to_regclass('public.control_probe') IS NOT NULL", Boolean.class))
                .as("the table really was created, so this is a genuine successful run")
                .isTrue();
    }
}
