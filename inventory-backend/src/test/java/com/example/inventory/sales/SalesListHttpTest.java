package com.example.inventory.sales;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * {@code GET /sales} and {@code GET /sales/{saleId}}, over HTTP — both
 * declared in the contract since M1 and unimplemented until now.
 *
 * <p>Two properties, each its own test: cursor pagination advances without
 * dropping or repeating a sale (the same failure mode CLAUDE.md §13 describes
 * for {@code /users}), and the listing is tenant-scoped — asserted as a row
 * count against a second, seeded tenant, per CLAUDE.md §10, rather than
 * trusted because RLS is proven elsewhere.
 */
@DisplayName("Listing and reading sales")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesListHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantA;
    private static UUID ownerA;
    private static UUID productA;
    private static UUID tenantB;
    private static UUID ownerB;
    private static UUID productB;
    // Captured so getReturnsTheSaleOr404 can reuse one rather than creating a
    // 4th sale in tenant A — this class shares one seeded tenant across
    // tests, and listPaginatesWithinTheTenant asserts an EXACT count, which
    // JUnit's undefined method order would otherwise make flaky.
    private static List<UUID> tenantASaleIds;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantA = newTenantId();
        ownerA = UUID.randomUUID();
        productA = UUID.randomUUID();
        tenantB = newTenantId();
        ownerB = UUID.randomUUID();
        productB = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            seedTenant(stmt, tenantA, ownerA, productA, "lsa");
            seedTenant(stmt, tenantB, ownerB, productB, "lsb");
        }
        seeded = true;

        // Three sales in A, one in B — enough to exercise a two-page listing
        // in A without B contributing any of A's pages.
        stock(tenantA, ownerA, productA, 10);
        tenantASaleIds = List.of(
                sell(tenantA, ownerA, productA),
                sell(tenantA, ownerA, productA),
                sell(tenantA, ownerA, productA));
        stock(tenantB, ownerB, productB, 10);
        sell(tenantB, ownerB, productB);
    }

    private static void seedTenant(Statement stmt, UUID tenantId, UUID ownerId, UUID productId,
                                   String tag) throws SQLException {
        UUID locationId = UUID.randomUUID();
        stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', '%s', 'Shop')"
                .formatted(tenantId, tag));
        stmt.execute("""
                INSERT INTO users (id, tenant_id, email, full_name, role, status)
                VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                """.formatted(ownerId, tenantId, tag));
        stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
        stmt.execute("""
                INSERT INTO products (id, tenant_id, sku, name, selling_price)
                VALUES ('%s', '%s', 'SKU-A', 'Bread', 12.50)
                """.formatted(productId, tenantId));
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token(UUID tenantId, UUID userId) {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private void stock(UUID tenantId, UUID userId, UUID productId, int quantity) {
        var r = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"opening"}
                """.formatted(productId, quantity), token(tenantId, userId));
        assertThat(r.status()).as("fixture stocking must succeed").isEqualTo(201);
    }

    private UUID sell(UUID tenantId, UUID userId, UUID productId) {
        var r = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":1}]}
                """.formatted(productId), token(tenantId, userId));
        assertThat(r.status()).as("fixture sale must succeed: %s", r.body()).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asString());
    }

    @Test
    @DisplayName("cursor pagination covers every sale in the tenant exactly once, and none of another tenant's")
    void listPaginatesWithinTheTenant() {
        var page1 = http().get("/sales?limit=2", token(tenantA, ownerA));
        assertThat(page1.status()).as(page1.body()).isEqualTo(200);
        assertThat(page1.json().get("items").size()).isEqualTo(2);
        String cursor = page1.json().get("nextCursor").isNull()
                ? null : page1.json().get("nextCursor").asString();
        assertThat(cursor).as("a 3-sale tenant asked for 2 must report a next page").isNotNull();

        var page2 = http().get("/sales?limit=2&cursor=" + cursor, token(tenantA, ownerA));
        assertThat(page2.status()).as(page2.body()).isEqualTo(200);
        assertThat(page2.json().get("items").size()).isEqualTo(1);
        assertThat(page2.json().get("nextCursor").isNull())
                .as("the last page must report no further cursor").isTrue();

        Set<String> ids = new HashSet<>();
        for (var page : List.of(page1, page2)) {
            for (var item : page.json().get("items")) {
                ids.add(item.get("id").asString());
            }
        }
        assertThat(ids).as("exactly the 3 sales seeded for tenant A, no repeats, no drops")
                .hasSize(3);
    }

    @Test
    @DisplayName("GET /sales/{saleId} returns the sale; an unknown or another tenant's id is 404")
    void getReturnsTheSaleOr404() {
        UUID saleId = tenantASaleIds.get(0);

        var found = http().get("/sales/" + saleId, token(tenantA, ownerA));
        assertThat(found.status()).as(found.body()).isEqualTo(200);
        assertThat(found.json().get("id").asString()).isEqualTo(saleId.toString());

        // T8: tenant B's token reaching tenant A's sale id must be 404, not
        // 403 — a 403 would confirm the id names something real.
        var wrongTenant = http().get("/sales/" + saleId, token(tenantB, ownerB));
        assertThat(wrongTenant.status()).isEqualTo(404);

        var unknown = http().get("/sales/" + UUID.randomUUID(), token(tenantA, ownerA));
        assertThat(unknown.status()).isEqualTo(404);
    }
}
