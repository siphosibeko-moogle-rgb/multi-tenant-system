package com.example.inventory.purchasing;

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
 * {@code POST /purchase-orders/{poId}/receipts}, over HTTP — CLAUDE.md §5:
 * the status transitions, the outstanding quantities and the 409 are claims
 * about the wire and about the ledger, neither of which a
 * {@code GoodsReceiptService}-only test would reach.
 *
 * <p>Lead-time observation is asserted in {@code LeadTimeObservationHttpTest},
 * the next step — this file is the receiving mechanics alone: partial
 * accumulation through {@code StockLedgerService} (T4/T5), and the
 * {@code partial → received} transition.
 */
@DisplayName("Receiving stock against a purchase order")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GoodsReceiptHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID ownerId;
    private static UUID supplierId;
    private static UUID productId;
    private static UUID productB;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        ownerId = UUID.randomUUID();
        supplierId = UUID.randomUUID();
        productId = UUID.randomUUID();
        productB = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'gr-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(ownerId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                    VALUES ('%s', '%s', 'SKU-A', 'Bread', 9.00, 12.50)
                    """.formatted(productId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                    VALUES ('%s', '%s', 'SKU-B', 'Milk', 5.00, 7.25)
                    """.formatted(productB, tenantId));
            stmt.execute("""
                    INSERT INTO suppliers (id, tenant_id, name, lead_time_days)
                    VALUES ('%s', '%s', 'Acme Bakery Supply', 7)
                    """.formatted(supplierId, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, ownerId, "owner").value();
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

    /** A draft PO for {@code quantity} of the fixture product, not yet submitted. */
    private UUID createDraft(int quantity) {
        var r = http().postWithToken("/purchase-orders", """
                {"supplierId":"%s","lines":[{"productId":"%s","quantityOrdered":%d,"unitCost":9.00}]}
                """.formatted(supplierId, productId, quantity), token());
        assertThat(r.status()).as("fixture PO creation must succeed: %s", r.body()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asString());
    }

    /** A submitted (status = ordered) PO for {@code quantity} of the fixture product. */
    private UUID createAndSubmit(int quantity) {
        UUID poId = createDraft(quantity);
        var r = http().postWithToken("/purchase-orders/" + poId + "/submit", "", token());
        assertThat(r.status()).as("fixture submit must succeed: %s", r.body()).isEqualTo(200);
        return poId;
    }

    private HttpTestClient.Response receive(UUID poId, int quantity) {
        return http().postWithToken("/purchase-orders/" + poId + "/receipts", """
                {"lines":[{"productId":"%s","quantityReceived":%d}]}
                """.formatted(productId, quantity), token());
    }

    // ------------------------------------------------------------------

    private BigDecimal onHand(UUID productId) {
        return asOwner("SELECT COALESCE(SUM(quantity_on_hand), 0) FROM product_stock "
                + "WHERE product_id = ?", BigDecimal.class, productId);
    }

    @Test
    @DisplayName("two receipts (7 then 3) against one line leave two movements summing to 10 and the PO received")
    void partialThenFullReceiptClosesThePO() {
        BigDecimal before = onHand(productId);
        UUID poId = createAndSubmit(10);

        var first = receive(poId, 7);
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(first.json().get("status").asString()).isEqualTo("partial");

        var firstLine = first.json().get("lines").get(0);
        assertThat(firstLine.get("quantityReceived").asDouble()).isEqualTo(7.0);
        assertThat(firstLine.get("quantityOutstanding").asDouble()).isEqualTo(3.0);

        var second = receive(poId, 3);
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(second.json().get("status").asString()).isEqualTo("received");
        assertThat(second.json().get("receivedAt").isNull())
                .as("closing the order must stamp receivedAt").isFalse();

        var secondLine = second.json().get("lines").get(0);
        assertThat(secondLine.get("quantityReceived").asDouble()).isEqualTo(10.0);
        assertThat(secondLine.get("quantityOutstanding").asDouble())
                .as("fully received — nothing left outstanding").isEqualTo(0.0);

        // The point of the test: TWO movements, posted through the ledger,
        // summing to exactly the ordered quantity — not one movement of 10,
        // and not a quantity silently merged or dropped.
        Long movementCount = asOwner("""
                SELECT count(*) FROM stock_movements
                WHERE product_id = ? AND movement_type = 'purchase_receipt'
                  AND reference_type = 'purchase_order' AND reference_id = ?
                """, Long.class, productId, poId);
        assertThat(movementCount).as("two separate receipts must leave two separate movements")
                .isEqualTo(2L);

        BigDecimal totalReceived = asOwner("""
                SELECT COALESCE(SUM(quantity_delta), 0) FROM stock_movements
                WHERE product_id = ? AND movement_type = 'purchase_receipt'
                  AND reference_type = 'purchase_order' AND reference_id = ?
                """, BigDecimal.class, productId, poId);
        assertThat(totalReceived).isEqualByComparingTo(new BigDecimal("10"));

        // Relative, not absolute: this fixture's product is shared across
        // every test in this class (CLAUDE.md §5's "assert what you tell it
        // to" trap in the other direction — an absolute figure here would
        // pass or fail by which other test happened to run first).
        assertThat(onHand(productId)).as("stock on hand reflects both receipts")
                .isEqualByComparingTo(before.add(new BigDecimal("10")));
    }

    @Test
    @DisplayName("receiving against an already-fully-received LINE is refused while the order is still partial")
    void receivingAgainstAFullyReceivedLineIsRefused() {
        // Two lines so the order itself stays 'partial' (not yet
        // 'received') after line A closes — the scenario this test is
        // actually about is the LINE check, distinct from
        // receivingAgainstADraftIsRefused's ORDER-level guard below. A
        // single-line PO can't tell the two apart: once its one line is
        // done the order itself is 'received' and every subsequent request
        // is refused by the order-level guard before the line check is ever
        // reached.
        var created = http().postWithToken("/purchase-orders", """
                {"supplierId":"%s","lines":[
                    {"productId":"%s","quantityOrdered":5,"unitCost":9.00},
                    {"productId":"%s","quantityOrdered":5,"unitCost":5.00}
                ]}""".formatted(supplierId, productId, productB), token());
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        UUID poId = UUID.fromString(created.json().get("id").asString());
        assertThat(http().postWithToken("/purchase-orders/" + poId + "/submit", "", token()).status())
                .isEqualTo(200);

        // Line A (productId) fully received; line B (productB) only 3 of 5 —
        // the order stays 'partial'.
        var firstReceipt = http().postWithToken("/purchase-orders/" + poId + "/receipts", """
                {"lines":[
                    {"productId":"%s","quantityReceived":5},
                    {"productId":"%s","quantityReceived":3}
                ]}""".formatted(productId, productB), token());
        assertThat(firstReceipt.status()).as(firstReceipt.body()).isEqualTo(201);
        assertThat(firstReceipt.json().get("status").asString())
                .as("line B still has 2 outstanding — the order is not fully received")
                .isEqualTo("partial");

        BigDecimal onHandAAfterFirst = onHand(productId);

        // Line A already has zero outstanding; asking for 1 more of it must
        // be refused by the LINE check, even though the ORDER as a whole is
        // still 'partial' and would otherwise accept a receipt.
        var second = receive(poId, 1);
        assertThat(second.status()).as(second.body()).isEqualTo(409);
        assertThat(second.json().get("type").asString()).endsWith("/receipt-exceeds-outstanding");

        assertThat(onHand(productId))
                .as("a refused receipt must not move stock a second time")
                .isEqualByComparingTo(onHandAAfterFirst);
        assertThat(asOwner("SELECT status::text FROM purchase_orders WHERE id = ?",
                String.class, poId)).isEqualTo("partial");
    }

    @Test
    @DisplayName("receiving against a draft (never submitted) order is refused")
    void receivingAgainstADraftIsRefused() {
        UUID poId = createDraft(4);

        var r = receive(poId, 4);
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(r.json().get("type").asString()).endsWith("/po-not-receivable");
    }
}
