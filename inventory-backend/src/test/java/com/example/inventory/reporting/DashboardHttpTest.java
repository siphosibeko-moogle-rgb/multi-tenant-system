package com.example.inventory.reporting;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /reports/dashboard}, over real HTTP, against numbers a reader can
 * check on paper.
 *
 * <p>CLAUDE.md §5: a criterion written about a response is asserted over HTTP —
 * status, content type, and every field the contract names. A report is a
 * particularly bad place to stop at the service layer: a miscalculated figure is
 * a wrong answer a shop owner acts on, and the only place the answer exists is
 * the response body.
 *
 * <p>Every expected value comes from {@link ReportFixture}, where the arithmetic
 * is written out. They are non-zero and mutually distinct on purpose — a suite
 * of zeroes cannot tell a present field from a missing one, and two equal fields
 * can be swapped without any assertion noticing.
 */
@DisplayName("GET /reports/dashboard")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private static ReportFixture shop;

    @BeforeEach
    void seedOnce() throws SQLException {
        if (shop == null) {
            shop = ReportFixture.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword(), http(), this::tokenFor);
        }
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String tokenFor(UUID tenantId, UUID userId) {
        return jwtService.issueAccessToken(tenantId, userId, "owner").value();
    }

    private String owner() {
        return jwtService.issueAccessToken(shop.tenantId, shop.userId, "owner").value();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("returns 200 and application/json")
    void statusAndContentType() {
        HttpTestClient.Response response = http().get("/reports/dashboard", owner());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().first("Content-Type"))
                .as("the contract declares application/json")
                .startsWith("application/json");
    }

    @Test
    @DisplayName("reports the money: net revenue, gross profit, and the count of sales")
    void money() {
        JsonNode body = http().get("/reports/dashboard?period=week", owner()).json();

        // Present BEFORE value, in that order. Jackson's at()/asDouble() turns an
        // absent field into 0.0, so a value assertion alone passes for a field
        // that was never emitted — the trap that hid M2's always-zero
        // `available` for a whole milestone.
        assertThat(body.has("salesTotal")).as("salesTotal must be present").isTrue();
        assertThat(body.has("grossProfit")).as("grossProfit must be present").isTrue();
        assertThat(body.has("salesCount")).as("salesCount must be present").isTrue();

        assertThat(body.get("salesTotal").decimalValue())
                .as("ex-tax, net of the returned unit, voids excluded")
                .isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(body.get("grossProfit").decimalValue())
                .as("revenue %s minus the snapshotted cost %s",
                        ReportFixture.EXPECTED_REVENUE, ReportFixture.EXPECTED_COST)
                .isEqualByComparingTo(ReportFixture.EXPECTED_GROSS_PROFIT);
        assertThat(body.get("salesCount").asLong())
                .as("a partially returned sale still happened")
                .isEqualTo(ReportFixture.EXPECTED_SALES_COUNT);

        // Distinctness is the point: if revenue and profit were ever computed
        // from the same expression this would be the assertion that objects.
        assertThat(body.get("salesTotal").decimalValue())
                .isNotEqualByComparingTo(body.get("grossProfit").decimalValue());
    }

    @Test
    @DisplayName("values the stock on hand at today's cost price")
    void inventoryValue() {
        JsonNode body = http().get("/reports/dashboard", owner()).json();

        assertThat(body.has("inventoryValue")).isTrue();
        assertThat(body.get("inventoryValue").decimalValue())
                .as("95 x 7.00 + 91 x 3.00 + 0 x 1.00 + 10 x 2.00")
                .isEqualByComparingTo(ReportFixture.EXPECTED_INVENTORY_COST_VALUE);
    }

    @Test
    @DisplayName("counts low stock, out of stock and open recommendations")
    void counts() {
        JsonNode body = http().get("/reports/dashboard", owner()).json();

        assertThat(body.has("lowStockCount")).isTrue();
        assertThat(body.has("outOfStockCount")).isTrue();
        assertThat(body.has("openRecommendationCount")).isTrue();

        // D holds 10 against a reorder point of 50. A and B have no reorder
        // point and no forecast, which R__views.sql deliberately answers `ok`
        // rather than `unknown`, so neither of them is counted here.
        assertThat(body.get("lowStockCount").asLong())
                .as("only Date Loaf is below its reorder point")
                .isEqualTo(1);
        assertThat(body.get("outOfStockCount").asLong())
                .as("only Cinnamon Bun was sold out")
                .isEqualTo(1);

        // Zero, and asserted as zero rather than skipped: nothing has run a
        // recompute for this tenant. The non-zero case is covered against real
        // seeded history in DashboardSeedDataTest, where a recompute has run.
        assertThat(body.get("openRecommendationCount").asLong()).isZero();
    }

    @Test
    @DisplayName("salesTrend is 14 consecutive days ending today, zero-filled")
    void trend() {
        JsonNode body = http().get("/reports/dashboard?period=today", owner()).json();

        JsonNode trend = body.get("salesTrend");
        assertThat(trend).as("salesTrend must be present").isNotNull();
        assertThat(trend.isArray()).isTrue();
        assertThat(trend.size())
                .as("fixed at 14 days, and NOT following period=today — a "
                        + "single-point trend is not a trend (ADR §3)")
                .isEqualTo(14);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int i = 0; i < trend.size(); i++) {
            LocalDate expected = today.minusDays(13L - i);
            assertThat(LocalDate.parse(trend.get(i).get("day").asString()))
                    .as("point %d must be %s — a gap would draw a line between "
                            + "two non-adjacent days", i, expected)
                    .isEqualTo(expected);
        }

        // Every sale in this fixture happened today, so the shape is known
        // exactly: thirteen zeroes and the day's whole takings.
        for (int i = 0; i < 13; i++) {
            assertThat(trend.get(i).get("revenue").decimalValue())
                    .as("day %s had no sales", trend.get(i).get("day").asString())
                    .isEqualByComparingTo("0.00");
        }
        assertThat(trend.get(13).get("revenue").decimalValue())
                .as("today's takings, and the same number as salesTotal")
                .isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
    }

    @Test
    @DisplayName("topProducts ranks by units sold, not by revenue")
    void topProductsRankByUnits() {
        JsonNode top = http().get("/reports/dashboard", owner()).json().get("topProducts");

        assertThat(top).isNotNull();
        assertThat(top.size())
                .as("three products sold; the fourth sold nothing and is not a top product")
                .isEqualTo(3);

        // THE discriminating assertion of ADR §4. Almond Tart earns 100.00 to
        // Bran Roll's 45.00, and Bran Roll still leads because it moved 9 units
        // to Almond Tart's 5. Rank by revenue and this order inverts.
        assertThat(top.get(0).get("name").asString()).isEqualTo("Bran Roll");
        assertThat(top.get(1).get("name").asString()).isEqualTo("Cinnamon Bun");
        assertThat(top.get(2).get("name").asString()).isEqualTo("Almond Tart");

        assertThat(top.get(0).get("unitsSold").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_B_UNITS);
        assertThat(top.get(1).get("unitsSold").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_C_UNITS);
        assertThat(top.get(2).get("unitsSold").decimalValue())
                .as("4 sold, then 2 more, then 1 returned")
                .isEqualByComparingTo(ReportFixture.EXPECTED_A_UNITS);

        assertThat(top.get(0).get("productId").asString()).isEqualTo(shop.productB.toString());
    }

    @Test
    @DisplayName("period echoes back, and defaults to week")
    void periodEchoes() {
        assertThat(http().get("/reports/dashboard", owner()).json().get("period").asString())
                .isEqualTo("week");
        assertThat(http().get("/reports/dashboard?period=month", owner())
                .json().get("period").asString())
                .isEqualTo("month");
    }

    @Test
    @DisplayName("an unknown period is 400, not 500")
    void unknownPeriodIsABadRequest() {
        HttpTestClient.Response response =
                http().get("/reports/dashboard?period=quarter", owner());

        assertThat(response.status())
                .as("a bad query parameter is the caller's problem to fix; a 500 "
                        + "tells them to retry, which would never work")
                .isEqualTo(400);
        assertThat(response.headers().first("Content-Type"))
                .startsWith("application/problem+json");
        assertThat(response.json().get("detail").asString()).contains("today, week, month");
    }

    @Test
    @DisplayName("an upper-case period is accepted, because a generated client will send one")
    void upperCasePeriodIsAccepted() {
        HttpTestClient.Response response = http().get("/reports/dashboard?period=WEEK", owner());

        // The Android pass lost a day to exactly this: Retrofit renders a @Query
        // enum with toString(), which is the Kotlin constant name, so the
        // generator's own default went on the wire upper-case against a
        // lower-case contract and produced a 500.
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("period").asString()).isEqualTo("week");
    }
}
