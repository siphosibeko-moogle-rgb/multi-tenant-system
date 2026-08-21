package com.example.inventory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down which connection Flyway migrates on now that the application really
 * does run two pools.
 *
 * <p>This test used to build its own hostile two-pool fixture, because M1 did not
 * exist yet. It no longer needs to: {@code DataSourceConfig} defines exactly that
 * arrangement — {@code inventory_app} as {@code @Primary}, {@code inventory_login}
 * alongside it — so the hostile setup <em>is</em> the production setup, and this
 * test now exercises the real configuration rather than an approximation of it.
 *
 * <p>Boot migrates the primary datasource by default. The primary datasource is
 * the RLS-bound application pool, which has no DDL rights and is subject to every
 * policy in the schema. What prevents migrations running there is
 * {@code spring.flyway.url/user/password}, and nothing else.
 *
 * <p><strong>Read this together with {@link FlywayPrimaryBindingControlTest}.</strong>
 * That class is the negative control: it removes those three properties and
 * nothing else, and shows Flyway then migrating as {@code inventory_app}
 * successfully and silently. Without it, the assertions here could pass for
 * reasons having nothing to do with the configuration they claim to protect.
 *
 * <p>Uses its own container rather than the shared singleton from
 * {@link AbstractIntegrationTest}: the assertions depend on migrations actually
 * being applied during this context's startup, and on a shared container an
 * earlier test class would already have applied them.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Flyway binding with two datasources")
class FlywayBindingTest {

    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "binding_owner_pw";
    private static final String APP_ROLE = "inventory_app";
    private static final String APP_PASSWORD = "binding_app_pw";
    private static final String LOGIN_ROLE = "inventory_login";
    private static final String LOGIN_PASSWORD = "binding_login_pw";

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
        // Explicit Flyway connection: the schema owner. These three lines are
        // the entire subject of this test.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER_ROLE);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.appUserPassword", () -> APP_PASSWORD);
        registry.add("spring.flyway.placeholders.loginUserPassword", () -> LOGIN_PASSWORD);

        // The two real pools from DataSourceConfig. @Primary is the app role —
        // which is precisely the wrong connection for migrations.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("app.datasource.login.url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.login.username", () -> LOGIN_ROLE);
        registry.add("app.datasource.login.password", () -> LOGIN_PASSWORD);
    }

    /**
     * Reads the migration history as the owner.
     *
     * <p>Deliberately a raw connection rather than an injected bean: the
     * application pool is subject to RLS and V2 revokes its write privileges on
     * {@code flyway_schema_history}, and the point here is to observe the history
     * from outside the arrangement being tested.
     */
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
    @DisplayName("migrations run as the schema owner, never as the application role")
    void migrationsRunAsTheSchemaOwner() {
        List<String> installedBy =
                queryAsOwner("SELECT DISTINCT installed_by FROM flyway_schema_history");

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
    @DisplayName("every migration actually applied on this context's own database")
    void migrationsWereAppliedHere() {
        List<String> versions = queryAsOwner(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL "
                        + "ORDER BY installed_rank");

        // Guards the test itself: if this were empty, migrationsRunAsTheSchemaOwner
        // would be asserting over nothing and would pass for the wrong reason.
        // Written out by hand, and kept that way on purpose: a list read from
        // flyway_schema_history would shrink to match whatever it found,
        // including a migration that silently failed to apply, and would agree
        // with the bug instead of catching it. Same reasoning as T11's tables.
        // V5 (sale_returns), V6 (partially_refunded_status), V7
        // (sale_numbering) and V8 (po_numbering) added with the migrations
        // that introduced them.
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    @DisplayName("the primary pool is the application role, so the setup is genuinely hostile")
    void primaryPoolIsTheApplicationRole(@Autowired DataSource primary) {
        String user = new JdbcTemplate(primary).queryForObject("SELECT current_user", String.class);

        assertThat(user)
                .as("if the primary pool were the owner, this test would prove nothing")
                .isEqualTo(APP_ROLE);
    }

    @Test
    @DisplayName("the login pool is a second, distinct datasource bean")
    void theLoginPoolIsASecondDataSource(@Autowired DataSource primary) {
        // The hazard this whole class exists for only arises when more than one
        // DataSource bean is present. If the login pool ever stopped being a
        // separate bean, Flyway's default binding would no longer be ambiguous
        // and these assertions would be guarding a situation that no longer
        // occurs — passing, but pointless.
        String loginUser = new JdbcTemplate(loginDataSource)
                .queryForObject("SELECT current_user", String.class);

        assertThat(loginUser).isEqualTo(LOGIN_ROLE);
        assertThat(loginDataSource)
                .as("two distinct pools, not one bean injected twice")
                .isNotSameAs(primary);
    }

    // @Qualifier is required: @Primary outranks by-field-name matching, so
    // without it this injects the application pool instead.
    @Autowired
    @Qualifier("loginDataSource")
    private DataSource loginDataSource;

    @Test
    @DisplayName("the Flyway bean itself holds an owner connection, not either pool")
    void flywayBeanIsBoundToTheOwnerConnection(@Autowired Flyway flyway) {
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();

        assertThat(flywayDataSource)
                .as("Flyway must have a datasource of its own, distinct from the pool beans")
                .isNotNull();

        String user = new JdbcTemplate(flywayDataSource)
                .queryForObject("SELECT current_user", String.class);

        assertThat(user)
                .as("spring.flyway.url/user/password must win over the @Primary DataSource bean")
                .isEqualTo(OWNER_ROLE);
    }
}
