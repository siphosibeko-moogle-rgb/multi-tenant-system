package com.example.inventory;

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
 * <h2>Container reuse</h2>
 *
 * <p>{@code withReuse(true)} only takes effect if the developer has opted in on
 * their machine with {@code testcontainers.reuse.enable=true} in
 * {@code ~/.testcontainers.properties}. Without it Testcontainers logs a notice
 * and falls back to a fresh container per run — correct either way, just slower.
 * See the README.
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

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(OWNER_ROLE)
                    .withPassword(OWNER_PASSWORD)
                    .withReuse(true);

    static {
        POSTGRES.start();
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

        // The application: the restricted role, exactly as in production.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
    }
}
