package com.example.inventory.purchasing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The criterion this whole milestone was for: a receipt against a purchase
 * order must leave {@code supplier_lead_time_observations} in a state that
 * {@code GET /suppliers/{supplierId}} reports correctly — not merely a row
 * that exists somewhere, per CLAUDE.md §5 ("a criterion written about a
 * response must be asserted over HTTP"), and not only incidentally covered
 * by {@code GoodsReceiptHttpTest}'s broader assertions about the ledger and
 * the PO's own status.
 *
 * <p>Every test seeds its own supplier ({@link #newSupplier()}) rather than
 * sharing the class's tenant fixture, deliberately: {@code sampleSize} is a
 * count, and a shared supplier across several tests would make that count
 * (and therefore these assertions) depend on JUnit's undefined method
 * order — exactly the trap {@code GoodsReceiptHttpTest}'s stock-on-hand
 * assertion fell into first (CLAUDE.md §5).
 */
@DisplayName("Lead time observations")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LeadTimeObservationHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID ownerId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        ownerId = UUID.randomUUID();
        productId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'lt-%s', 'Shop')"
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
        }
        seeded = true;
    }

    /** A fresh supplier — see the class Javadoc for why one per test. */
    private UUID newSupplier() throws SQLException {
        UUID supplierId = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            // lead_time_days (promised) deliberately far from the observed
            // figures these tests produce, so a bug that returned the
            // promised number instead of the observed one would be visible.
            stmt.execute("""
                    INSERT INTO suppliers (id, tenant_id, name, lead_time_days)
                    VALUES ('%s', '%s', 'Acme Bakery Supply %s', 21)
                    """.formatted(supplierId, tenantId, supplierId.toString().substring(0, 8)));
        }
        return supplierId;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, ownerId, "owner").value();
    }

    private UUID createAndSubmit(UUID supplierId, int quantity) {
        var created = http().postWithToken("/purchase-orders", """
                {"supplierId":"%s","lines":[{"productId":"%s","quantityOrdered":%d,"unitCost":9.00}]}
                """.formatted(supplierId, productId, quantity), token());
        assertThat(created.status()).as("fixture PO creation must succeed: %s", created.body())
                .isEqualTo(201);
        UUID poId = UUID.fromString(created.json().get("id").asString());

        var submitted = http().postWithToken("/purchase-orders/" + poId + "/submit", "", token());
        assertThat(submitted.status()).as("fixture submit must succeed: %s", submitted.body())
                .isEqualTo(200);
        return poId;
    }

    private OffsetDateTime orderedAtOf(UUID poId) {
        var r = http().get("/purchase-orders/" + poId, token());
        assertThat(r.status()).isEqualTo(200);
        return OffsetDateTime.parse(r.json().get("orderedAt").asString());
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("receiving a PO records an observation the supplier's observedLeadTime reflects, over HTTP")
    void receivingRecordsAnObservationVisibleOnTheSupplier() throws SQLException {
        UUID supplierId = newSupplier();
        UUID poId = createAndSubmit(supplierId, 4);

        // A deliberate, exact 5-day gap: computed from the PO's own
        // orderedAt (read back from the API, not assumed) rather than from
        // wall-clock timing, so the expected lead time is exact rather than
        // "somewhere around zero", and distinct from the supplier's
        // promised 21 days so the two cannot be confused.
        OffsetDateTime receivedAt = orderedAtOf(poId).plusDays(5);

        var receipt = http().postWithToken("/purchase-orders/" + poId + "/receipts", """
                {"receivedAt":"%s","lines":[{"productId":"%s","quantityReceived":4}]}
                """.formatted(receivedAt, productId), token());
        assertThat(receipt.status()).as(receipt.body()).isEqualTo(201);
        assertThat(receipt.json().get("status").asString()).isEqualTo("received");

        // The criterion that matters most in this milestone: query the
        // supplier over HTTP — not the database directly — and confirm
        // observedLeadTime reflects this one receipt.
        var supplier = http().get("/suppliers/" + supplierId, token());
        assertThat(supplier.status()).as(supplier.body()).isEqualTo(200);

        var observed = supplier.json().get("observedLeadTime");
        assertThat(observed.has("sampleSize")).as("sampleSize must be present").isTrue();
        assertThat(observed.get("sampleSize").asInt())
                .as("exactly one order was received, against a fresh supplier")
                .isEqualTo(1);

        assertThat(observed.has("averageDays")).as("averageDays must be present").isTrue();
        assertThat(observed.get("averageDays").isNull()).as("averageDays must not be null").isFalse();
        assertThat(observed.get("averageDays").asDouble())
                .as("the observed figure, not the promised 21 days typed into the supplier form")
                .isEqualTo(5.0);
    }

    @Test
    @DisplayName("a PO closed across two partial receipts records exactly ONE observation, at the final receipt")
    void partialReceiptsRecordOnlyOneObservationAtClose() throws SQLException {
        UUID supplierId = newSupplier();
        UUID poId = createAndSubmit(supplierId, 10);
        OffsetDateTime orderedAt = orderedAtOf(poId);

        // First partial: 7 of 10, "arriving" 2 days after the order — if
        // this were mistakenly used as the lead-time timestamp (the
        // rejected design in GoodsReceiptService's Javadoc), the observed
        // figure would read 2 days instead of the correct 9.
        var first = http().postWithToken("/purchase-orders/" + poId + "/receipts", """
                {"receivedAt":"%s","lines":[{"productId":"%s","quantityReceived":7}]}
                """.formatted(orderedAt.plusDays(2), productId), token());
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(first.json().get("status").asString()).isEqualTo("partial");

        var afterFirst = http().get("/suppliers/" + supplierId, token());
        assertThat(afterFirst.json().get("observedLeadTime").get("sampleSize").asInt())
                .as("a partial receipt must not record an observation yet")
                .isEqualTo(0);

        // Second and final: the remaining 3, 9 days after the order.
        var second = http().postWithToken("/purchase-orders/" + poId + "/receipts", """
                {"receivedAt":"%s","lines":[{"productId":"%s","quantityReceived":3}]}
                """.formatted(orderedAt.plusDays(9), productId), token());
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(second.json().get("status").asString()).isEqualTo("received");

        var afterSecond = http().get("/suppliers/" + supplierId, token());
        var observed = afterSecond.json().get("observedLeadTime");
        assertThat(observed.get("sampleSize").asInt())
                .as("one PO, however many partial receipts it took, is one observation")
                .isEqualTo(1);
        assertThat(observed.get("averageDays").asDouble())
                .as("measured from the FINAL receipt (9 days), not the first partial one (2 days)")
                .isEqualTo(9.0);
    }

    @Test
    @DisplayName("after three receipts from one supplier, averageDays is the real average — M5's own Done-when criterion")
    void threeReceiptsProduceARealAverageNotThePromisedFigure() throws SQLException {
        // lead_time_days on the supplier form is 21 (see newSupplier) — the
        // three real observations below average to 5, and the assertion
        // checks specifically for 5, not "not 21", so a bug that returned
        // the promised figure would be caught rather than passing by
        // asserting the negative.
        UUID supplierId = newSupplier();

        int[] actualLeadTimeDays = {3, 5, 7};
        for (int days : actualLeadTimeDays) {
            UUID poId = createAndSubmit(supplierId, 2);
            OffsetDateTime receivedAt = orderedAtOf(poId).plusDays(days);
            var receipt = http().postWithToken("/purchase-orders/" + poId + "/receipts", """
                    {"receivedAt":"%s","lines":[{"productId":"%s","quantityReceived":2}]}
                    """.formatted(receivedAt, productId), token());
            assertThat(receipt.status()).as(receipt.body()).isEqualTo(201);
        }

        var supplier = http().get("/suppliers/" + supplierId, token());
        assertThat(supplier.status()).as(supplier.body()).isEqualTo(200);
        var observed = supplier.json().get("observedLeadTime");

        assertThat(observed.get("sampleSize").asInt())
                .as("three separate order cycles, three observations").isEqualTo(3);
        assertThat(observed.get("averageDays").asDouble())
                .as("(3 + 5 + 7) / 3 = 5 — the measured average, not the promised 21")
                .isEqualTo(5.0);
    }
}
