package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three ways {@link Forecaster} withholds a reorder point, each on a fixture
 * where <strong>only that one reason</strong> can explain the null.
 *
 * <h2>Why this class exists — a mutation survived without it</h2>
 *
 * <p>{@code ReorderPointSeedDataTest} appeared to cover the no-supplier case: it
 * asserts that M6's dead-stock and brand-new products resolve no lead time, and
 * that their forecasts carry no reorder point. Both assertions were true and
 * neither tested what its name claimed.
 *
 * <p>The two unlinked products in the seed data are also the two products below
 * the readiness threshold, so their reorder point is already null for a
 * completely different reason. Making {@code Forecaster} invent a default 7-day
 * lead time whenever a product had no supplier left the entire suite green:
 * the readiness guard reached those products first, and the no-supplier guard
 * was never executed by any test at all.
 *
 * <p>That is CLAUDE.md §5's "a test can pass while exercising the wrong guard",
 * and the fix is the one that section prescribes — a fixture where the guard
 * under test is the only one reachable. Here that means a product with
 * <em>ample</em> history and <em>no</em> supplier, which the seed data does not
 * contain and, being realistic data, has no particular reason to.
 */
@DisplayName("Forecaster withholds a reorder point rather than inventing one")
class ForecasterWithholdingTest extends AbstractIntegrationTest {

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private Forecaster forecaster;

    private static UUID tenantId;
    private static UUID locationId;
    private static UUID supplierId;

    /** Ample history, a supplier, real demand. The positive control. */
    private static UUID ready;

    /** Ample history and real demand, but NO supplier link. */
    private static UUID readyWithoutSupplier;

    /** A supplier and a stocked shelf, but only a handful of selling days. */
    private static UUID tooNew;

    /** Ample history and a supplier, but never sold a single unit. */
    private static UUID zeroDemand;

    private static boolean seeded;

    @BeforeEach
    void seed() throws Exception {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        locationId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        ready = UUID.randomUUID();
        readyWithoutSupplier = UUID.randomUUID();
        tooNew = UUID.randomUUID();
        zeroDemand = UUID.randomUUID();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(90);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    INSERT INTO tenants (id, slug, name, timezone)
                    VALUES ('%s', 'withhold-%s', 'Withholding Fixture', 'UTC')
                    """.formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Shelf', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO suppliers (id, tenant_id, name, lead_time_days)
                    VALUES ('%s', '%s', 'Fixture Supplies', 7)
                    """.formatted(supplierId, tenantId));

            int n = 0;
            for (UUID productId : new UUID[]{ready, readyWithoutSupplier, tooNew, zeroDemand}) {
                stmt.execute("""
                        INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price, tax_rate)
                        VALUES ('%s', '%s', 'WH-%s-%d', 'Withholding Product %d', 4.00, 10.00, 0)
                        """.formatted(productId, tenantId,
                        tenantId.toString().substring(0, 8), n, n));
                n++;
            }

