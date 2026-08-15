package com.example.inventory;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
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
 * Pins down which connection Flyway migrates on once more than one
 * {@link DataSource} bean exists.
 *
 * <p>This is the M1 hazard, tested before M1 exists. M1 introduces a second pool:
 * the RLS-bound application pool alongside the unscoped owner pool. Spring Boot
 * migrates the <strong>primary</strong> datasource by default, so if the app pool
 * becomes primary and the explicit {@code spring.flyway.*} configuration ever
 * stopped taking precedence, migrations would silently start running as
 * {@code inventory_app} — a role with no DDL rights that is subject to every RLS
 * policy in the schema.
 *
 * <p>The setup here is deliberately hostile: two datasource beans, with
 * {@code @Primary} pointing at the <em>application</em> role, which is the wrong
 * connection for migrations. If {@code spring.flyway.*} wins, migrations still
 * run as the owner and {@code installed_by} proves it. If the primary bean were
 * to win instead, this test fails — either on the assertion or, more likely, when
 * Flyway cannot create a table as a role that has no rights to.
 *
 * <p>Uses its own container rather than the shared singleton from
 * {@link AbstractIntegrationTest}: the assertion depends on migrations actually
 * being applied during this context's startup, and on a shared container an
 * earlier test class would already have applied them.
 *
 * <p><strong>Read this together with {@link FlywayPrimaryBindingControlTest}.</strong>
 * That class is the negative control: it removes {@code spring.flyway.url/user/password}
 * and nothing else, and shows Flyway then migrating as {@code inventory_app}
 * successfully and silently. Without it, the assertions here could pass for
 * reasons having nothing to do with the configuration they claim to protect.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FlywayBindingTest.TwoPools.class)
@DisplayName("Flyway binding with two datasources")
class FlywayBindingTest {

    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "binding_owner_pw";
    private static final String APP_ROLE = "inventory_app";
    private static final String APP_PASSWORD = "binding_app_pw";

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("inventory")
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Explicit Flyway connection: the schema owner.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER_ROLE);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.appUserPassword", () -> APP_PASSWORD);
    }

    /**
     * Two pools, primary deliberately pointing at the role that must never run
     * migrations.
     */
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
    @DisplayName("migrations run as the schema owner, never as the application role")
    void migrationsRunAsTheSchemaOwner() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);

        var installedBy = owner.queryForList(
                "SELECT DISTINCT installed_by FROM flyway_schema_history", String.class);

        assertThat(installedBy)
                .as("flyway_schema_history should record who applied each migration")
                .isNotEmpty();

        assertThat(installedBy)
                .as("migrations must never be applied by the RLS-bound application role")
                .doesNotContain(APP_ROLE);

        assertThat(installedBy)
                .as("migrations must be applied by the schema owner only")
                .containsExactly(OWNER_ROLE);
    }

    @Test
    @DisplayName("both migrations actually applied on this context's own database")
    void migrationsWereAppliedHere() {
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);

        var versions = owner.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL "
                        + "ORDER BY installed_rank", String.class);

        // Guards the test itself: if this were empty, migrationsRunAsTheSchemaOwner
        // would be asserting over nothing and would pass for the wrong reason.
        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    @DisplayName("the primary pool is the application role, so the setup is genuinely hostile")
    void primaryPoolIsTheApplicationRole(@Autowired DataSource primary) {
        String user = new JdbcTemplate(primary).queryForObject("SELECT current_user", String.class);

        assertThat(user)
                .as("if the primary pool were the owner, this test would prove nothing")
                .isEqualTo(APP_ROLE);
    }

    /**
     * Asks the configured {@link Flyway} bean directly which connection it holds,
     * rather than inferring it after the fact from {@code installed_by}.
     *
     * <p>The two assertions fail at different times and that is the point.
     * {@code installed_by} is historical: it can only be checked after migrations
     * have run, and against a database that was already migrated it reports what
     * some earlier run did, not what this configuration would do now. This one is
     * a live property of the wiring, so it stays meaningful even when there is
     * nothing left to migrate.
     */
    @Test
    @DisplayName("the Flyway bean itself holds an owner connection, not the primary pool")
    void flywayBeanIsBoundToTheOwnerConnection(@Autowired Flyway flyway) {
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();

        assertThat(flywayDataSource)
                .as("Flyway must have a datasource of its own, distinct from the primary bean")
                .isNotNull();

        String user = new JdbcTemplate(flywayDataSource)
                .queryForObject("SELECT current_user", String.class);

        assertThat(user)
                .as("spring.flyway.url/user/password must win over the @Primary DataSource bean")
                .isEqualTo(OWNER_ROLE);
    }
}
