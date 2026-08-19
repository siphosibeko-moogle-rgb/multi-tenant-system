package com.example.inventory.inventory;

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
 * {@code stockState}, which is computed in two places that must agree.
 *
 * <h2>What this is really testing</h2>
 *
 * <p>The list path reads {@code v_stock_status}; the single-product reads
 * compute the same thing in Java, in {@code ProductCatalog.stockState}, because
 * they do not go through the view. Two implementations of one rule is a standing
 * invitation to drift, and a drift here shows up as the product list and the
 * product page disagreeing about the same item — reported by users as "the app
 * is wrong" with no further detail, and very hard to find from that.
 *
 * <p>So every case below is asserted through <strong>both</strong> paths, and
 * the assertion is that they are equal to each other as well as equal to the
 * expected value. Checking only the expected value would let both drift together
 * and still pass; checking only equality would let them agree on the wrong
 * answer.
 *
 * <h2>The `unknown` regression this pins</h2>
 *
 * <p>Both paths used to answer {@code unknown} when a product had no reorder
 * point. Nothing sets a reorder point — the column defaults to null and the
 * forecaster that would supply one is M7 — so {@code unknown} was the state of
 * essentially every product in every new business. Emulator verification found a
 * product holding ten units labelled "unknown", which is not an edge case but
 * the default experience.
 *
 * <p>Not knowing whether stock is <em>low</em> is not the same as not knowing
 * what the stock <em>is</em>. With ten on the shelf and no threshold configured,
 * there is no evidence of a problem, and {@code ok} is the honest answer.
 * {@code unknown} stays in the contract's enum, reserved for a state that
 * genuinely cannot be determined — a value that fires for "nobody configured
 * this yet" is one clients learn to ignore, and then it cannot warn about
 * anything.
 */
@DisplayName("Stock state")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockStateTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID noThreshold;
    private static UUID belowThreshold;
    private static UUID empty;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        noThreshold = UUID.randomUUID();
        belowThreshold = UUID.randomUUID();
        empty = UUID.randomUUID();
        String tag = tenantId.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'state-%s', 'Shop')"
                    .formatted(tenantId, tag));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'own-%s@example.test', 'Olu Owner', 'owner', 'active')
                    """.formatted(userId, tenantId, tag));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));

            // Stock, no reorder point. The case that used to read `unknown`.
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, reorder_point)
                    VALUES ('%s', '%s', 'SKU-NO-RP', 'No Threshold', NULL)
                    """.formatted(noThreshold, tenantId));
            // Stock, and a reorder point ABOVE it. Distinct numbers so a swap
            // would be visible: 10 on hand against a threshold of 25.
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, reorder_point)
                    VALUES ('%s', '%s', 'SKU-LOW', 'Below Threshold', 25)
                    """.formatted(belowThreshold, tenantId));
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, reorder_point)
                    VALUES ('%s', '%s', 'SKU-EMPTY', 'Nothing Left', NULL)
                    """.formatted(empty, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String token() {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private void stock(UUID productId, int quantity) {
        var response = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":%d,"reason":"opening"}
                """.formatted(productId, quantity), token());
        assertThat(response.status()).as("fixture must post successfully").isEqualTo(201);
    }

    /** The view's answer, via the paginated list that reads v_stock_status. */
    private String fromTheView(UUID productId) {
        var response = http().get("/inventory?limit=200", token());
        assertThat(response.status()).isEqualTo(200);
        for (var item : response.json().get("items")) {
            if (productId.toString().equals(item.get("productId").asString())) {
                return item.get("stockState").asString();
            }
        }
        throw new AssertionError("product " + productId + " absent from /inventory");
    }

    /** The Java mirror's answer, via the single-product read. */
    private String fromJava(UUID productId) {
        var response = http().get("/products/" + productId, token());
        assertThat(response.status()).isEqualTo(200);
        return response.json().get("stockState").asString();
    }

    private void bothAgree(UUID productId, String expected) {
        String view = fromTheView(productId);
        String java = fromJava(productId);

        assertThat(view)
                .as("v_stock_status must answer %s", expected).isEqualTo(expected);
        assertThat(java)
                .as("ProductCatalog.stockState must answer %s", expected).isEqualTo(expected);
        // Explicit, though implied by the two above: the point of this test is
        // that the two implementations do not drift apart.
        assertThat(java)
                .as("the view and its Java mirror must not disagree about one product")
                .isEqualTo(view);
    }

    @Test
    @DisplayName("stock with no reorder point is ok, not unknown")
    void noReorderPointIsOk() {
        stock(noThreshold, 10);

        // The regression this exists for. Before the fix both paths said
        // `unknown`, so every product in every new business was labelled
        // unknown while sitting in plain sight on the shelf.
        bothAgree(noThreshold, "ok");
    }

    @Test
    @DisplayName("stock at or below the reorder point is reorder")
    void belowTheThresholdIsReorder() {
        stock(belowThreshold, 10);

        bothAgree(belowThreshold, "reorder");
    }

    @Test
    @DisplayName("no stock is out_of_stock, threshold or not")
    void nothingOnHandIsOutOfStock() {
        // Never stocked: quantity_on_hand is zero, and that outranks every
        // other consideration including the absent threshold.
        assertThat(fromJava(empty)).isEqualTo("out_of_stock");
    }

    @Test
    @DisplayName("out_of_stock outranks a reorder point that would also match")
    void emptyIsNotReportedAsReorder() {
        // Both rules match a product at zero with a threshold of 25 — available
        // is certainly <= 25. The order of the CASE decides, and reporting
        // "reorder" for a shelf with nothing on it would understate it.
        stock(belowThreshold, 0 - 10);

        bothAgree(belowThreshold, "out_of_stock");
    }
}
