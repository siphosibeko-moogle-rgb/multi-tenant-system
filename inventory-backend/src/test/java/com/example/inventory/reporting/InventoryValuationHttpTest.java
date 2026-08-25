package com.example.inventory.reporting;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * {@code GET /reports/inventory-valuation}, including the {@code asOf} replay.
 *
 * <p>M8's own acceptance criterion is "valuation {@code asOf} a past date
 * matches a manual replay of the ledger to that date". {@link #asOfMatchesAManualReplay}
 * is that criterion, and it does the replay <strong>independently</strong> — its
 * own SQL over {@code stock_movements}, over the owner connection, not a second
 * call to the endpoint and not the query the endpoint uses. A test that replays
 * by asking the thing it is checking proves only that the code is deterministic.
 *
 * <p>The other assertion worth naming is {@link #todayAgreesAcrossBothQueryShapes}.
 * The endpoint has two implementations of one number — {@code product_stock} for
 * now and a ledger replay for the past — because always replaying would be
 * needlessly expensive on the call the dashboard makes on every screen open.
 * Two implementations of one number is exactly what CLAUDE.md §5 keeps finding
 * bugs in, so something has to compare them.
 */
@DisplayName("GET /reports/inventory-valuation")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryValuationHttpTest extends AbstractIntegrationTest {

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

    private JsonNode valuation(String query) {
        HttpTestClient.Response response = http().get(
                "/reports/inventory-valuation" + (query.isEmpty() ? "" : "?" + query), owner());
        assertThat(response.status()).as("body: %s", response.body()).isEqualTo(200);
        return response.json();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("returns 200 and every field the contract requires")
    void shape() {
        HttpTestClient.Response response = http().get("/reports/inventory-valuation", owner());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().first("Content-Type")).startsWith("application/json");

        JsonNode body = response.json();
        for (String field : new String[]{"totalCostValue", "totalRetailValue",
                                         "productCount", "asOf"}) {
            assertThat(body.has(field)).as("%s must be present", field).isTrue();
            assertThat(body.get(field).isNull()).as("%s must not be null", field).isFalse();
        }
    }

    @Test
    @DisplayName("values current stock at cost and at retail, and counts the products")
    void currentValuation() {
        JsonNode body = valuation("");

        assertThat(body.get("totalCostValue").decimalValue())
                .as("95 x 7.00 + 91 x 3.00 + 0 x 1.00 + 10 x 2.00")
                .isEqualByComparingTo(ReportFixture.EXPECTED_INVENTORY_COST_VALUE);
        assertThat(body.get("totalRetailValue").decimalValue())
                .as("95 x 20.00 + 91 x 5.00 + 0 x 2.00 + 10 x 4.00")
                .isEqualByComparingTo(ReportFixture.EXPECTED_INVENTORY_RETAIL_VALUE);
        assertThat(body.get("productCount").asLong())
                .as("three products hold stock; the sold-out one holds none")
                .isEqualTo(ReportFixture.EXPECTED_VALUED_PRODUCT_COUNT);

        // Cost and retail must not be the same number, or a swapped column in
        // the row mapper would pass both assertions above.
        assertThat(body.get("totalCostValue").decimalValue())
                .isNotEqualByComparingTo(body.get("totalRetailValue").decimalValue());

        assertThat(LocalDate.parse(body.get("asOf").asString()))
                .as("asOf is echoed even when omitted, so a client never has to "
                        + "guess what `today` meant in the tenant's timezone")
                .isEqualTo(today());
    }

    /**
     * The branch guard. {@code asOf=today} takes the replay path; omitting
     * {@code asOf} takes the {@code product_stock} path. They must agree.
     */
    @Test
    @DisplayName("today agrees whether it is read from the cache or replayed from the ledger")
    void todayAgreesAcrossBothQueryShapes() {
        JsonNode fromCache = valuation("");
        JsonNode replayed = valuation("asOf=" + today());

        assertThat(replayed.get("totalCostValue").decimalValue())
                .as("product_stock is the ledger's running fold (T5); replaying it "
                        + "to now must land on the same number")
                .isEqualByComparingTo(fromCache.get("totalCostValue").decimalValue());
        assertThat(replayed.get("totalRetailValue").decimalValue())
                .isEqualByComparingTo(fromCache.get("totalRetailValue").decimalValue());
        assertThat(replayed.get("productCount").asLong())
                .isEqualTo(fromCache.get("productCount").asLong());

        // And it is the real figure, not two paths agreeing on nothing.
        assertThat(replayed.get("totalCostValue").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_INVENTORY_COST_VALUE);
    }

    /**
     * M8's "Done when": a past valuation matches a manual replay of the ledger.
     *
     * <p>The manual replay is written here, in SQL of its own, run over the
     * owner connection. It deliberately does not reuse anything from
     * {@code ReportQueries} — a replay performed by the code under test is not a
     * check, it is a restatement.
     */
    @Test
    @DisplayName("a past asOf matches a manual replay of the ledger to that date")
    void asOfMatchesAManualReplay() throws SQLException {
        // A shop of its own, because this test makes history and the rest of the
        // class asserts on a known present. JUnit promises no ordering, so a
        // backdated movement written into the shared fixture would turn
        // currentValuation into a coin flip — a flake wearing a real failure's
        // clothes.
        ReportFixture past = ReportFixture.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), http(),
                (tenantId, userId) -> jwtService
                        .issueAccessToken(tenantId, userId, "owner").value());
        String token = jwtService.issueAccessToken(past.tenantId, past.userId, "owner").value();

        // Backdate one movement so there IS a difference between then and now.
        // Without it, "the past" and "the present" hold the same stock and every
        // assertion below passes for a replay that ignored asOf entirely.
        LocalDate cutoff = today().minusDays(3);
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            // History is made by INSERTING a backdated movement, never by
            // editing one: stock_movements is append-only (T4) and the trigger
            // refuses an UPDATE even for the owner. A real backdated correction
            // is exactly this row.
            stmt.execute("""
                    INSERT INTO stock_movements (tenant_id, product_id, location_id,
                                                 movement_type, quantity_delta, occurred_at)
                    VALUES ('%s', '%s', '%s', 'adjustment', 13, now() - interval '10 days')
                    """.formatted(past.tenantId, past.productD, past.locationId));
        }

        HttpTestClient.Response response = http().get(
                "/reports/inventory-valuation?asOf=" + cutoff, token);
        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(LocalDate.parse(body.get("asOf").asString())).isEqualTo(cutoff);

        Replay expected = manualReplay(past, cutoff);

        assertThat(expected.costValue())
                .as("the manual replay must value something at the cutoff, or this "
                        + "comparison is two zeroes agreeing")
                .isGreaterThan(BigDecimal.ZERO);

        assertThat(body.get("totalCostValue").decimalValue())
                .isEqualByComparingTo(expected.costValue());
        assertThat(body.get("totalRetailValue").decimalValue())
                .isEqualByComparingTo(expected.retailValue());
        assertThat(body.get("productCount").asLong()).isEqualTo(expected.productCount());

        // 13 units of Date Loaf at 2.00 — that is all this shop held three days
        // ago, because everything else arrived today.
        assertThat(body.get("totalCostValue").decimalValue()).isEqualByComparingTo("26.00");

        // And the past is genuinely a different number from the present. This is
        // what fails if asOf is parsed, echoed and then ignored by the query,
        // which would otherwise look entirely correct.
        JsonNode now = http().get("/reports/inventory-valuation", token).json();
        assertThat(body.get("totalCostValue").decimalValue())
                .as("a past valuation that equals the present one is not a replay")
                .isNotEqualByComparingTo(now.get("totalCostValue").decimalValue());
    }

    private record Replay(BigDecimal costValue, BigDecimal retailValue, long productCount) {
    }

    /** An independent fold of the ledger, written for this test alone. */
    private Replay manualReplay(ReportFixture fixture, LocalDate asOf) throws SQLException {
        String sql = """
                SELECT COALESCE(round(SUM(bal.qty * p.cost_price), 2), 0)    AS cost_value,
                       COALESCE(round(SUM(bal.qty * p.selling_price), 2), 0) AS retail_value,
                       count(*) FILTER (WHERE bal.qty <> 0)                  AS product_count
                FROM (
                    SELECT m.product_id, SUM(m.quantity_delta) AS qty
                    FROM stock_movements m
                    WHERE m.tenant_id = '%s'
                      AND m.occurred_at < (DATE '%s' + 1)::timestamp AT TIME ZONE 'UTC'
                    GROUP BY m.product_id
                ) bal
                JOIN products p ON p.id = bal.product_id
                WHERE p.deleted_at IS NULL AND p.is_active AND p.is_tracked
                """.formatted(fixture.tenantId, asOf);

        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return new Replay(rs.getBigDecimal("cost_value"),
                    rs.getBigDecimal("retail_value"),
                    rs.getLong("product_count"));
        }
    }

    @Test
    @DisplayName("a date before the shop existed values nothing")
    void beforeTheBeginning() {
        JsonNode body = valuation("asOf=" + today().minusDays(400));

        assertThat(body.get("totalCostValue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(body.get("totalRetailValue").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(body.get("productCount").asLong()).isZero();
    }

    @Test
    @DisplayName("a future asOf is 400, not a silent valuation of today")
    void futureAsOf() {
        HttpTestClient.Response response = http().get(
                "/reports/inventory-valuation?asOf=" + today().plusDays(1), owner());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).contains("future");
    }

    /**
     * The contract's shared {@code LocationIdQuery} says "defaults to the
     * tenant's default location". This endpoint deliberately does not, and the
     * parameter is inlined on this path in the contract to say so.
     *
     * <p>This fixture has one location, so both readings give the same total —
     * which is the point of asserting the filter works at all: naming the one
     * location must reproduce the whole valuation, and naming a location that
     * belongs to nobody must value nothing.
     */
    @Test
    @DisplayName("locationId narrows the valuation, and omitting it values everything")
    void locationFilter() {
        JsonNode all = valuation("");
        JsonNode mine = valuation("locationId=" + shop.locationId);
        JsonNode elsewhere = valuation("locationId=" + java.util.UUID.randomUUID());

        assertThat(mine.get("totalCostValue").decimalValue())
                .isEqualByComparingTo(all.get("totalCostValue").decimalValue());
        assertThat(elsewhere.get("totalCostValue").decimalValue())
                .as("a location this tenant does not have holds nothing — and is a "
                        + "valuation of zero rather than an error, since under RLS "
                        + "another tenant's location is indistinguishable from one "
                        + "that never existed (T8)")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("an unparseable asOf is 400, not 500")
    void malformedAsOf() {
        HttpTestClient.Response response =
                http().get("/reports/inventory-valuation?asOf=last-tuesday", owner());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).contains("asOf");
    }
}
