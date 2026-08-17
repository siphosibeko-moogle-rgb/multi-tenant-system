package com.example.inventory.sales;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.example.inventory.inventory.InsufficientStockException;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.sales.SalesDtos.SaleLineRequest;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code POST /sales}, pulled forward from M4 for M3's Android slice.
 *
 * <p>Three properties matter here and each has its own test: a sale is all or
 * nothing, a replay records once, and the business day is the tenant's rather
 * than the server's.
 */
@DisplayName("Recording a sale")
class SaleTest extends AbstractIntegrationTest {

    /**
     * Deliberately not UTC and not a whole number of hours away from it in the
     * usual test-fixture sense — Johannesburg is UTC+2, enough that a late-night
     * sale falls on the previous UTC day.
     */
    private static final String TENANT_TIMEZONE = "Africa/Johannesburg";

    @Autowired
    private SaleService sales;

    @Autowired
    private StockLedgerService ledger;

    private static UUID tenantId;
    private static UUID locationId;
    private static UUID productA;
    private static UUID productB;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        locationId = UUID.randomUUID();
        productA = UUID.randomUUID();
        productB = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    INSERT INTO tenants (id, slug, name, timezone)
                    VALUES ('%s', 'sale-%s', 'Corner Shop', '%s')
                    """.formatted(tenantId, tenantId.toString().substring(0, 8), TENANT_TIMEZONE));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Till', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price, tax_rate)
                    VALUES ('%s', '%s', 'SKU-A', 'Bread', 6.00, 12.50, 0.15)
                    """.formatted(productA, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price, tax_rate)
                    VALUES ('%s', '%s', 'SKU-B', 'Milk', 8.00, 15.00, 0.15)
                    """.formatted(productB, tenantId));
        }
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

    private <T> T readAsOwner(String sql, Class<T> type, Object... args) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true)).queryForObject(sql, type, args);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stock(UUID productId, String quantity) throws Exception {
        asTenant(() -> ledger.post(new MovementRequest(productId, locationId, "purchase_receipt",
                new BigDecimal(quantity), new BigDecimal("5.00"), null, null, "restock", null)));
    }

    private BigDecimal balance(UUID productId) {
        return readAsOwner("""
                SELECT COALESCE((SELECT quantity_on_hand FROM product_stock
                                  WHERE product_id = ? AND location_id = ?), 0)
                """, BigDecimal.class, productId, locationId);
    }

    // ------------------------------------------------------------------
    // All or nothing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("records the sale, its lines, and one movement per line")
    void recordsASale() throws Exception {
        stock(productA, "10");
        stock(productB, "10");
        BigDecimal beforeA = balance(productA);
        BigDecimal beforeB = balance(productB);

        var recorded = asTenant(() -> sales.record(new SaleWriteRequest(
                List.of(new SaleLineRequest(productA, new BigDecimal("2"), null, null),
                        new SaleLineRequest(productB, new BigDecimal("1"), null, null)),
                null, null, "Walk-in", null, "cash", null, null)));

        assertThat(recorded.replayed()).isFalse();
        assertThat(recorded.sale().itemCount()).isEqualTo(2);

        // 2 x 12.50 + 1 x 15.00 = 40.00
        assertThat(recorded.sale().subtotalAmount()).isEqualByComparingTo("40.00");
        assertThat(recorded.sale().totalAmount())
                .as("subtotal plus 15% tax")
                .isEqualByComparingTo("46.00");

        assertThat(balance(productA)).isEqualByComparingTo(beforeA.subtract(new BigDecimal("2")));
        assertThat(balance(productB)).isEqualByComparingTo(beforeB.subtract(BigDecimal.ONE));

        assertThat(readAsOwner("""
                SELECT count(*) FROM stock_movements
                WHERE reference_type = 'sale' AND reference_id = ?
                """, Long.class, recorded.sale().id()))
                .as("one movement per line, tied to the sale by reference_id")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("a line that oversells rejects the whole sale and persists nothing")
    void aLineThatOversellsPersistsNothing() throws Exception {
        stock(productA, "10");
        BigDecimal beforeA = balance(productA);
        BigDecimal beforeB = balance(productB);
        long salesBefore = readAsOwner("SELECT count(*) FROM sales", Long.class);
        long itemsBefore = readAsOwner("SELECT count(*) FROM sale_items", Long.class);
        long movementsBefore = readAsOwner("SELECT count(*) FROM stock_movements", Long.class);

        // Line 1 is fine. Line 2 asks for stock that is not there. The whole sale
        // must fail — this is the M4 criterion "a sale where line 3 oversells
        // persists nothing", tested now because the endpoint exists now.
        assertThatThrownBy(() -> asTenant(() -> sales.record(new SaleWriteRequest(
                List.of(new SaleLineRequest(productA, BigDecimal.ONE, null, null),
                        new SaleLineRequest(productB, new BigDecimal("999"), null, null)),
                null, null, null, null, null, null, null))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(readAsOwner("SELECT count(*) FROM sales", Long.class))
                .as("no sale row may survive")
                .isEqualTo(salesBefore);
        assertThat(readAsOwner("SELECT count(*) FROM sale_items", Long.class))
                .as("and no line — including the first, which was perfectly valid")
                .isEqualTo(itemsBefore);
        assertThat(readAsOwner("SELECT count(*) FROM stock_movements", Long.class))
                .as("and no movement, or the ledger records stock leaving for a sale that "
                        + "does not exist — and T4 means there is no update to fix it")
                .isEqualTo(movementsBefore);

        assertThat(balance(productA))
                .as("the good line must not have moved stock")
                .isEqualByComparingTo(beforeA);
        assertThat(balance(productB)).isEqualByComparingTo(beforeB);
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("clientRequestId")
    class Idempotency {

        @Test
        @DisplayName("a replay returns the original sale and moves stock once")
        void replayReturnsTheOriginalAndMovesStockOnce() throws Exception {
            stock(productA, "10");
            BigDecimal before = balance(productA);
            UUID clientRequestId = UUID.randomUUID();

            SaleWriteRequest request = new SaleWriteRequest(
                    List.of(new SaleLineRequest(productA, new BigDecimal("3"), null, null)),
                    clientRequestId, null, null, null, "card", null, null);

            var first = asTenant(() -> sales.record(request));
            assertThat(first.replayed()).as("the first call records").isFalse();

            // The same request again, exactly as a retry over a flaky link would
            // arrive: same clientRequestId, because the client generated it when
            // the user tapped save rather than when the request was sent.
            var replay = asTenant(() -> sales.record(request));

            assertThat(replay.replayed())
                    .as("the second call must be recognised as a replay, so the controller "
                            + "answers 200 rather than 201")
                    .isTrue();
            assertThat(replay.sale().id())
                    .as("and must return the ORIGINAL sale, not a new one")
                    .isEqualTo(first.sale().id());
            assertThat(replay.sale().saleNumber()).isEqualTo(first.sale().saleNumber());

            assertThat(balance(productA))
                    .as("stock moves ONCE. This is the assertion that matters: a retry that "
                            + "sold three units twice would be invisible until someone counted "
                            + "the shelf.")
                    .isEqualByComparingTo(before.subtract(new BigDecimal("3")));

            assertThat(readAsOwner("SELECT count(*) FROM sales WHERE client_request_id = ?",
                    Long.class, clientRequestId))
                    .as("exactly one sale row carries this key")
                    .isEqualTo(1L);
            assertThat(readAsOwner("""
                    SELECT count(*) FROM stock_movements
                    WHERE reference_type = 'sale' AND reference_id = ?
                    """, Long.class, first.sale().id()))
                    .as("and exactly one movement")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("two sales without a clientRequestId are two sales")
        void withoutAKeyEachCallRecords() throws Exception {
            stock(productA, "10");

            SaleWriteRequest request = new SaleWriteRequest(
                    List.of(new SaleLineRequest(productA, BigDecimal.ONE, null, null)),
                    null, null, null, null, null, null, null);

            var first = asTenant(() -> sales.record(request));
            var second = asTenant(() -> sales.record(request));

            // Idempotency is opt-in. Without a key there is nothing to match on,
            // and two taps genuinely are two sales — asserted so the absence of a
            // key is never quietly treated as a replay.
            assertThat(second.replayed()).isFalse();
            assertThat(second.sale().id()).isNotEqualTo(first.sale().id());
        }
    }

    // ------------------------------------------------------------------
    // The business day
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a late-night sale lands on the tenant's business day, not the server's")
    void aLateNightSaleLandsOnTheTenantsBusinessDay() throws Exception {
        stock(productA, "10");

        // 23:30 on 17 August, Johannesburg time (UTC+2). In UTC that is 21:30 on
        // the SAME day — so pick a time where the two genuinely disagree: 00:30
        // local on the 18th is 22:30 UTC on the 17th.
        OffsetDateTime soldAtLocal = OffsetDateTime.of(
                2026, 8, 18, 0, 30, 0, 0, ZoneOffset.ofHours(2));

        var recorded = asTenant(() -> sales.record(new SaleWriteRequest(
                List.of(new SaleLineRequest(productA, BigDecimal.ONE, null, null)),
                null, null, null, null, null, null, soldAtLocal)));

        UUID saleId = recorded.sale().id();

        // The business day, computed the way a tenant-timezone rollup must: shift
        // the stored instant into the tenant's zone before taking the date.
        String businessDay = readAsOwner("""
                SELECT (s.sold_at AT TIME ZONE t.timezone)::date::text
                FROM sales s JOIN tenants t ON t.id = s.tenant_id
                WHERE s.id = ?
                """, String.class, saleId);

        assertThat(businessDay)
                .as("the sale happened at 00:30 on the 18th as far as the shop is concerned")
                .isEqualTo("2026-08-18");

        // And the naive version is wrong, which is the entire point. If these two
        // agreed, this test would pass against a server that ignored the tenant's
        // timezone completely.
        String naiveUtcDay = readAsOwner(
                "SELECT (sold_at AT TIME ZONE 'UTC')::date::text FROM sales WHERE id = ?",
                String.class, saleId);

        assertThat(naiveUtcDay)
                .as("in UTC the same instant is still the 17th — so a rollup that used the "
                        + "server's clock would put this sale in yesterday's takings")
                .isEqualTo("2026-08-17");
        assertThat(businessDay).isNotEqualTo(naiveUtcDay);

        // The movement carries the same business time, so M7's demand_daily
        // rollup sees the sale on the same day the sale does.
        String movementDay = readAsOwner("""
                SELECT (m.occurred_at AT TIME ZONE t.timezone)::date::text
                FROM stock_movements m JOIN tenants t ON t.id = m.tenant_id
                WHERE m.reference_type = 'sale' AND m.reference_id = ?
                """, String.class, saleId);

        assertThat(movementDay)
                .as("the ledger row must agree with the sale about which day it was, or the "
                        + "demand history and the sales history disagree")
                .isEqualTo(businessDay);
    }

    @Test
    @DisplayName("soldAt defaults to now when the client omits it")
    void soldAtDefaultsToNow() throws Exception {
        stock(productA, "10");

        var recorded = asTenant(() -> sales.record(new SaleWriteRequest(
                List.of(new SaleLineRequest(productA, BigDecimal.ONE, null, null)),
                null, null, null, null, null, null, null)));

        assertThat(recorded.sale().soldAt())
                .as("a client that does not care about business time should not have to send one")
                .isNotNull()
                .isAfter(OffsetDateTime.now().minusMinutes(5));
    }
}
