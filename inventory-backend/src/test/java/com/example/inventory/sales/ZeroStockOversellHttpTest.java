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
 * The oversell 409 from {@code POST /sales} when there is <em>nothing</em> on
 * the shelf.
 *
 * <h2>Why zero needs its own test</h2>
 *
 * <p>{@code InsufficientStockHttpTest} covers an oversell against a product with
 * stock, and {@code ResponseRequiredFieldsHttpTest} covers one through the sales
 * endpoint — both against a product that had been stocked first. Neither reaches
 * the case that is actually the default: <strong>a product with no stock at
 * all</strong>. A new catalogue is entirely zero-stock, so this is the first
 * refusal any real shop sees, not an edge case.
 *
 * <p>It is also the case where the two internal paths differ. A product that was
 * never stocked has no {@code product_stock} row, so the balance read that fills
 * in {@code available} has nothing to find; a product stocked and then emptied
 * has a row sitting at zero. Both must report {@code available: 0} — one from an
 * absent row and one from a present one — and only this test asks.
 *
 * <p>Emulator verification made the point sharply: a sale of six against zero
 * stock produced a message naming neither number. The body was fine by the time
 * it was captured here, but nothing had ever asserted it for this shape, which
 * is why the failure could be attributed to the client, the server or the
 * template with equal plausibility. Now the server's half is pinned.
 *
 * <p>The exact bodies this produces are the fixtures used by the Android
 * {@code ApiErrorTest} and {@code RecordSaleViewModelTest}, rather than bodies
 * invented to suit those tests. A fixture asserts what you tell it to; these are
 * told by the server.
 */
@DisplayName("Overselling a product with no stock")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ZeroStockOversellHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID neverStocked;
    private static UUID stockedThenEmptied;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        neverStocked = UUID.randomUUID();
        stockedThenEmptied = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'probe-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'o-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-NEVER', 'Never Stocked', 5.00)
                    """.formatted(neverStocked, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, selling_price)
                    VALUES ('%s', '%s', 'SKU-EMPTIED', 'Emptied', 5.00)
                    """.formatted(stockedThenEmptied, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    @Test
    @DisplayName("a product that was never stocked reports available 0, not null")
    void neverStockedReportsZero() {
        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":6}]}
                """.formatted(neverStocked), token());

        assertThat(response.status()).isEqualTo(409);

        var body = response.json();

        // Presence before value. A null `available` is precisely what made the
        // Android client discard the whole problem body and show the server's
        // detail instead, and asserting the number alone cannot see the
        // difference between 0 and absent.
        assertThat(body.has("requested")).as("requested must be present").isTrue();
        assertThat(body.get("requested").isNull()).as("requested must not be null").isFalse();
        assertThat(body.has("available")).as("available must be present").isTrue();
        assertThat(body.get("available").isNull())
                .as("available must not be null — a null here is unconstructable by a "
                        + "generated client and costs the caller `requested` as well")
                .isFalse();

        assertThat(body.get("requested").asInt()).isEqualTo(6);
        assertThat(body.get("available").asDouble()).isZero();
        assertThat(body.get("productId").asString()).isEqualTo(neverStocked.toString());
    }

    @Test
    @DisplayName("a product stocked and then emptied reports available 0 too")
    void emptiedReportsZero() {
        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":2,"reason":"opening"}
                """.formatted(stockedThenEmptied), token());
        var sold = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":2}]}
                """.formatted(stockedThenEmptied), token());
        assertThat(sold.status()).as("the fixture sale must succeed").isEqualTo(201);

        var response = http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":6}]}
                """.formatted(stockedThenEmptied), token());

        assertThat(response.status()).isEqualTo(409);
        var body = response.json();

        assertThat(body.has("available")).isTrue();
        assertThat(body.get("available").isNull()).isFalse();
        // Reaches zero down a different route from the test above: this product
        // HAS a product_stock row, sitting at zero, where the other has none.
        // PostgreSQL renders the numeric(14,3) column as 0.000, which the client
        // must normalise rather than show to a shop owner.
        assertThat(body.get("available").asDouble()).isZero();
        assertThat(body.get("requested").asInt()).isEqualTo(6);
    }
}
