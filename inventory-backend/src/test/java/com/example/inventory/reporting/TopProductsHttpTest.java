package com.example.inventory.reporting;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
 * {@code GET /reports/top-products}.
 *
 * <p>The decision this endpoint exists to hold to is ADR §4: <strong>ranked by
 * units sold, not by revenue</strong>, here and on the dashboard, one key and
 * never two.
 *
 * <p>{@link ReportFixture} is built so that the two rankings <em>disagree</em> —
 * Almond Tart earns 100.00 on 5 units while Bran Roll earns 45.00 on 9. With any
 * other fixture the two orders coincide and a test of one passes silently for
 * the other, which is the whole reason the fixture's numbers are shaped the way
 * they are.
 */
@DisplayName("GET /reports/top-products")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TopProductsHttpTest extends AbstractIntegrationTest {

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

    private JsonNode top(String query) {
        HttpTestClient.Response response = http().get(
                "/reports/top-products" + (query.isEmpty() ? "" : "?" + query), owner());
        assertThat(response.status()).as("body: %s", response.body()).isEqualTo(200);
        return response.json();
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        for (JsonNode row : array) {
            names.add(row.get("name").asString());
        }
        return names;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("returns 200, a JSON array, and every field the contract requires")
    void shape() {
        HttpTestClient.Response response = http().get("/reports/top-products", owner());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().first("Content-Type")).startsWith("application/json");

        JsonNode body = response.json();
        assertThat(body.isArray())
                .as("the contract declares a bare array here, not a page")
                .isTrue();
        assertThat(body.size())
                .as("an empty array satisfies every field assertion without checking one")
                .isGreaterThan(0);

        for (JsonNode row : body) {
            for (String field : new String[]{"productId", "sku", "name", "unitsSold", "revenue"}) {
                assertThat(row.has(field)).as("%s must be present: %s", field, row).isTrue();
                assertThat(row.get(field).isNull()).as("%s must not be null: %s", field, row)
                        .isFalse();
            }
        }
    }

    /**
     * The discriminating assertion of the whole milestone's §4 decision.
     */
    @Test
    @DisplayName("best movers rank by units sold — the fixture's revenue order is the reverse")
    void bestRanksByUnitsNotRevenue() {
        JsonNode body = top("order=best");

        assertThat(names(body))
                .as("9 units, then 6, then 5")
                .containsExactly("Bran Roll", "Cinnamon Bun", "Almond Tart");

        // Same rows, ranked the other way, to show the orders really differ. If
        // this ever stops holding, the fixture has lost the property that makes
        // the assertion above mean anything and must be fixed before trusting it.
        assertThat(body.get(0).get("revenue").decimalValue())
                .as("the leader by units earns LESS than the product below it")
                .isEqualByComparingTo(ReportFixture.EXPECTED_B_REVENUE);
        assertThat(body.get(2).get("revenue").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_A_REVENUE);
        assertThat(body.get(2).get("revenue").decimalValue())
                .isGreaterThan(body.get(0).get("revenue").decimalValue());

        assertThat(body.get(0).get("unitsSold").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_B_UNITS);
        assertThat(body.get(0).get("sku").asString()).contains("REP-B-");
        assertThat(body.get(0).get("productId").asString()).isEqualTo(shop.productB.toString());
    }

    @Test
    @DisplayName("best excludes a product that sold nothing")
    void bestExcludesNonSellers() {
        assertThat(names(top("order=best")))
                .as("padding a best-sellers list with zeroes helps nobody")
                .doesNotContain("Date Loaf");
    }

    /**
     * The asymmetry between {@code best} and {@code worst} is deliberate and is
     * the thing most likely to be "simplified" away later.
     */
    @Test
    @DisplayName("worst movers INCLUDE products that sold nothing, and lead with them")
    void worstIncludesNonSellers() {
        JsonNode body = top("order=worst");

        assertThat(names(body).get(0))
                .as("a product that sold nothing is the worst mover there is — an "
                        + "aggregate over sale_items cannot see it, so the query "
                        + "left-joins the catalogue to find it")
                .isEqualTo("Date Loaf");
        assertThat(body.get(0).get("unitsSold").decimalValue()).isEqualByComparingTo("0.000");
        assertThat(body.get(0).get("revenue").decimalValue()).isEqualByComparingTo("0.00");

        // Then the real sellers, fewest units first — the exact reverse of best.
        assertThat(names(body))
                .containsExactly("Date Loaf", "Almond Tart", "Cinnamon Bun", "Bran Roll");
    }

    @Test
    @DisplayName("grossMarginPct is null for a product with no revenue, and a real figure otherwise")
    void grossMargin() {
        JsonNode worst = top("order=worst");

        assertThat(worst.get(0).has("grossMarginPct")).isTrue();
        assertThat(worst.get(0).get("grossMarginPct").isNull())
                .as("0%% would be a claim about a margin that does not exist")
                .isTrue();

        JsonNode best = top("order=best");
        // Bran Roll: 9 x (5.00 - 3.00) = 18.00 profit on 45.00 revenue = 40%.
        assertThat(best.get(0).get("grossMarginPct").decimalValue())
                .isEqualByComparingTo("40.00");
        // Almond Tart: 5 x (20.00 - 7.00) = 65.00 on 100.00 = 65%. Distinct from
        // 40%, so a row mapper reading the wrong row cannot pass both.
        assertThat(best.get(2).get("grossMarginPct").decimalValue())
                .isEqualByComparingTo("65.00");
    }

    @Test
    @DisplayName("the margin uses the sale-time cost snapshot, so revenue minus cost reconciles")
    void marginReconcilesWithTheDashboard() {
        JsonNode best = top("order=best");

        java.math.BigDecimal revenue = java.math.BigDecimal.ZERO;
        java.math.BigDecimal profit = java.math.BigDecimal.ZERO;
        for (JsonNode row : best) {
            java.math.BigDecimal rowRevenue = row.get("revenue").decimalValue();
            revenue = revenue.add(rowRevenue);
            profit = profit.add(rowRevenue
                    .multiply(row.get("grossMarginPct").decimalValue())
                    .divide(new java.math.BigDecimal("100"), 2,
                            java.math.RoundingMode.HALF_UP));
        }

        // Per-product figures must add up to the whole-shop ones. Two endpoints
        // answering the same question is the shape CLAUDE.md §5 keeps finding
        // bugs in; ReportAgreementHttpTest compares them directly, and this
        // checks the arithmetic inside one response is self-consistent too.
        assertThat(revenue).isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(profit).isEqualByComparingTo(ReportFixture.EXPECTED_GROSS_PROFIT);
    }

    @Test
    @DisplayName("limit caps the list, and defaults to the contract's 50")
    void limits() {
        assertThat(top("order=worst&limit=2").size()).isEqualTo(2);
        assertThat(top("order=worst&limit=1").size()).isEqualTo(1);

        // Four products exist, so the default and the ceiling both return all of
        // them — asserted so that a limit accidentally applied as 0 or 1 fails.
        assertThat(top("order=worst").size()).isEqualTo(4);
        assertThat(top("order=worst&limit=500").size()).isEqualTo(4);
    }

    @Test
    @DisplayName("an explicit range that predates the shop returns nothing sold")
    void explicitRange() {
        JsonNode body = top("order=best&from=%s&to=%s"
                .formatted(today().minusDays(400), today().minusDays(398)));

        assertThat(body.isArray()).isTrue();
        assertThat(body.size())
                .as("`best` requires units, and nothing sold in that window")
                .isZero();

        // `worst` over the same dead window still lists the catalogue, every
        // product at zero — which is the honest answer to "what moved least".
        JsonNode worst = top("order=worst&from=%s&to=%s"
                .formatted(today().minusDays(400), today().minusDays(398)));
        assertThat(worst.size()).isEqualTo(4);
        for (JsonNode row : worst) {
            assertThat(row.get("unitsSold").decimalValue()).isEqualByComparingTo("0.000");
        }
    }

    @Test
    @DisplayName("an unknown order is 400, and an upper-case one is accepted")
    void orderValidation() {
        HttpTestClient.Response bad = http().get("/reports/top-products?order=BIGGEST", owner());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().get("detail").asString()).contains("best, worst");

        assertThat(http().get("/reports/top-products?order=BEST", owner()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("an inverted explicit range is 400")
    void invertedRange() {
        HttpTestClient.Response response = http().get("/reports/top-products?from=%s&to=%s"
                .formatted(today(), today().minusDays(7)), owner());

        assertThat(response.status()).isEqualTo(400);
    }
}
