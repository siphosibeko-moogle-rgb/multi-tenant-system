package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.sales.SaleService;
import com.example.inventory.sales.SalesDtos.ReturnLine;
import com.example.inventory.sales.SalesDtos.ReturnRequest;
import com.example.inventory.sales.SalesDtos.SaleLineRequest;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DemandRollupJob}'s mechanics, on fixtures small enough that every
 * expected number can be written down by hand.
 *
 * <p>The realistic-series checks live in {@code DemandRollupSeedDataTest},
 * against M6's real 30-week generator. Both exist on purpose: a hand-built
 * fixture is the only way to pin an exact arithmetic answer, and a hand-built
 * fixture is also the easiest place in this milestone to hide a statistical bug
 * behind convenient numbers.
 *
 * <h2>Each behaviour has its own separately-reachable fixture</h2>
 *
 * <p>CLAUDE.md §5: a test can pass while exercising the wrong guard. Netting,
 * the zero floor, the stockout flag and the quiet-day contrast are four
 * different rules that could each produce "a low number on this day", so each
 * gets a product of its own where only the rule under test can explain the
 * result.
 */
@DisplayName("DemandRollupJob")
class DemandRollupTest extends AbstractIntegrationTest {

    /**
     * UTC deliberately, unlike {@code SaleTest}'s Johannesburg fixture. A return
     * posts its movement at {@code now()} and cannot be backdated, so the
     * netting test needs the sale and the return to land on the same tenant day;
     * an offset zone would add a midnight boundary this test has no interest in.
     * The tenant-timezone rule itself is {@code SaleTest}'s to prove.
     */
    private static final String TENANT_TIMEZONE = "UTC";

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private SaleService sales;

    @Autowired
    private StockLedgerService ledger;

    private static UUID tenantId;
    private static UUID otherTenantId;
    private static UUID locationId;
    private static UUID otherLocationId;

    /** Sold 5, two handed back and restocked, all on the same day. Nets to 3. */
    private static UUID netting;

    /** Sold on an old day, returned today. Today floors at 0, not -2. */
    private static UUID lateReturn;

    /** Stocked, sold down to exactly zero, left empty, restocked. */
    private static UUID outage;

    /** Stocked generously and sells on few days. Quiet, never empty. */
    private static UUID quiet;

    private static UUID otherTenantProduct;

    private static LocalDate today;
    private static boolean seeded;

