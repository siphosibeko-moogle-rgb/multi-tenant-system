package com.example.inventory.forecasting;

import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recompute refreshes the demand data it forecasts from.
 *
 * <h2>Why this needed its own test</h2>
 *
 * <p>{@code ReorderService.recomputeAll()} used to begin at the second stage of
 * the pipeline: it read {@code demand_daily} and never populated it.
 * {@code DemandRollupJob} is called nowhere else in the application, so on a
 * real deployment the table stayed empty forever — every product reported
 * {@code insufficient_data}, every reorder list was blank, and nothing errored
 * anywhere.
 *
 * <p><strong>Every M7 test missed it, and they missed it the same way.</strong>
 * Each one rolls up explicitly in its fixture before recomputing, because each
 * was written to test the stage it was about. That is reasonable per test and
 * fatal in aggregate: the suite only ever exercised the second stage with the
 * first already done by hand, so the wiring between them was never covered by
 * anything.
 *
 * <p>Found by pointing the Android client at a database holding two fully
 * seeded tenants and getting an empty reorder list, then counting rows: 0 in
 * {@code demand_daily}, 0 forecasts, 0 recommendations.
 *
 * <p>So this test's whole discipline is that it <strong>never calls
 * {@link DemandRollupJob}</strong>. It seeds, then calls the one method the API
 * exposes, and asserts the pipeline ran end to end. Adding a rollup to this
 * fixture would restore precisely the blind spot it exists to close.
 */
@DisplayName("A recompute rolls up first")
class RecomputeRunsTheRollupTest extends AbstractIntegrationTest {

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    @Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private SeededTenant tenant;

    /**
     * A fresh tenant per test, not the usual static-guard fixture.
     *
     * <p>Both tests below recompute, and a recompute populates
     * {@code demand_daily} — so sharing one tenant means whichever test runs
     * second finds the table already full and its "nothing has rolled up yet"
     * premise false. JUnit promises no ordering, so that is a coin flip, and it
     * failed on the first run here.
     *
     * <p>Ten weeks of seeding per test is a few seconds. Worth it: the whole
     * point of this class is that the table starts empty, and a fixture that
     * cannot guarantee that guarantees nothing.
     *
     * <p>Deliberately NO rollup here. See the class Javadoc.
     */
    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Rollup Wiring " + tag, "wiring-" + tag, 1L, 10);
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(
                tenant.tenantId(), tenant.ownerId(), "owner"));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private long count(String table) throws Exception {
        return asTenant(() -> new JdbcTemplate(appDataSource).queryForObject(
                "SELECT count(*) FROM " + table, Long.class));
    }

    @Test
    @DisplayName("recomputing populates demand_daily, not just forecasts")
    void recomputeRefreshesTheDemandDataItForecastsFrom() throws Exception {
        assertThat(count("demand_daily"))
                .as("fixture check: nothing has rolled up yet, so the table this recompute "
                        + "reads from is empty. If this is ever non-zero, something in the "
                        + "fixture has started rolling up and the test has stopped testing "
                        + "the wiring.")
                .isZero();

        ReorderService.RecomputeResult result = asTenant(() -> reorderService.recomputeAll());

        assertThat(count("demand_daily"))
                .as("the ledger holds 10 weeks of seeded history for seven products, so a "
                        + "recompute that refreshed the demand data has rows here. Zero means "
                        + "the pipeline started at stage two — which errors nowhere and simply "
                        + "reports every product as insufficient_data forever.")
                .isGreaterThan(0);

        assertThat(result.forecastsWritten())
                .as("and a forecast per seeded product/location, from data this call produced "
                        + "itself rather than data a test happened to lay down first")
                .isEqualTo(7);

        assertThat(count("forecasts"))
                .as("persisted, not merely counted in the result")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("recomputing twice is safe — the rollup underneath is idempotent")
    void asecondRecomputeDoesNotDoubleTheDemandRows() throws Exception {
        asTenant(() -> reorderService.recomputeAll());
        long afterFirst = count("demand_daily");

        asTenant(() -> reorderService.recomputeAll());

        assertThat(count("demand_daily"))
                .as("DemandRollupJob upserts, so running it on every recompute must not "
                        + "accumulate — otherwise the fix for the empty-table bug would trade "
                        + "it for a growing one")
                .isEqualTo(afterFirst);
    }
}
