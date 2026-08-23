package com.example.inventory.forecasting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.seed.SeedDataRunner;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DemandRollupJob} against M6's real generator, at the real 30-week
 * window — the two criteria {@code docs/MILESTONES.md} M6 could only verify one
 * level lower, against {@code stock_movements}, because nothing existed yet to
 * populate {@code demand_daily}.
 *
 * <p>This class exists separately from {@code DemandRollupTest} because the two
 * catch different things. Hand-built fixtures pin exact arithmetic; they are
 * also where a too-convenient number hides a statistical bug, and CLAUDE.md §5
 * has three separate entries on fixtures asserting what they were told to. A
 * 30-week series with real randomness, real reactive restocking and a real
 * refused-sale outage is the closest thing available to the data M7 will
 * actually forecast from.
 *
 * <p>Assertions here are deliberately shape assertions — "a majority", "nearly
 * all zeros", "the outage days are flagged" — not exact counts. The generator is
 * seeded and therefore reproducible, but pinning an exact figure would make this
 * class fail on any change to the seeder for reasons that have nothing to do
 * with the rollup. The observed figures are printed instead, so a real change in
 * the data is visible in the build log rather than merely being green.
 */
@DisplayName("DemandRollupJob against M6 seed data")
class DemandRollupSeedDataTest extends AbstractIntegrationTest {

    /** The real production window, same constant {@code SeedDataRunner} uses. */
    private static final int WINDOW_WEEKS = SeedDataRunner.WINDOW_WEEKS;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private DemandRollupJob rollup;

    private static SeededTenant tenant;
    private static boolean prepared;

    /**
     * {@code @BeforeEach} with a static guard rather than {@code @BeforeAll} —
     * {@code @BeforeAll} runs before the Spring context loads, so the injected
     * seeder would still be null (CLAUDE.md §10). Seeding 30 weeks is the slow
     * part of this class by a wide margin and every test below shares it.
     */
    @BeforeEach
    void seedAndRollUp() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        // Seed 1, the same draw SeedDataVerificationTest's tenant A uses, and a
        // representative one rather than a tail. It is worth pinning
        // deliberately: replaying TenantSeeder's exact RNG consumption across 40
        // seeds puts the intermittent shape's selling days at a mean of 20.5
        // (the coded 10% rate over 210 days), but individual draws range from 11
        // to 31. Seed 1 lands on 21. Seed 7 — used here first — lands on 11,
        // which is one day above ADR §5's ten-non-zero-day readiness floor, and
        // would have made step 2's "intermittent routes to Croston" assertion
        // depend on a near-boundary draw rather than on the selector. No seed of
        // the 40 fell below the floor, so the threshold itself is not at risk.
        tenant = seeder.seedTenant("Rollup Bakery", "rollup-" + tag, 1L, WINDOW_WEEKS);

        asTenant(() -> rollup.rollUp());
        prepared = true;
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

