package com.example.inventory.purchasing;

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

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /purchase-orders} → {@code /submit} → {@code GET}, over HTTP —
 * CLAUDE.md §5: the status transitions and the response shape are claims about
 * the wire, not about {@code PurchaseOrderService} in isolation.
 *
 * <p>Receiving is {@code GoodsReceiptHttpTest}, added in the next step — this
 * file stops at the draft/ordered lifecycle M5 step 1 covers.
 */
@DisplayName("Purchase orders: draft, submit, read")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PurchaseOrderHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID ownerId;
    private static UUID supplierId;
    private static UUID productId;
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
        UUID locationId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'po-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(ownerId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            // Distinct cost/selling prices — CLAUDE.md §5, non-zero and
            // distinct so a mix-up between the two would be visible.
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price)
                    VALUES ('%s', '%s', 'SKU-A', 'Bread', 9.00, 12.50)
                    """.formatted(productId, tenantId));
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

    private UUID createDraft(int quantity) {
        var r = http().postWithToken("/purchase-orders", """
                {"supplierId":"%s","lines":[{"productId":"%s","quantityOrdered":%d,"unitCost":9.00}]}
                """.formatted(supplierId, productId, quantity), token());
        assertThat(r.status()).as("fixture PO creation must succeed: %s", r.body()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asString());
    }

    @Test
    @DisplayName("create leaves a draft; submit stamps orderedAt and moves it to ordered")
    void createThenSubmit() {
        var created = http().postWithToken("/purchase-orders", """
                {"supplierId":"%s","lines":[{"productId":"%s","quantityOrdered":10,"unitCost":9.00}]}
                """.formatted(supplierId, productId), token());

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().get("status").asString()).isEqualTo("draft");
        assertThat(created.json().get("orderedAt").isNull())
                .as("a draft has no orderedAt yet").isTrue();
        assertThat(created.json().get("poNumber").asString()).matches("PO-\\d{6,}");
        assertThat(created.json().get("supplierName").asString()).isEqualTo("Acme Bakery Supply");
        assertThat(created.json().get("totalAmount").asDouble()).isEqualTo(90.0);

        var lines = created.json().get("lines");
        assertThat(lines.size()).isEqualTo(1);
        var line = lines.get(0);
        assertThat(line.get("quantityOrdered").asDouble()).isEqualTo(10.0);
        assertThat(line.get("quantityReceived").asDouble()).isEqualTo(0.0);
        assertThat(line.get("quantityOutstanding").asDouble())
                .as("nothing received yet — outstanding equals ordered").isEqualTo(10.0);

        UUID poId = UUID.fromString(created.json().get("id").asString());

        var submitted = http().postWithToken("/purchase-orders/" + poId + "/submit", "", token());
        assertThat(submitted.status()).as(submitted.body()).isEqualTo(200);
        assertThat(submitted.json().get("status").asString()).isEqualTo("ordered");
        assertThat(submitted.json().get("orderedAt").isNull())
                .as("submit must stamp orderedAt — the lead-time clock's start").isFalse();

        var fetched = http().get("/purchase-orders/" + poId, token());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.json().get("status").asString()).isEqualTo("ordered");
    }

    @Test
    @DisplayName("a PO can only be submitted from draft")
    void submitOnlyFromDraft() {
        UUID poId = createDraft(5);

        assertThat(http().postWithToken("/purchase-orders/" + poId + "/submit", "", token()).status())
                .isEqualTo(200);

        var second = http().postWithToken("/purchase-orders/" + poId + "/submit", "", token());
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.json().get("type").asString()).endsWith("/po-not-submittable");
    }

    @Test
    @DisplayName("a line referencing a product outside the tenant's catalog is 404")
    void lineMustReferenceAnExistingProduct() {
        var r = http().postWithToken("/purchase-orders", """
                {"supplierId":"%s","lines":[{"productId":"%s","quantityOrdered":5}]}
                """.formatted(supplierId, UUID.randomUUID()), token());

        assertThat(r.status()).as(r.body()).isEqualTo(404);
    }
}
