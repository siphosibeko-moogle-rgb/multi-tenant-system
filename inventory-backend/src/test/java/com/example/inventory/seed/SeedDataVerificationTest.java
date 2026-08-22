package com.example.inventory.seed;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.seed.TenantSeeder.SeededTenant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6's real acceptance criteria — CLAUDE.md §5: these are claims about what
 * ended up in the database, so the test queries the database, the same way
 * {@code TenantIsolationTest} does rather than trusting the generator's own
 * bookkeeping.
 *
 * <p>Runs {@link TenantSeeder#seedTenant} for two tenants exactly once (a
 * {@code @BeforeEach} guarded by a static flag — see {@link #seed()}) — it is
 * the slow part of this class by a wide margin, and every criterion below can
 * be checked against the same two runs. A shorter window than the real seed
 * run's 30 weeks
 * (below the intermittent shape's ~14-week readiness point, deliberately —
 * this class proves the MECHANISM, not the full-scale realism the real
 * {@code --spring.profiles.active=seed} run produces) keeps this fast enough
 * to run with everything else.
 */
@DisplayName("M6 seed data")
class SeedDataVerificationTest extends AbstractIntegrationTest {

    private static final int WINDOW_WEEKS = 10;

    @Autowired
    private TenantSeeder seeder;

    private static SeededTenant tenantA;
    private static SeededTenant tenantB;
    private static boolean seeded;

    /**
     * {@code @BeforeEach} with a static guard, not {@code @BeforeAll} —
     * {@code @BeforeAll} runs before {@code SpringExtension} loads the
     * context, so {@code seeder} would still be null (CLAUDE.md §10). Runs
     * once for the whole class, since this generator is the slow part of
     * this test by a wide margin and every criterion below can share the
     * same two seeded tenants.
     */
    @BeforeEach
    void seed() {
        if (seeded) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenantA = seeder.seedTenant("Riverside Bakery Alpha", "seed-a-" + tag, 1L, WINDOW_WEEKS);
        tenantB = seeder.seedTenant("Riverside Bakery Beta", "seed-b-" + tag, 2L, WINDOW_WEEKS);
        seeded = true;
    }

    private <T> T asOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true))
                    .queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the steady seller has both zero-demand and non-zero-demand days — the single most common seeding bug")
    void steadySellerHasZeroAndNonZeroDemandDays() {
        UUID productId = tenantA.productIdsByShape().get("steady");

        long calendarDays = WINDOW_WEEKS * 7L;
        long daysWithASale = asOwner("""
                SELECT count(DISTINCT occurred_at::date) FROM stock_movements
                WHERE product_id = ? AND movement_type = 'sale'
                """, Long.class, productId);

        assertThat(daysWithASale)
                .as("a steady seller selling on ~90%% of days must have SOME non-zero days")
                .isGreaterThan(0);
        assertThat(daysWithASale)
                .as("and it must NOT be selling on every single calendar day — a generator that "
                        + "does would never produce a zero-demand day, which is exactly the bug "
                        + "docs/MILESTONES.md calls out")
                .isLessThan(calendarDays);
    }

    @Test
    @DisplayName("the stockout period is real: sold to zero, refused while at zero, restocked")
    void stockoutIsGenuine() {
        UUID productId = tenantA.productIdsByShape().get("stockout");

        // The running balance, reconstructed from the ledger itself rather
        // than trusted from product_stock, must (a) never go negative — no
        // trigger lets a movement drive stock below zero (T12) — and (b)
        // genuinely touch zero at some point. seedStockoutProduct's own
        // internal check (throwing if a sale unexpectedly succeeded during
        // the intended outage) is the guarantee that the refused attempts
        // actually happened; this independently confirms the shelf really
        // was empty, not just that the refusals were coded to succeed.
        BigDecimal minEverObserved = asOwner("""
                SELECT MIN(running) FROM (
                    SELECT SUM(quantity_delta) OVER (ORDER BY occurred_at, id) AS running
                    FROM stock_movements WHERE product_id = ?
                ) balances
                """, BigDecimal.class, productId);
        assertThat(minEverObserved.signum()).as("stock must never go negative (T12)").isGreaterThanOrEqualTo(0);
        assertThat(minEverObserved).as("the product must genuinely have hit zero at some point")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // And recovered: current stock is positive again.
        BigDecimal currentStock = asOwner("""
                SELECT COALESCE(SUM(quantity_on_hand), 0) FROM product_stock WHERE product_id = ?
                """, BigDecimal.class, productId);
        assertThat(currentStock.signum()).as("the product must have been restocked afterward")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("dead stock has no sales at all in the window")
    void deadStockHasNoSales() {
        UUID productId = tenantA.productIdsByShape().get("dead");

        Long saleCount = asOwner("""
                SELECT count(*) FROM stock_movements WHERE product_id = ? AND movement_type = 'sale'
                """, Long.class, productId);
        assertThat(saleCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("the brand-new product's sales history is under 2 weeks old")
    void brandNewHistoryIsUnderTwoWeeks() {
        UUID productId = tenantA.productIdsByShape().get("new");

        LocalDate earliestSale = asOwner("""
                SELECT MIN(occurred_at)::date FROM stock_movements
                WHERE product_id = ? AND movement_type = 'sale'
                """, LocalDate.class, productId);

        assertThat(earliestSale).as("must have at least one sale to be meaningfully 'new'").isNotNull();
        assertThat(java.time.temporal.ChronoUnit.DAYS.between(earliestSale, LocalDate.now()))
                .as("readiness (once M7 exists) is keyed on demand_daily's own span — this keeps "
                        + "it well under the ADR's 42-day floor")
                .isLessThan(14);
    }

    @Test
    @DisplayName("the supplier used for restocking has 5+ receipts, exceeding the ADR's trust threshold")
    void supplierHasEnoughObservations() {

        Integer sampleSize = asOwner(
                "SELECT count(*) FROM supplier_lead_time_observations WHERE supplier_id = ?",
                Integer.class, tenantA.supplierId());
        assertThat(sampleSize)
                .as("docs/adr/forecasting.md §2: the observed average is trusted at 5+ samples")
                .isGreaterThanOrEqualTo(5);

        BigDecimal averageDays = asOwner(
                "SELECT avg(lead_time_days) FROM supplier_lead_time_observations WHERE supplier_id = ?",
                BigDecimal.class, tenantA.supplierId());
        assertThat(averageDays).as("a plausible lead time, not null and not zero")
                .isNotNull();
        assertThat(averageDays.signum()).isGreaterThan(0);
    }

    @Test
    @DisplayName("each major product has at least one purchase order, not just sales")
    void majorProductsHaveAPurchaseOrder() {
        for (String shape : List.of("steady", "intermittent", "stockout", "trending")) {
            UUID productId = tenantA.productIdsByShape().get(shape);
            Long poLineCount = asOwner("""
                    SELECT count(*) FROM purchase_order_items WHERE product_id = ?
                    """, Long.class, productId);
            assertThat(poLineCount).as("%s must have gone through a real PO, not only sales", shape)
                    .isGreaterThan(0L);
        }
    }

    @Test
    @DisplayName("two tenants seeded independently: tenant A's data never appears scoped to tenant B")
    void tenantsAreIsolated() {

        // Every product id minted for tenant A, confirmed absent from
        // tenant B's own product table — a real, if redundant, extra check
        // alongside TenantIsolationTest's sweep, exercised through a new
        // data-generation path (CLAUDE.md T2/T11 spirit: cheap insurance,
        // asserted on row counts, never assumed).
        for (UUID productId : tenantA.productIdsByShape().values()) {
            Long crossTenantHit = asOwner("""
                    SELECT count(*) FROM products WHERE id = ? AND tenant_id = ?
                    """, Long.class, productId, tenantB.tenantId());
            assertThat(crossTenantHit).as("tenant A's product must not be found under tenant B's id")
                    .isEqualTo(0L);
        }

        // And the positive twin (CLAUDE.md §10): each id genuinely belongs
        // to tenant A, so the zero above is a real isolation result and not
        // an unseeded fixture passing trivially.
        for (UUID productId : tenantA.productIdsByShape().values()) {
            Long ownTenantHit = asOwner("""
                    SELECT count(*) FROM products WHERE id = ? AND tenant_id = ?
                    """, Long.class, productId, tenantA.tenantId());
            assertThat(ownTenantHit).isEqualTo(1L);
        }

        assertThat(tenantA.tenantId()).isNotEqualTo(tenantB.tenantId());
        assertThat(tenantA.supplierId()).isNotEqualTo(tenantB.supplierId());
    }
}
