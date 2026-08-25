package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Gross profit is computed from the cost snapshotted at sale time, not
 * from whatever a product costs today.</strong>
 *
 * <p>This is M8's headline decision (ADR §1) and the one most likely to be
 * "simplified" by someone who notices that {@code products.cost_price} is right
 * there and that joining it removes a column from a query. The consequence of
 * that change is not a wrong number today; it is that <em>every historical
 * margin silently moves every time anyone edits a product</em>. Last month's
 * report gives a different answer this month, with nothing recording that it
 * changed. A report that is not reproducible is not a report.
 *
 * <h2>Why one assertion is not enough</h2>
 *
 * <p>"Change the cost, re-read the report, show the profit did not move" passes
 * trivially for an implementation that ignores cost altogether — a report
 * reporting a constant is very stable indeed. So the same test also shows that a
 * <em>new</em> sale picks the new cost up. Together they pin the actual
 * behaviour: cost is read, and it is read from the snapshot.
 *
 * <p>The two cost prices are far apart and neither is zero, so no arithmetic
 * accident can make the before and after coincide.
 */
@DisplayName("Gross profit uses the sale-time cost snapshot")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CostSnapshotHttpTest extends AbstractIntegrationTest {

    /** What the product cost when it was sold. */
    private static final BigDecimal COST_AT_SALE_TIME = new BigDecimal("4.00");

    /** What a supplier later decided to charge. Deliberately far away. */
    private static final BigDecimal COST_AFTER_THE_RISE = new BigDecimal("19.00");

    private static final BigDecimal SELLING_PRICE = new BigDecimal("25.00");

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static ReportFixture shop;

    @BeforeEach
    void seedOnce() throws SQLException {
        if (shop == null) {
            shop = ReportFixture.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword(), http(),
                    (tenantId, userId) -> jwtService
                            .issueAccessToken(tenantId, userId, "owner").value());
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String owner() {
        return jwtService.issueAccessToken(shop.tenantId, shop.userId, "owner").value();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** This product's own line in today's top-products list. */
    private JsonNode rowFor(java.util.UUID productId) {
        JsonNode body = http().get("/reports/top-products?order=best&limit=200&from=%s&to=%s"
                .formatted(today(), today()), owner()).json();
        for (JsonNode row : body) {
            if (row.get("productId").asString().equals(productId.toString())) {
                return row;
            }
        }
        throw new AssertionError("no top-products row for " + productId + ": " + body);
    }

    private BigDecimal profitOf(JsonNode row) {
        return row.get("revenue").decimalValue()
                .multiply(row.get("grossMarginPct").decimalValue())
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
    }

    private void setCostPrice(java.util.UUID productId, BigDecimal cost) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE products SET cost_price = %s WHERE id = '%s'"
                    .formatted(cost, productId));
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a supplier's later price rise does not change what an old sale earned")
    void aLaterPriceRiseDoesNotRewriteHistory() throws SQLException {
        java.util.UUID product = java.util.UUID.randomUUID();
        String tag = product.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price,
                                          tax_rate)
                    VALUES ('%s', '%s', 'SNAP-%s', 'Snapshot Scone', %s, %s, 0.10)
                    """.formatted(product, shop.tenantId, tag, COST_AT_SALE_TIME, SELLING_PRICE));
        }

        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":40,"reason":"opening"}
                """.formatted(product), owner());

        // Sell 3 at a cost of 4.00: revenue 75.00, cost 12.00, profit 63.00.
        http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":3}]}
                """.formatted(product), owner());

        BigDecimal profitBefore = profitOf(rowFor(product));
        assertThat(profitBefore)
                .as("3 x (25.00 - 4.00)")
                .isEqualByComparingTo("63.00");

        // The supplier raises their price, nearly fivefold.
        setCostPrice(product, COST_AFTER_THE_RISE);

        BigDecimal profitAfter = profitOf(rowFor(product));

        assertThat(profitAfter)
                .as("the sale already happened at 4.00; reading cost_price fresh "
                        + "would report 3 x (25.00 - 19.00) = 18.00 instead")
                .isEqualByComparingTo(profitBefore);

        // Spelled out, because the number a regression would produce is the
        // useful thing to see in a failure message.
        assertThat(profitAfter)
                .as("a fresh read of products.cost_price would give 18.00")
                .isNotEqualByComparingTo("18.00");
    }

    /**
     * The other half. Without it, the assertion above passes for an
     * implementation that never reads a cost at all.
     */
    @Test
    @DisplayName("but a NEW sale is costed at the new price — the snapshot is taken, not frozen")
    void aNewSalePicksUpTheNewCost() throws SQLException {
        java.util.UUID product = java.util.UUID.randomUUID();
        String tag = product.toString().substring(0, 8);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO products (id, tenant_id, sku, name, cost_price, selling_price,
                                          tax_rate)
                    VALUES ('%s', '%s', 'SNAP2-%s', 'Second Scone', %s, %s, 0.10)
                    """.formatted(product, shop.tenantId, tag, COST_AT_SALE_TIME, SELLING_PRICE));
        }

        http().postWithToken("/inventory/adjustments", """
                {"productId":"%s","quantityDelta":40,"reason":"opening"}
                """.formatted(product), owner());
        http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":1}]}
                """.formatted(product), owner());

        // 1 x (25.00 - 4.00)
        assertThat(profitOf(rowFor(product))).isEqualByComparingTo("21.00");

        setCostPrice(product, COST_AFTER_THE_RISE);

        http().postWithToken("/sales", """
                {"lines":[{"productId":"%s","quantity":1}]}
                """.formatted(product), owner());

        // The old unit keeps its 21.00; the new one earns 25.00 - 19.00 = 6.00.
        // A report that ignored cost entirely could not produce 27.00, and a
        // report that read cost_price fresh would produce 2 x 6.00 = 12.00.
        assertThat(profitOf(rowFor(product)))
                .as("21.00 from the old cost plus 6.00 from the new one")
                .isEqualByComparingTo("27.00");
    }

    @Test
    @DisplayName("the dashboard agrees with the per-product view about the same price rise")
    void theDashboardUsesTheSnapshotToo() throws SQLException {
        // The two endpoints share ReportQueries.NET_LINES, which is why they are
        // expected to agree — but a shared definition is a reason to expect
        // agreement, not a proof of it, and the dashboard adds its own SUM on
        // top. M7 shipped a bug that lived exactly in that gap.
        JsonNode before = http().get("/reports/dashboard?period=today", owner()).json();
        BigDecimal profitBefore = before.get("grossProfit").decimalValue();

        assertThat(profitBefore)
                .as("a dashboard reporting zero profit would satisfy the comparison "
                        + "below without checking anything")
                .isGreaterThan(BigDecimal.ZERO);

        setCostPrice(shop.productA, new BigDecimal("18.50"));

        assertThat(http().get("/reports/dashboard?period=today", owner()).json()
                .get("grossProfit").decimalValue())
                .as("Almond Tart's cost nearly tripled and today's takings are unchanged")
                .isEqualByComparingTo(profitBefore);

        // Put it back, so a test that runs after this one still meets the shop
        // the fixture describes.
        setCostPrice(shop.productA, new BigDecimal(ReportFixture.A_COST));
    }
}
