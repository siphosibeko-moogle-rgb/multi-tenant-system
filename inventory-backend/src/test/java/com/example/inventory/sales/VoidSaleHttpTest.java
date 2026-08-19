package com.example.inventory.sales;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Voiding a sale, over HTTP.
 *
 * <p>Every criterion M4 states about the void response is asserted through a
 * real request, per CLAUDE.md §5. A service-level test would stop exactly where
 * the interesting part begins — the status code, the problem type, and whether
 * the sale row survives are all decided at or after the controller boundary.
 */
@DisplayName("Voiding a sale")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VoidSaleHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
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
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        productA = UUID.randomUUID();
        productB = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'void-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            // Two products with DIFFERENT prices and quantities throughout, so a
            // test cannot pass by confusing one for the other.
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-A', 'Bread', 12.50)
                    """.formatted(productA, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-B', 'Milk', 7.25)
                    """.formatted(productB, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
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

    private void stock(UUID productId, int quantity) {
        var r = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"opening"}
                """.formatted(productId, quantity), token());
        assertThat(r.status()).as("fixture stocking must succeed").isEqualTo(201);
    }

    /** Records a sale of one product and returns its id. */
    private UUID sell(UUID productId, int quantity) {
        var r = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":%d}]}
                """.formatted(productId, quantity), token());
        assertThat(r.status()).as("fixture sale must succeed: %s", r.body()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asString());
    }

    private BigDecimal onHand(UUID productId) {
        return asOwner("SELECT COALESCE(SUM(quantity_on_hand), 0) FROM product_stock "
                + "WHERE product_id = ?", BigDecimal.class, productId);
    }

    private long movementCount(UUID productId) {
        return asOwner("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                Long.class, productId);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("returns exactly the sold quantity, and the sale row survives as voided")
    void voidReturnsTheStockAndKeepsTheSale() {
        stock(productA, 20);
        BigDecimal beforeSale = onHand(productA);
        UUID saleId = sell(productA, 6);

        // Relative, not absolute. These tests share a seeded tenant and JUnit
        // gives no order guarantee, so a fixed "14" asserts the order tests
        // happened to run in rather than the behaviour under test.
        assertThat(onHand(productA)).as("the sale must have moved stock out")
                .isEqualByComparingTo(beforeSale.subtract(new BigDecimal("6")));

        var response = http().postWithToken("/sales/" + saleId + "/void",
                "{\"reason\":\"customer changed their mind\"}", token());

        // 200, per the contract — nothing addressable is created.
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("voided");

        // EXACTLY the sold quantity: back where it started, not 6 more and not
        // 6 short.
        assertThat(onHand(productA))
                .as("a void returns what was sold — no more, no less")
                .isEqualByComparingTo(beforeSale);

        // T4: the sale is never deleted. A void is a compensating record, not
        // an erasure, so the history of what happened survives.
        assertThat(asOwner("SELECT count(*) FROM sales WHERE id = ?", Long.class, saleId))
                .as("the original sale row must still exist").isEqualTo(1L);
        assertThat(asOwner("SELECT status::text FROM sales WHERE id = ?", String.class, saleId))
                .isEqualTo("voided");

        // The compensating movement is a sale_return, posted through the ledger
        // (T5) and referencing the sale it reverses.
        assertThat(asOwner("""
                SELECT count(*) FROM stock_movements
                WHERE product_id = ? AND movement_type = 'sale_return'
                  AND reference_type = 'sale' AND reference_id = ?
                """, Long.class, productA, saleId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("voiding an already-voided sale is refused, and does not double-return")
    void doubleVoidIsRefused() {
        stock(productB, 10);
        BigDecimal beforeSale = onHand(productB);
        UUID saleId = sell(productB, 4);

        assertThat(http().postWithToken("/sales/" + saleId + "/void", "{}", token()).status())
                .isEqualTo(200);
        BigDecimal afterFirst = onHand(productB);
        assertThat(afterFirst).as("the first void returns the stock")
                .isEqualByComparingTo(beforeSale);

        var second = http().postWithToken("/sales/" + saleId + "/void", "{}", token());

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.json().get("type").asString()).endsWith("/sale-not-voidable");

        // The point of the test. A refused second void must not have moved
        // anything — 14 here would mean the stock came back twice and the shelf
        // now claims units that do not exist.
        assertThat(onHand(productB))
                .as("a refused void must not return the stock a second time")
                .isEqualByComparingTo(afterFirst);
    }

    @Test
    @DisplayName("concurrent voids of one sale: exactly one succeeds")
    void concurrentVoidsElectOneWinner() throws Exception {
        stock(productA, 50);
        UUID saleId = sell(productA, 5);
        BigDecimal before = onHand(productA);

        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> {
                    // All eight arrive together, so they genuinely contend
                    // rather than running in sequence and each seeing the last
                    // one's committed result.
                    barrier.await();
                    return http().postWithToken("/sales/" + saleId + "/void", "{}", token())
                            .status();
                });
            }

            List<Integer> statuses = pool.invokeAll(calls).stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            assertThat(statuses.stream().filter(s -> s == 200).count())
                    .as("exactly one void may succeed, got: %s", statuses)
                    .isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count())
                    .as("every other caller must be refused, got: %s", statuses)
                    .isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        // The assertion that matters more than the status counts: the stock came
        // back once. Eight winners would be 5 units short of visible, but two
        // winners would leave 5 phantom units on the shelf and nothing would say
        // so.
        assertThat(onHand(productA))
                .as("the stock must return exactly once no matter how many callers raced")
                .isEqualByComparingTo(before.add(new BigDecimal("5")));
    }

    @Test
    @DisplayName("voiding a sale that does not exist is 404, not 409")
    void unknownSaleIsNotFound() {
        var response = http().postWithToken(
                "/sales/" + UUID.randomUUID() + "/void", "{}", token());

        // T8: another tenant's sale is hidden by RLS and reaches this same path,
        // so this must be 404 rather than a 403 or a 409 that would confirm the
        // id names something real.
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a void with no body at all is accepted")
    void voidWithoutABodyWorks() {
        stock(productB, 8);
        UUID saleId = sell(productB, 2);

        // The contract marks the request body optional. A till that voids
        // without collecting a reason must not get a 400. Sent as an empty body
        // rather than a JSON null, which is what a client omitting the body
        // actually puts on the wire.
        var response = http().postWithToken("/sales/" + saleId + "/void", "", token());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("voided");
    }

    @Test
    @DisplayName("the void response satisfies the contract's SaleDetail")
    void responseIsAFullSaleDetail() {
        stock(productA, 12);
        UUID saleId = sell(productA, 3);

        var response = http().postWithToken("/sales/" + saleId + "/void", "{}", token());

        assertThat(response.status()).isEqualTo(200);
        // Fields the contract marks required on SaleDetail. Checked here as well
        // as in ResponseRequiredFieldsHttpTest because that sweep exercises
        // POST /sales, and a response is not covered because a sibling is.
        for (String field : List.of("id", "saleNumber", "status", "itemCount",
                "subtotalAmount", "discountAmount", "taxAmount", "totalAmount",
                "soldAt", "locationId", "createdBy", "lines")) {
            assertThat(response.json().has(field)).as("%s must be present", field).isTrue();
            assertThat(response.json().get(field).isNull())
                    .as("%s must not be null", field).isFalse();
        }
    }
}
