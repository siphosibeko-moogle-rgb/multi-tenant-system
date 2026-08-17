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
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The oversell 409, asserted <strong>over HTTP</strong>.
 *
 * <h2>Why this exists when the service-level tests already cover oversell</h2>
 *
 * <p>Because the milestone criterion is not "the service throws". It is:
 * overselling returns 409 with {@code productId}, {@code requested} and
 * {@code available} <em>in the problem body</em>, and stock is unchanged
 * afterwards. Every part of that after "throws" lives in
 * {@code GlobalExceptionHandler} and the HTTP layer, and no service-level test
 * touches any of it.
 *
 * <p>{@code ConcurrentOversellTest} asserts the exception carries the right
 * values. That is necessary and not sufficient: the handler could drop a field,
 * use the wrong status, or emit {@code application/json} instead of
 * {@code application/problem+json}, and every existing test would stay green.
 * Confirmed by mutation — removing {@code available} from the handler turns this
 * class red and nothing else.
 */
@DisplayName("Insufficient stock over HTTP")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InsufficientStockHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static UUID tenantId;
    private static UUID userId;
    private static UUID locationId;
    private static UUID productId;
    private static boolean seeded;

    @BeforeEach
    void seed() throws SQLException {
        if (seeded) {
            return;
        }
        tenantId = newTenantId();
        userId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        productId = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("INSERT INTO tenants (id, slug, name) VALUES ('%s', 'http409-%s', 'Shop')"
                    .formatted(tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("""
                    INSERT INTO users (id, tenant_id, email, full_name, role, status)
                    VALUES ('%s', '%s', 'mgr-%s@example.test', 'Mo Manager', 'manager', 'active')
                    """.formatted(userId, tenantId, tenantId.toString().substring(0, 8)));
            stmt.execute("INSERT INTO locations (id, tenant_id, name, is_default) "
                    + "VALUES ('%s', '%s', 'Main', true)".formatted(locationId, tenantId));
            // Never stocked, and not allowed to go negative: any removal is an
            // oversell.
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, allow_negative_stock)
                    VALUES ('%s', '%s', 'SKU-EMPTY-HTTP', 'Nothing In Stock', false)
                    """.formatted(productId, tenantId));
        }
        seeded = true;
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String managerToken() {
        return jwtService.issueAccessToken(tenantId, userId, "manager").value();
    }

    private long movementCount() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            return new JdbcTemplate(new org.springframework.jdbc.datasource
                    .SingleConnectionDataSource(conn, true))
                    .queryForObject("SELECT count(*) FROM stock_movements WHERE product_id = ?",
                            Long.class, productId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("409 with productId, requested and available in the problem body")
    void oversellReturnsAProblemDetailWithTheNumbers() {
        // Three in stock, five requested. Both numbers non-zero and different
        // from each other on purpose: with available == 0, an assertion on its
        // value cannot tell a zero from a missing field, and with requested ==
        // available the two could be swapped unnoticed. This test was written
        // that weaker way first and a mutation walked straight through it.
        var stocked = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":3,"reason":"opening"}
                """.formatted(productId), managerToken());
        assertThat(stocked.status()).as("precondition: three units on hand").isEqualTo(201);

        long movementsBefore = movementCount();

        var response = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":-5,"reason":"damaged in transit"}
                """.formatted(productId), managerToken());

        assertThat(response.status())
                .as("the contract's InsufficientStock response is a 409")
                .isEqualTo(409);

        assertThat(response.headers().first("Content-Type"))
                .as("RFC 9457 — problem+json, not application/json. A client that branches on "
                        + "content type would not recognise the latter as an error shape.")
                .contains("application/problem+json");

        var body = response.json();

        // PRESENCE first, value second. Jackson's at() yields a MissingNode for
        // an absent field and asDouble() turns that into 0.0, so a value-only
        // assertion passes just as happily when the field was never emitted.
        // Dropping `available` from the handler proved exactly that: the earlier
        // version of this test stayed green through the mutation.
        for (String field : java.util.List.of("productId", "requested", "available")) {
            assertThat(body.at("/" + field).isMissingNode())
                    .as("%s must be PRESENT in the problem body — the contract's "
                            + "InsufficientStock response names it, and a client cannot ask the "
                            + "user to reorder without it", field)
                    .isFalse();
        }

        assertThat(body.at("/productId").asString())
                .as("productId must name the product that could not be moved — with several "
                        + "lines in flight, a client cannot infer which one failed")
                .isEqualTo(productId.toString());

        assertThat(body.at("/requested").asDouble())
                .as("requested is the absolute quantity asked for, not the signed delta: the "
                        + "caller sent -5 and the shortfall is 5")
                .isEqualTo(5.0);

        assertThat(body.at("/available").asDouble())
                .as("available is what there actually was — 3, not 0 and not 5. Distinct from "
                        + "requested so the two cannot be swapped without this failing.")
                .isEqualTo(3.0);

        // The generic problem fields must still be there — this is one shape
        // everywhere, not a bespoke error for this case.
        assertThat(body.at("/status").asInt()).isEqualTo(409);
        assertThat(body.at("/title").asString()).isNotBlank();
        assertThat(body.at("/type").asString()).contains("insufficient-stock");

        assertThat(response.body())
                .as("never a stack trace")
                .doesNotContain("Exception").doesNotContain("at com.example");

        assertThat(movementCount())
                .as("and stock is unchanged afterwards — the refused adjustment must leave no "
                        + "row behind, which is the second half of the milestone criterion")
                .isEqualTo(movementsBefore);
    }

    @Test
    @DisplayName("a successful adjustment is 201, so the 409 is not the only outcome")
    void aValidAdjustmentSucceeds() {
        // Without this, the 409 assertions above would pass just as well against
        // an endpoint that refused everything.
        var response = http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":7,"reason":"found in the back"}
                """.formatted(productId), managerToken());

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.at("/movementType")).isEqualTo("adjustment");
        assertThat(response.json().at("/balanceAfter").isMissingNode())
                .as("a successful adjustment reports the resulting balance")
                .isFalse();
    }
}
