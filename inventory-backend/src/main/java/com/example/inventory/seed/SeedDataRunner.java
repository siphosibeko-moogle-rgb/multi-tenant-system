package com.example.inventory.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * M6: {@code --spring.profiles.active=seed} entry point.
 *
 * <pre>
 *   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,seed
 * </pre>
 *
 * <p>{@code local} for the datasource (same as an ordinary local run);
 * {@code seed} activates this bean, the only thing that {@code @Profile}
 * guards in this codebase's {@code main} sources. It calls
 * {@link TenantSeeder#seedTenant} for two fresh tenants at the full 6-12
 * month window, then exits — this is meant to be run once per invocation and
 * read from the database afterward, not to leave a server listening.
 *
 * <p>Every run creates two BRAND NEW tenants (the slug carries a timestamp),
 * never updates existing ones — re-running is exactly "seed another pair",
 * matching "you'll likely want to re-run it" rather than an idempotent
 * upsert. Old seeded tenants are not cleaned up; drop and recreate the local
 * database ({@code docker compose down -v && docker compose up -d db}) for a
 * clean slate.
 */
@Component
@Profile("seed")
public class SeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataRunner.class);

    /** ~7 months — comfortably inside the 6-12 month ask, and well above the
     * ADR's ~14-week readiness point for the intermittent shape (§5). */
    private static final int WINDOW_WEEKS = 30;

    private final TenantSeeder seeder;
    private final ConfigurableApplicationContext context;

    public SeedDataRunner(TenantSeeder seeder, ConfigurableApplicationContext context) {
        this.seeder = seeder;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        long tag = System.currentTimeMillis() % 1_000_000_000L;
        log.info("M6 seed data: generating {} weeks of history for two tenants (tag {})", WINDOW_WEEKS, tag);

        var tenantA = seeder.seedTenant("Riverside Bakery", "riverside-" + tag, tag, WINDOW_WEEKS);
        log.info("Seeded tenant A: {} (slug riverside-{}), products: {}",
                tenantA.tenantId(), tag, tenantA.productIdsByShape());

        var tenantB = seeder.seedTenant("Harborview Deli", "harborview-" + tag, tag + 1, WINDOW_WEEKS);
        log.info("Seeded tenant B: {} (slug harborview-{}), products: {}",
                tenantB.tenantId(), tag, tenantB.productIdsByShape());

        log.info("M6 seed data complete. Owner login: owner+riverside-{}@example.test / "
                + "owner+harborview-{}@example.test, password SeedPassw0rd!1", tag, tag);

        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