    @BeforeEach
    void seed() throws Exception {
        if (seeded) {
            return;
        }
        today = LocalDate.now(ZoneOffset.UTC);

        tenantId = newTenantId();
        otherTenantId = newTenantId();
        locationId = UUID.randomUUID();
        otherLocationId = UUID.randomUUID();
        netting = UUID.randomUUID();
        lateReturn = UUID.randomUUID();
        outage = UUID.randomUUID();
        quiet = UUID.randomUUID();
        otherTenantProduct = UUID.randomUUID();

        createTenant(tenantId, "roll", locationId,
                List.of(netting, lateReturn, outage, quiet));
        createTenant(otherTenantId, "rollb", otherLocationId, List.of(otherTenantProduct));

        asTenant(tenantId, () -> {
            // --- netting: one sale of 5, two returned and restocked, today ----
            stock(netting, locationId, today.minusDays(3), 50);
            UUID saleId = sell(netting, locationId, today, 5);
            sales.returnSale(saleId, new ReturnRequest(
                    List.of(new ReturnLine(netting, new BigDecimal(2))), "changed mind", true));

            // --- lateReturn: sold on an old day, returned today ---------------
            stock(lateReturn, locationId, today.minusDays(10), 50);
            UUID oldSale = sell(lateReturn, locationId, today.minusDays(6), 4);
            sales.returnSale(oldSale, new ReturnRequest(
                    List.of(new ReturnLine(lateReturn, new BigDecimal(2))), "late", true));

            // --- outage: down to exactly zero, empty for three days, restocked -
            // The last unit leaves at midday, so the shelf is empty for the rest
            // of that day too — the within-day half of the flag, distinct from
            // the carried-in-empty half the following days exercise.
            stock(outage, locationId, today.minusDays(9), 10);
            sell(outage, locationId, today.minusDays(8), 10);
            // days -7, -6, -5: no movements at all. A refused sale writes
            // nothing (T12), so an outage is an ABSENCE of rows — which is
            // exactly what a movements-only GROUP BY cannot see.
            stock(outage, locationId, today.minusDays(4), 20);

            // --- quiet: always stocked, sells on two days out of ten ----------
            stock(quiet, locationId, today.minusDays(9), 100);
            sell(quiet, locationId, today.minusDays(7), 1);
            sell(quiet, locationId, today.minusDays(2), 1);
            return null;
        });

        asTenant(otherTenantId, () -> {
            stock(otherTenantProduct, otherLocationId, today.minusDays(5), 30);
            sell(otherTenantProduct, otherLocationId, today.minusDays(3), 7);
            return null;
        });

        seeded = true;
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private void createTenant(UUID id, String prefix, UUID location, List<UUID> products)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    INSERT INTO tenants (id, slug, name, timezone)
                    VALUES ('%s', '%s-%s', 'Rollup Fixture', '%s')
                    """.formatted(id, prefix, id.toString().substring(0, 8), TENANT_TIMEZONE));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Shelf', true)".formatted(location, id));
            int n = 0;
            for (UUID product : products) {
                stmt.execute("""
                        INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price, tax_rate)
                        VALUES ('%s', '%s', 'ROLL-%s-%d', 'Fixture Product %d', 4.00, 10.00, 0)
                        """.formatted(product, id, id.toString().substring(0, 8), n, n));
                n++;
            }
        }
    }

    private <T> T asTenant(UUID id, Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(id, null, null));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private void stock(UUID product, UUID location, LocalDate day, int quantity) {
        ledger.post(new MovementRequest(product, location, "adjustment",
                new BigDecimal(quantity), new BigDecimal("4.00"), null, null, "fixture stock",
                day.atTime(8, 0).atOffset(ZoneOffset.UTC)));
    }

    private UUID sell(UUID product, UUID location, LocalDate day, int quantity) {
        return sales.record(new SaleWriteRequest(
                List.of(new SaleLineRequest(product, new BigDecimal(quantity), null, null)),
                null, location, null, null, null, null,
                day.atTime(12, 0).atOffset(ZoneOffset.UTC))).sale().id();
    }

    /**
     * Reads through the OWNER connection, bypassing RLS, so an assertion about
     * tenant B's rows cannot be satisfied by simply not being able to see them.
     */
    private <T> T readAsOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true)).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private BigDecimal unitsOn(UUID product, LocalDate day) {
        return readAsOwner("SELECT units_sold FROM demand_daily "
                + "WHERE product_id = ? AND day = ?", BigDecimal.class, product, day);
    }

    private Boolean stockoutOn(UUID product, LocalDate day) {
        return readAsOwner("SELECT had_stockout FROM demand_daily "
                + "WHERE product_id = ? AND day = ?", Boolean.class, product, day);
    }

    private long rowCount(UUID product) {
        return readAsOwner("SELECT count(*) FROM demand_daily WHERE product_id = ?",
                Long.class, product);
    }

    private void runRollupFor(UUID tenant) throws Exception {
        asTenant(tenant, () -> rollup.rollUp(today));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("net demand")
    class NetDemand {

        @Test
        @DisplayName("a sale of 5 with 2 restocked back nets to 3 for that day, not 5")
        void restockedReturnsReduceTheDay() throws Exception {
            runRollupFor(tenantId);

            // Presence before value (CLAUDE.md §5): an absent row and a row
            // reading zero are different bugs, and a null would otherwise be
            // read as "no demand" by any caller that unwraps it carelessly.
            assertThat(rowCount(netting))
                    .as("the netting product must have a row for today at all")
                    .isGreaterThan(0);
            assertThat(unitsOn(netting, today))
                    .as("5 sold, 2 handed back and put on the shelf — 3 units of real demand")
                    .isEqualByComparingTo("3");
        }

        @Test
        @DisplayName("a sale counts once in sales_count regardless of the return against it")
        void salesCountIsTransactionsNotUnits() throws Exception {
            runRollupFor(tenantId);

            Integer count = readAsOwner("SELECT sales_count FROM demand_daily "
                    + "WHERE product_id = ? AND day = ?", Integer.class, netting, today);
            assertThat(count)
                    .as("one sale transaction touched this product today")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a return lands on the day the goods came back, not the day of the sale")
        void returnsAreNotBackdated() throws Exception {
            runRollupFor(tenantId);

            assertThat(unitsOn(lateReturn, today.minusDays(6)))
                    .as("the original sale day keeps its full 4 — the goods were genuinely "
                            + "demanded and taken away that day")
                    .isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("a return exceeding the day's sales floors at zero rather than going negative")
        void netDemandIsFlooredAtZero() throws Exception {
            runRollupFor(tenantId);

            assertThat(rowCount(lateReturn))
                    .as("today must have a row even though nothing was sold today")
                    .isGreaterThan(0);
            assertThat(unitsOn(lateReturn, today))
                    .as("2 units came back today against a sale six days ago; demand is not -2")
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("zero-demand days")
    class ZeroDemandDays {

        @Test
        @DisplayName("every calendar day from the first movement gets a row, not only the days that sold")
        void theCalendarIsGeneratedNotDerivedFromMovements() throws Exception {
            runRollupFor(tenantId);

            long daysWithASale = readAsOwner("""
                    SELECT count(DISTINCT (occurred_at AT TIME ZONE 'UTC')::date)
                    FROM stock_movements
                    WHERE product_id = ? AND movement_type = 'sale'
                    """, Long.class, quiet);
            long rows = rowCount(quiet);

            assertThat(daysWithASale)
                    .as("fixture check: this product deliberately sells on only two days")
                    .isEqualTo(2);
            assertThat(rows)
                    .as("first movement was 9 days before today, inclusive of both ends — "
                            + "a GROUP BY over movements would have produced %d rows instead",
                            daysWithASale)
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("a day with stock on the shelf and no sales reads zero, and is not flagged")
        void aQuietDayIsZeroAndNotAStockout() throws Exception {
            runRollupFor(tenantId);

            LocalDate quietDay = today.minusDays(5);
            assertThat(unitsOn(quiet, quietDay))
                    .as("nobody bought one that day")
                    .isEqualByComparingTo("0");
            assertThat(stockoutOn(quiet, quietDay))
                    .as("but there were ~98 units on the shelf — this is real evidence of low "
                            + "demand, and excluding it (ADR §3) would be the censored-demand bug "
                            + "in reverse")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("had_stockout")
    class HadStockout {

        @Test
        @DisplayName("the day the last unit sells is flagged — the shelf was empty from midday on")
        void theDayStockHitsZeroIsFlagged() throws Exception {
            runRollupFor(tenantId);

            LocalDate emptied = today.minusDays(8);
            assertThat(unitsOn(outage, emptied))
                    .as("all 10 sold that day")
                    .isEqualByComparingTo("10");
            assertThat(stockoutOn(outage, emptied))
                    .as("10 is this product's best day and it is still censored: the shelf hit "
                            + "zero at midday, so the number is a floor, not a count")
                    .isTrue();
        }

        @Test
        @DisplayName("days with no movements at all are flagged from the carried-in balance")
        void anOutageIsVisibleDespiteHavingNoRowsToGroupBy() throws Exception {
            runRollupFor(tenantId);

            for (int back = 7; back >= 5; back--) {
                LocalDate day = today.minusDays(back);
                assertThat(stockoutOn(outage, day))
                        .as("%s had no stock_movements whatsoever — a refused sale writes "
                                + "nothing (T12) — so only the reconstructed running balance "
                                + "can tell this from a quiet day", day)
                        .isTrue();
                assertThat(unitsOn(outage, day))
                        .as("%s sold nothing because nothing was there to sell", day)
                        .isEqualByComparingTo("0");
            }
        }

        @Test
        @DisplayName("the restock morning is flagged too — the shelf was empty until the delivery")
        void theDayStockReturnsIsStillPartlyAStockout() throws Exception {
            runRollupFor(tenantId);

            LocalDate restocked = today.minusDays(4);
            assertThat(stockoutOn(outage, restocked))
                    .as("stock arrived at 08:00; the shelf was empty from midnight until then, "
                            + "so that day's demand is censored too")
                    .isTrue();
        }

        @Test
        @DisplayName("a day fully covered by stock is not flagged, and neither is the first day")
        void aStockedDayIsNotFlagged() throws Exception {
            runRollupFor(tenantId);

            assertThat(stockoutOn(outage, today.minusDays(9)))
                    .as("the first day: the balance before the opening movement is zero, but "
                            + "there was no shelf to be empty yet — flagging it would make every "
                            + "product's first day ineligible for no reason")
                    .isFalse();
            assertThat(stockoutOn(outage, today.minusDays(3)))
                    .as("20 units on the shelf all day")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("re-running")
    class ReRunning {

        @Test
        @DisplayName("a second run over the same range changes nothing")
        void theRollupIsIdempotent() throws Exception {
            runRollupFor(tenantId);
            long rowsAfterFirst = readAsOwner(
                    "SELECT count(*) FROM demand_daily WHERE tenant_id = ?", Long.class, tenantId);
            BigDecimal totalAfterFirst = readAsOwner(
                    "SELECT COALESCE(SUM(units_sold), 0) FROM demand_daily WHERE tenant_id = ?",
                    BigDecimal.class, tenantId);

            runRollupFor(tenantId);

            assertThat(readAsOwner("SELECT count(*) FROM demand_daily WHERE tenant_id = ?",
                    Long.class, tenantId))
                    .as("ON CONFLICT DO UPDATE, not INSERT — a second run must not double the table")
                    .isEqualTo(rowsAfterFirst);
            assertThat(readAsOwner(
                    "SELECT COALESCE(SUM(units_sold), 0) FROM demand_daily WHERE tenant_id = ?",
                    BigDecimal.class, tenantId))
                    .as("and must not double the demand either")
                    .isEqualByComparingTo(totalAfterFirst);
        }
    }

    @Nested
    @DisplayName("tenancy")
    class Tenancy {

        @Test
        @DisplayName("a rollup writes rows for the bound tenant only")
        void theRollupIsScopedToTheBoundTenant() throws Exception {
            runRollupFor(tenantId);

            // Read as the owner: RLS would otherwise satisfy this assertion by
            // hiding the rows rather than by their not existing (CLAUDE.md §10).
            long otherTenantRows = readAsOwner(
                    "SELECT count(*) FROM demand_daily WHERE tenant_id = ?",
                    Long.class, otherTenantId);
            assertThat(otherTenantRows)
                    .as("tenant A's rollup must not have rolled up tenant B's ledger")
                    .isZero();

            runRollupFor(otherTenantId);

            assertThat(readAsOwner("SELECT count(*) FROM demand_daily WHERE tenant_id = ?",
                    Long.class, otherTenantId))
                    .as("and B's own rollup does produce B's rows — without this the assertion "
                            + "above passes for a job that simply never writes anything")
                    .isGreaterThan(0);
            assertThat(readAsOwner("""
                    SELECT count(*) FROM demand_daily
                    WHERE tenant_id = ? AND product_id = ?
                    """, Long.class, otherTenantId, netting))
                    .as("and B's rows carry none of A's products")
                    .isZero();
        }

        @Test
        @DisplayName("with no tenant bound the job refuses rather than rolling up nothing quietly")
        void anUnboundRollupIsRefused() {
            TenantContext.clear();
            assertThatThrownBy(() -> rollup.rollUp(today))
                    .as("unbound, RLS would make this a successful no-op — which reads as "
                            + "'the rollup ran' in any log that only checks for an exception")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bound tenant");
        }
    }
}
