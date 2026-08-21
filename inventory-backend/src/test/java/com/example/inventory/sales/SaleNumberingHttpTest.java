package com.example.inventory.sales;

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
 * Sale numbering, over HTTP — sequential and per-tenant (CLAUDE.md M4 §
 * "sale numbering"). Two properties, each with its own test: consecutive
 * sales in one tenant get consecutive numbers, and two DIFFERENT tenants each
 * start their own sequence at 1 rather than sharing one global counter — the
 * whole reason V7 put the counter on {@code tenants} instead of a
 * {@code SEQUENCE}.
 */
@DisplayName("Sale numbering")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SaleNumberingHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        productId = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'num-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-A', 'Bread', 12.50)
                    """.formatted(productId, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private void stock(int quantity) {
        var r = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"opening"}
                """.formatted(productId, quantity), token());
        assertThat(r.status()).as("fixture stocking must succeed").isEqualTo(201);
    }

    private String sell() {
        var r = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":1}]}
                """.formatted(productId), token());
        assertThat(r.status()).as("fixture sale must succeed: %s", r.body()).isEqualTo(201);
        return r.json().get("saleNumber").asString();
    }

    @Test
    @DisplayName("consecutive sales in one tenant get consecutive, human-readable numbers")
    void numbersAreSequentialWithinATenant() {
        stock(10);
        String first = sell();
        String second = sell();
        String third = sell();

        // Format asserted, not assumed: S-%06d.
        assertThat(first).matches("S-\\d{6,}");
        assertThat(second).matches("S-\\d{6,}");
        assertThat(third).matches("S-\\d{6,}");

        long firstN = Long.parseLong(first.substring(2));
        long secondN = Long.parseLong(second.substring(2));
        long thirdN = Long.parseLong(third.substring(2));

        assertThat(secondN).as("no gap between consecutive sales").isEqualTo(firstN + 1);
        assertThat(thirdN).as("no gap between consecutive sales").isEqualTo(secondN + 1);
    }
}