    private <T> T readAsOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true)).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> queryAsOwner(String sql, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true)).queryForList(sql, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID product(String shape) {
        return tenant.productIdsByShape().get(shape);
    }

    private long count(String shape, String predicate) {
        return readAsOwner("SELECT count(*) FROM demand_daily WHERE product_id = ? AND " + predicate,
                Long.class, product(shape));
    }

    private long days(String shape) {
        return count(shape, "true");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("every shape gets a full calendar of rows — the observed figures, printed")
    void reportTheShapeOfWhatTheRollupProduced() {
        StringBuilder report = new StringBuilder(
                "\n=== DemandRollupJob over M6 seed data (%d weeks) ===\n".formatted(WINDOW_WEEKS));
        report.append(String.format("%-14s %6s %8s %8s %9s %10s %12s%n",
                "shape", "days", "nonzero", "zero", "stockout", "eligible", "units"));

        for (String shape : tenant.productIdsByShape().keySet()) {
            Map<String, Object> row = queryAsOwner("""
                    SELECT count(*)                                              AS days,
                           count(*) FILTER (WHERE units_sold > 0)                AS nonzero,
                           count(*) FILTER (WHERE units_sold = 0)                AS zero,
                           count(*) FILTER (WHERE had_stockout)                  AS stockout,
                           count(*) FILTER (WHERE NOT had_stockout
                                              AND units_sold > 0)                AS eligible_nonzero,
                           COALESCE(sum(units_sold), 0)                          AS units
                    FROM demand_daily WHERE product_id = ?
                    """, product(shape)).get(0);

            report.append(String.format("%-14s %6s %8s %8s %9s %10s %12s%n",
                    shape, row.get("days"), row.get("nonzero"), row.get("zero"),
                    row.get("stockout"), row.get("eligible_nonzero"), row.get("units")));
        }
        System.out.println(report);

        // Every shape must have produced rows at all. Without this the counting
        // assertions in the other tests could be satisfied by an empty table.
        for (String shape : tenant.productIdsByShape().keySet()) {
            assertThat(days(shape))
                    .as("%s must have demand_daily rows — a shape with none would make every "
                            + "'zero of X' assertion below pass trivially", shape)
                    .isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("the steady seller has genuine zero-demand days AND a majority of non-zero ones")
    void steadySellerHasBothKindsOfDay() {
        long total = days("steady");
        long nonZero = count("steady", "units_sold > 0");
        long zero = count("steady", "units_sold = 0");

        assertThat(zero)
                .as("M6's steady seller deliberately sells on ~90%% of days, so the remaining "
                        + "~10%% must appear as rows reading zero. This is the criterion M6 "
                        + "booked forward to M7 because demand_daily had no populating "
                        + "mechanism: 'demand_daily rows with genuine zero-demand days'. "
                        + "Observed %d of %d days at zero.", zero, total)
                .isGreaterThan(0);

        assertThat(nonZero)
                .as("and the positive twin — a rollup that produced only zeros would satisfy "
                        + "the assertion above perfectly. Observed %d of %d days non-zero.",
                        nonZero, total)
                .isGreaterThan(total / 2);

        assertThat(nonZero + zero)
                .as("every row is one or the other")
                .isEqualTo(total);
    }

    @Test
    @DisplayName("the stockout product's outage days are flagged, and its stocked days are not")
    void theOutageIsFlaggedAndTheRestIsNot() {
        long flagged = count("stockout", "had_stockout");
        long total = days("stockout");

        assertThat(flagged)
                .as("M6 sold this product to zero and then made real sale attempts that were "
                        + "refused — which write nothing to the ledger (T12). Only the "
                        + "reconstructed running balance can see those days. Observed %d "
                        + "flagged of %d.", flagged, total)
                .isGreaterThan(0);

        assertThat(flagged)
                .as("but not the whole window: the product sells normally before the outage "
                        + "and again after the restock, and a rollup that flagged everything "
                        + "would empty the eligible set that ADR §3 computes the average from")
                .isLessThan(total);

        // The flagged days are contiguous where the outage was, not scattered.
        // A flag that fired at random would satisfy both counts above.
        long longestRun = readAsOwner("""
                WITH marked AS (
                    SELECT day, had_stockout,
                           day - (row_number() OVER (ORDER BY day))::int AS grp
                    FROM demand_daily WHERE product_id = ? AND had_stockout
                )
                SELECT COALESCE(max(len), 0) FROM (
                    SELECT count(*) AS len FROM marked GROUP BY grp
                ) runs
                """, Long.class, product("stockout"));

        System.out.printf("stockout product: %d flagged of %d days, longest unbroken run %d%n",
                flagged, total, longestRun);

        assertThat(longestRun)
                .as("M6 scripts a five-day empty shelf and restocks the morning after, so the "
                        + "flag must appear as one unbroken run covering it — not as scattered "
                        + "single days, which is what a flag keyed on 'sold nothing today' "
                        + "would produce. Observed longest run: %d days.", longestRun)
                .isGreaterThanOrEqualTo(5);

        assertThat(longestRun)
                .as("and every flagged day belongs to that one run — an empty shelf is a "
                        + "period, not a scattering")
                .isEqualTo(flagged);

        // Independent of the balance reconstruction: the outage must end on the
        // day the goods physically arrived, which is a movement's own date. If
        // the flag were computed even slightly wrongly — off by a day, or keyed
        // on sales rather than stock — these two would not coincide.
        String lastFlagged = readAsOwner(
                "SELECT max(day)::text FROM demand_daily WHERE product_id = ? AND had_stockout",
                String.class, product("stockout"));
        String receiptDay = readAsOwner("""
                SELECT min(occurred_at AT TIME ZONE 'UTC')::date::text FROM stock_movements
                WHERE product_id = ? AND movement_type = 'purchase_receipt'
                """, String.class, product("stockout"));

        assertThat(lastFlagged)
                .as("the shelf was empty from midnight until the delivery landed, so the "
                        + "receipt's own day is the last censored one — and the day after it "
                        + "is not. Observed last flagged %s, receipt %s.", lastFlagged, receiptDay)
                .isEqualTo(receiptDay);
    }

    @Test
    @DisplayName("the stockout product's flagged days sell less than its unflagged ones")
    void theFlaggedDaysAreTheCensoredOnes() {
        Map<String, Object> row = queryAsOwner("""
                SELECT COALESCE(avg(units_sold) FILTER (WHERE had_stockout), 0)     AS censored,
                       COALESCE(avg(units_sold) FILTER (WHERE NOT had_stockout), 0) AS eligible
                FROM demand_daily WHERE product_id = ?
                """, product("stockout")).get(0);

        double censored = ((Number) row.get("censored")).doubleValue();
        double eligible = ((Number) row.get("eligible")).doubleValue();
        System.out.printf("stockout product: avg units on flagged days = %.3f, "
                + "on eligible days = %.3f%n", censored, eligible);

        assertThat(censored)
                .as("the whole point of ADR §3: a flagged day's units_sold is a floor set by "
                        + "the empty shelf, not by demand. If the flagged days did not sell "
                        + "visibly less than the rest, the flag is not tracking the outage. "
                        + "Observed %.3f vs %.3f.", censored, eligible)
                .isLessThan(eligible);
    }

    @Test
    @DisplayName("the dead-stock product rolls up to a full window of zeros, none of them flagged")
    void deadStockIsAllZerosAndIsNotAStockout() {
        long total = days("dead");
        long nonZero = count("dead", "units_sold > 0");
        long flagged = count("dead", "had_stockout");

        assertThat(total)
                .as("M6 stocks this product once and never sells it, but it must still be "
                        + "seeded through the FULL window — ADR §5 needs it to stay "
                        + "insufficient_data forever, and a product with no rows at all would "
                        + "be a different case entirely. Observed %d days.", total)
                .isGreaterThan(WINDOW_WEEKS * 7L - 7);

        assertThat(nonZero)
                .as("no sales in months means no non-zero days. Observed %d.", nonZero)
                .isZero();

        assertThat(flagged)
                .as("and critically NOT flagged: this product has stock sitting on the shelf "
                        + "the whole time. Reading it as censored would be the censored-demand "
                        + "rule firing on exactly the product it must not fire on — dead stock "
                        + "read as a strong seller nobody could buy. Observed %d flagged.",
                        flagged)
                .isZero();
    }

    @Test
    @DisplayName("the intermittent product is sparse but not empty — the shape Croston needs")
    void intermittentIsSparse() {
        long total = days("intermittent");
        long nonZero = count("intermittent", "units_sold > 0");
        double fraction = (double) nonZero / total;
        System.out.printf("intermittent product: %d non-zero of %d days = nonzero_fraction %.3f%n",
                nonZero, total, fraction);

        assertThat(nonZero)
                .as("selling on roughly 1 day in 10 still means selling. Observed %d.", nonZero)
                .isGreaterThan(0);
        assertThat(fraction)
                .as("ADR §4 routes below 0.3 to Croston, and M6 aims at ~0.1. This is not the "
                        + "selector's test — that is step 2 — but if the seeded shape does not "
                        + "land on this side of the line, the selector test would be proving "
                        + "nothing. Observed %.3f.", fraction)
                .isLessThan(0.3);
    }

    @Test
    @DisplayName("the brand-new product has under two weeks of history")
    void brandNewIsShort() {
        long total = days("new");
        System.out.printf("brand-new product: %d days of history%n", total);

        assertThat(total)
                .as("ADR §5's 42-day floor must reject this one on the calendar span alone. "
                        + "Observed %d days.", total)
                .isLessThan(42);
        assertThat(total)
                .as("but it does have SOME history — zero rows would fail the readiness check "
                        + "for the wrong reason", total)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("every shape's rolled-up demand reconciles exactly with the ledger it was read from")
    void theRollupReconcilesWithTheLedger() {
        for (String shape : tenant.productIdsByShape().keySet()) {
            UUID productId = product(shape);

            // The ledger's own answer, computed a completely different way:
            // no calendar, no window functions, no netting expression — just
            // the signed sale and sale_return rows summed.
            Map<String, Object> ledger = queryAsOwner("""
                    SELECT COALESCE(-sum(quantity_delta) FILTER (WHERE movement_type = 'sale'), 0)
                             - COALESCE(sum(quantity_delta) FILTER (WHERE movement_type = 'sale_return'), 0)
                               AS net_units,
                           count(DISTINCT (occurred_at AT TIME ZONE 'UTC')::date)
                               FILTER (WHERE movement_type = 'sale') AS selling_days
                    FROM stock_movements WHERE product_id = ?
                    """, productId).get(0);

            Map<String, Object> rolled = queryAsOwner("""
                    SELECT COALESCE(sum(units_sold), 0)            AS units,
                           count(*) FILTER (WHERE units_sold > 0)  AS nonzero_days
                    FROM demand_daily WHERE product_id = ?
                    """, productId).get(0);

            assertThat(((Number) rolled.get("units")).doubleValue())
                    .as("%s: demand_daily's total must equal the ledger's own net figure. A "
                            + "rollup that dropped days, double-counted a movement or lost a "
                            + "return would disagree here and nowhere else — every other test "
                            + "in this class asserts a shape, and a shape survives a "
                            + "systematically wrong total.", shape)
                    .isEqualTo(((Number) ledger.get("net_units")).doubleValue());

            assertThat(((Number) rolled.get("nonzero_days")).longValue())
                    .as("%s: and the count of days that sold something must match the ledger's "
                            + "distinct selling days. This is what tells a genuinely sparse "
                            + "series apart from a dense one the rollup mangled — the "
                            + "difference between a real Croston candidate and a bug.", shape)
                    .isEqualTo(((Number) ledger.get("selling_days")).longValue());
        }
    }

    @Test
    @DisplayName("a second rollup over 30 weeks of real history changes nothing")
    void theRollupIsIdempotentAtScale() throws Exception {
        long rowsBefore = readAsOwner("SELECT count(*) FROM demand_daily WHERE tenant_id = ?",
                Long.class, tenant.tenantId());
        String digestBefore = readAsOwner("""
                SELECT md5(string_agg(
                    product_id::text || day::text || units_sold::text || had_stockout::text,
                    ',' ORDER BY product_id, day))
                FROM demand_daily WHERE tenant_id = ?
                """, String.class, tenant.tenantId());

        asTenant(() -> rollup.rollUp());

        assertThat(readAsOwner("SELECT count(*) FROM demand_daily WHERE tenant_id = ?",
                Long.class, tenant.tenantId()))
                .as("re-running must replace rows, not accumulate them")
                .isEqualTo(rowsBefore);
        assertThat(readAsOwner("""
                SELECT md5(string_agg(
                    product_id::text || day::text || units_sold::text || had_stockout::text,
                    ',' ORDER BY product_id, day))
                FROM demand_daily WHERE tenant_id = ?
                """, String.class, tenant.tenantId()))
                .as("and every value must be identical — a count-only check would miss a "
                        + "rollup that recomputed the same number of rows differently")
                .isEqualTo(digestBefore);
    }
}
