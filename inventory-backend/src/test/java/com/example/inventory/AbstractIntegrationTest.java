package com.example.inventory;

import java.util.UUID;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for every integration test.
 *
 * <p>Real PostgreSQL 16, never H2. H2 has neither row-level security nor
 * {@code set_config}, so it cannot exercise the mechanism the entire tenancy
 * story rests on — see CLAUDE.md T10.
 *
 * <h2>Why the container is started in a static block</h2>
 *
 * <p>Not {@code @Testcontainers}/{@code @Container}: that annotation pair ties the
 * container lifecycle to a single test class and stops it on teardown, which
 * defeats reuse. A static singleton started once per JVM is shared by every
 * subclass, and with reuse enabled it survives between runs as well.
 *
 * <h2>Two roles, on purpose</h2>
 *
 * <p>Flyway connects as the container's admin role — the schema owner — because
 * migrations are DDL. The application datasource connects as {@code inventory_app},
 * the NOSUPERUSER / NOBYPASSRLS role that {@code V2__app_role.sql} creates. That
 * split is the whole point: a superuser or a BYPASSRLS role silently turns every
 * policy from V1 into a no-op, so tests run through the same restricted role
 * production uses (CLAUDE.md T2).
 *
 * <h2>Container reuse is deliberately OFF</h2>
 *
 * <p>Reuse would share one container across runs. Because these tests seed
 * tenants, the first cross-run collision would present as a tenant isolation
 * failure — the most expensive possible false alarm in this codebase, since the
 * correct response to a real one is auditing every query written since M1. The
 * startup time saved is not worth that. The README documents the opt-in for
 * anyone who wants it locally, but nothing here asks for it.
 *
 * <p>Isolation between tests does not depend on that decision, though: the
 * container is a per-JVM singleton shared by every test class in a run, so tests
 * must not collide <em>within</em> a run either. {@link #newTenantId()} is how —
 * see its javadoc.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    /** Matches docker-compose.yml so local and test behave the same way. */
    private static final String DATABASE_NAME = "inventory";

    /** Schema owner. Flyway uses this; the application never does. */
    private static final String OWNER_ROLE = "inventory_owner";
    private static final String OWNER_PASSWORD = "test_owner_only";

    /** Created by V2. NOSUPERUSER, NOBYPASSRLS — subject to RLS. */
    protected static final String APP_ROLE = "inventory_app";
    protected static final String APP_PASSWORD = "test_app_only";

    /**
     * Created by V3. NOSUPERUSER, NOBYPASSRLS, NOINHERIT — SELECT on
     * {@code tenants} and {@code users} only, readable unscoped through the
     * {@code login_read} policy. A different password from the app role's,
     * matching production: separate roles, separate secrets.
     */
    protected static final String LOGIN_ROLE = "inventory_login";
    protected static final String LOGIN_PASSWORD = "test_login_only";

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
    }

    /**
     * A tenant id no other test will ever use.
     *
     * <p>Every test that seeds a tenant must take its ids from here rather than
     * hard-coding a literal UUID. One container is shared by every test class in
     * a run, so two classes that both seeded, say,
     * {@code 00000000-0000-0000-0000-000000000001} would see each other's rows —
     * and that shows up as a failing isolation assertion rather than as the test
     * collision it actually is. Random v4 ids make the collision impossible
     * instead of unlikely-and-debugged-later.
     *
     * <p>This also means a test must never assert on a fixed tenant id, and must
     * never assume it is the only tenant in the database.
     */
    protected static UUID newTenantId() {
        return UUID.randomUUID();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        // Flyway: the schema owner, on its own connection.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        // Substituted into CREATE ROLE ... PASSWORD in V2, so it has to agree
        // with the application password below.
        registry.add("spring.flyway.placeholders.appUserPassword", () -> APP_PASSWORD);
        // Substituted into CREATE ROLE ... PASSWORD in V3. Must agree with the
        // login pool below for the same reason appUserPassword must agree with
        // the application pool.
        registry.add("spring.flyway.placeholders.loginUserPassword", () -> LOGIN_PASSWORD);

        // The application: the restricted role, exactly as in production.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);

        // The login pool. DataSourceConfig defines both beans, so this is not
        // optional configuration in tests — the context will not start without
        // it, which is the intended outcome: a test environment missing the
        // login pool would silently prove nothing about it.
        registry.add("app.datasource.login.url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.login.username", () -> LOGIN_ROLE);
        registry.add("app.datasource.login.password", () -> LOGIN_PASSWORD);
    }
}
