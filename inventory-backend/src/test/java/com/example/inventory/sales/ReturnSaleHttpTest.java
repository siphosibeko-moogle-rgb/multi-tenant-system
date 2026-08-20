package com.example.inventory.sales;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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
 * {@code POST /sales/{saleId}/returns}, over HTTP — CLAUDE.md §5: a criterion
 * about a response is asserted through a real request, not at the service
 * layer where the interesting part (status code, the new sale status, the
 * per-line figures) has not happened yet.
 *
 * <p>Shares {@link SaleService#returnOutstanding} with {@link VoidSaleHttpTest}'s
 * subject, so this file does not re-prove the restock/damaged-goods branch or
 * the double-return refusal — those are void's tests exercising the same
 * method. What is specific to a partial return: the sale lands in
 * {@code partially_refunded} rather than a terminal state, and
 * {@code returnedQuantity}/{@code outstandingQuantity} on the line reflect it.
 */
@DisplayName("Returning part of a sale")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReturnSaleHttpTest extends AbstractIntegrationTest {

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
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'ret-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            // Distinct prices/quantities throughout, per CLAUDE.md §5 — a test
            // cannot pass by confusing one figure for another.
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

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a partial return of 2 from a sale of 5 leaves outstandingQuantity 3 and the sale partially_refunded")
    void partialReturnLeavesTheRightOutstandingQuantity() {
        stock(productA, 20);
        BigDecimal beforeSale = onHand(productA);
        UUID saleId = sell(productA, 5);
        assertThat(onHand(productA)).isEqualByComparingTo(beforeSale.subtract(new BigDecimal("5")));

        var response = http().postWithToken("/sales/" + saleId + "/returns", """
                {"lines":[{"productId":"%s","quantity":2}],"reason":"wrong size"}
                """.formatted(productA), token());

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().get("status").asString()).isEqualTo("partially_refunded");

        var lines = response.json().get("lines");
        assertThat(lines.isArray()).isTrue();
        var line = lines.get(0);
        assertThat(line.get("productId").asString()).isEqualTo(productA.toString());
        assertThat(line.get("returnedQuantity").asDouble()).isEqualTo(2.0);
        assertThat(line.get("outstandingQuantity").asDouble()).isEqualTo(3.0);

        // Confirmed independently of the response body: the DB agrees.
        assertThat(asOwner("SELECT status::text FROM sales WHERE id = ?", String.class, saleId))
                .isEqualTo("partially_refunded");

        // restock defaults to true — the 2 units came back onto the shelf.
        assertThat(onHand(productA))
                .as("a restocked return puts the units back")
                .isEqualByComparingTo(beforeSale.subtract(new BigDecimal("3")));
    }

    @Test
    @DisplayName("returning more than what is outstanding is refused, and moves nothing")
    void overReturnIsRefused() {
        stock(productB, 10);
        UUID saleId = sell(productB, 4);
        BigDecimal afterSale = onHand(productB);

        var response = http().postWithToken("/sales/" + saleId + "/returns", """
                {"lines":[{"productId":"%s","quantity":5}]}
                """.formatted(productB), token());

        assertThat(response.status()).as(response.body()).isEqualTo(409);
        assertThat(response.json().get("type").asString()).endsWith("/return-exceeds-sale");

        // The point of the test: a refused return must not have moved
        // anything, and the sale must still be sitting at 'completed'.
        assertThat(onHand(productB))
                .as("a refused return must not move stock")
                .isEqualByComparingTo(afterSale);
        assertThat(asOwner("SELECT status::text FROM sales WHERE id = ?", String.class, saleId))
                .isEqualTo("completed");
    }
}