            // Every product EXCEPT readyWithoutSupplier gets a supplier link.
            // That single omission is the whole point of the class.
            for (UUID productId : new UUID[]{ready, tooNew, zeroDemand}) {
                stmt.execute("""
                        INSERT INTO product_suppliers
                            (tenant_id, product_id, supplier_id, unit_cost, is_preferred)
                        VALUES ('%s', '%s', '%s', 4.00, true)
                        """.formatted(tenantId, productId, supplierId));
            }
        }

        asTenant(() -> {
            // Two products with identical, ample, ready histories. They differ
            // in exactly one respect: one has a supplier and one does not.
            for (UUID productId : new UUID[]{ready, readyWithoutSupplier}) {
                stock(productId, start.minusDays(1), 900);
                for (LocalDate d = start; d.isBefore(today); d = d.plusDays(1)) {
                    sell(productId, d, 3);
                }
            }

            // Stocked and selling, but only for a week — fails the 42-day floor.
            stock(tooNew, today.minusDays(7), 100);
            for (LocalDate d = today.minusDays(7); d.isBefore(today); d = d.plusDays(1)) {
                sell(tooNew, d, 3);
            }

            // Ample calendar history, a supplier, stock on the shelf the whole
            // time — and not one sale.
            stock(zeroDemand, start, 100);
            return rollup.rollUp(today);
        });
        seeded = true;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(tenantId, null, null));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private void stock(UUID productId, LocalDate day, int quantity) {
        ledger.post(new MovementRequest(productId, locationId, "adjustment",
                new BigDecimal(quantity), new BigDecimal("4.00"), null, null, "fixture",
                day.atTime(8, 0).atOffset(ZoneOffset.UTC)));
    }

    private void sell(UUID productId, LocalDate day, int quantity) {
        ledger.post(new MovementRequest(productId, locationId, "sale",
                new BigDecimal(quantity).negate(), new BigDecimal("4.00"), null, null, "fixture",
                day.atTime(12, 0).atOffset(ZoneOffset.UTC)));
    }

    private Forecast forecastFor(UUID productId) throws Exception {
        return asTenant(() -> forecaster.forecast(productId, locationId));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the positive control: ample history AND a supplier produces a real reorder point")
    void aReadyProductWithASupplierGetsANumber() throws Exception {
        Forecast forecast = forecastFor(ready);

        assertThat(forecast.method())
                .as("90 days of daily sales clears both halves of ADR §5")
                .isNotEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        assertThat(forecast.leadTime())
                .as("and it has a supplier to take a lead time from")
                .isNotNull();
        assertThat(forecast.reorderPoint())
                .as("so there is nothing to withhold. Without this control, every assertion "
                        + "below would pass for a Forecaster that returns null unconditionally.")
                .isNotNull();
        assertThat(forecast.reorderPoint()).isPositive();
    }

    @Test
    @DisplayName("no supplier: withheld, even though the history is ample")
    void aReadyProductWithoutASupplierGetsNoReorderPoint() throws Exception {
        Forecast forecast = forecastFor(readyWithoutSupplier);

        // The guard under test is the ONLY one this product can reach. It has
        // the same 90 days of daily sales as the control above, so readiness
        // cannot explain a null, and its demand is non-zero, so that cannot
        // either.
        assertThat(forecast.method())
                .as("readiness is satisfied — this product is not being refused for being new")
                .isNotEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        assertThat(forecast.avgDailyDemand())
                .as("and its demand is real — 3 units a day, every day")
                .isPositive();

        assertThat(forecast.leadTime())
                .as("but nothing records who supplies it")
                .isNull();
        assertThat(forecast.reorderPoint())
                .as("so there is no lead time, and a reorder point needs one (ADR §2). "
                        + "Substituting a plausible default here produces a made-up number "
                        + "indistinguishable from a real one — which is precisely the mutation "
                        + "that survived before this test existed.")
                .isNull();
    }

    @Test
    @DisplayName("not ready: withheld on the readiness threshold, with a supplier available")
    void anUnreadyProductGetsNoReorderPointEvenWithASupplier() throws Exception {
        Forecast forecast = forecastFor(tooNew);

        assertThat(forecast.method()).isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
        assertThat(forecast.reorderPoint())
                .as("ADR §5: null, not a confident number from a week of data")
                .isNull();
        assertThat(forecast.projectedStockoutOn()).isNull();

        // The distinguishing assertion: this product DOES have a supplier, so
        // the null above can only be the readiness guard.
        assertThat(asTenant(() -> forecaster.forecast(tooNew, locationId)))
                .isNotNull();
        assertThat(forecast.selection().daysShortOfHistory())
                .as("and it can say how far short it is, which is what ADR §6's explanation "
                        + "has to quote")
                .isPositive();
    }

    @Test
    @DisplayName("zero measured demand: withheld, with both history and a supplier available")
    void aProductThatNeverSoldGetsNoReorderPoint() throws Exception {
        Forecast forecast = forecastFor(zeroDemand);

        assertThat(forecast.reorderPoint())
                .as("there is nothing to reorder against. A zero reorder point would read as "
                        + "'never restock this', which is a different claim from 'we cannot "
                        + "tell you'.")
                .isNull();
        assertThat(forecast.daysOfCover())
                .as("and cover is effectively infinite, which V1's own comment on the column "
                        + "says is expressed as null")
                .isNull();
    }
}
