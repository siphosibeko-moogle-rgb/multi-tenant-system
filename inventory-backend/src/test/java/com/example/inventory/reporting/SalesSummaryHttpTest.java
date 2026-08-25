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
 * {@code GET /reports/sales-summary}, over real HTTP.
 *
 * <p>The interesting properties of this endpoint are not the totals — those are
 * the dashboard's numbers bucketed — but the <em>bucketing</em>: that an empty
 * day is emitted as a zero rather than skipped, that the buckets cover the range
 * asked for and no more, and that {@code groupBy} actually changes the shape
 * rather than being accepted and ignored.
 *
 * <p>A parameter that is accepted and ignored is the specific failure worth
 * testing for here. It produces a correct-looking response every time, and the
 * only way to catch it is to ask for two different groupings and assert they
 * differ.
 */
@DisplayName("GET /reports/sales-summary")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesSummaryHttpTest extends AbstractIntegrationTest {

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

    private JsonNode summary(String query) {
        HttpTestClient.Response response = http().get("/reports/sales-summary?" + query, owner());
        assertThat(response.status())
                .as("body: %s", response.body())
                .isEqualTo(200);
        return response.json();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("returns 200, application/json and a buckets array")
    void shape() {
        LocalDate today = today();
        HttpTestClient.Response response = http().get(
                "/reports/sales-summary?from=%s&to=%s".formatted(today.minusDays(6), today),
                owner());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().first("Content-Type")).startsWith("application/json");
        assertThat(response.json().has("buckets"))
                .as("the contract requires `buckets`")
                .isTrue();
        assertThat(response.json().get("buckets").isArray()).isTrue();
    }

    @Test
    @DisplayName("one bucket per day across the range, including days with no sales")
    void everyDayIsPresent() {
        LocalDate today = today();
        JsonNode buckets = summary("from=%s&to=%s".formatted(today.minusDays(6), today))
                .get("buckets");

        assertThat(buckets.size())
                .as("seven days inclusive; a bucket omitted for an empty day would "
                        + "make a chart draw a line between two non-adjacent points")
                .isEqualTo(7);

        for (int i = 0; i < 7; i++) {
            LocalDate expected = today.minusDays(6L - i);
            assertThat(LocalDate.parse(buckets.get(i).get("periodStart").asString()))
                    .isEqualTo(expected);

            // Present before value. An absent numeric field reads as zero
            // through asDouble(), which would make the six empty days pass this
            // assertion whether or not they were emitted at all.
            assertThat(buckets.get(i).has("unitsSold")).isTrue();
            assertThat(buckets.get(i).has("revenue")).isTrue();
            assertThat(buckets.get(i).has("grossProfit")).isTrue();
        }

        for (int i = 0; i < 6; i++) {
            assertThat(buckets.get(i).get("revenue").decimalValue()).isEqualByComparingTo("0.00");
            assertThat(buckets.get(i).get("unitsSold").decimalValue()).isEqualByComparingTo("0.000");
        }
    }

    @Test
    @DisplayName("today's bucket carries the whole day's units, revenue and profit")
    void todaysNumbers() {
        LocalDate today = today();
        JsonNode buckets = summary("from=%s&to=%s".formatted(today.minusDays(6), today))
                .get("buckets");
        JsonNode last = buckets.get(buckets.size() - 1);

        assertThat(last.get("revenue").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(last.get("grossProfit").decimalValue())
                .as("computed from the sale-time cost snapshot, same as the dashboard")
                .isEqualByComparingTo(ReportFixture.EXPECTED_GROSS_PROFIT);

        // 5 + 9 + 6 units, net of the one returned. Distinct from every money
        // figure above, so a column swapped in the row mapper cannot pass.
        assertThat(last.get("unitsSold").decimalValue()).isEqualByComparingTo("20.000");
    }

    @Test
    @DisplayName("a single-day range returns exactly one bucket")
    void singleDay() {
        JsonNode buckets = summary("from=%s&to=%s".formatted(today(), today())).get("buckets");

        assertThat(buckets.size()).isEqualTo(1);
        assertThat(buckets.get(0).get("revenue").decimalValue())
                .isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
    }

    @Test
    @DisplayName("groupBy actually changes the bucketing, rather than being accepted and ignored")
    void groupByIsHonoured() {
        LocalDate today = today();
        String range = "from=%s&to=%s".formatted(today.minusDays(60), today);

        int byDay = summary(range + "&groupBy=day").get("buckets").size();
        int byWeek = summary(range + "&groupBy=week").get("buckets").size();
        int byMonth = summary(range + "&groupBy=month").get("buckets").size();

        // The specific bug this catches: a parameter that is read, validated and
        // then never reaches the SQL. It produces a plausible response every
        // time and only two different requests can tell.
        assertThat(byDay).as("61 days inclusive").isEqualTo(61);
        assertThat(byWeek).as("61 days spans 9 or 10 ISO weeks").isBetween(9, 10);
        assertThat(byMonth).as("61 days spans 3 calendar months").isEqualTo(3);
        assertThat(byDay).isGreaterThan(byWeek);
        assertThat(byWeek).isGreaterThan(byMonth);
    }

    @Test
    @DisplayName("regrouping the same range preserves the totals")
    void regroupingConservesTheTotal() {
        LocalDate today = today();
        String range = "from=%s&to=%s".formatted(today.minusDays(60), today);

        java.math.BigDecimal daily = sumRevenue(summary(range + "&groupBy=day"));
        java.math.BigDecimal weekly = sumRevenue(summary(range + "&groupBy=week"));
        java.math.BigDecimal monthly = sumRevenue(summary(range + "&groupBy=month"));

        // Bucketing must move money between buckets, never create or lose it. A
        // week boundary computed one way for the calendar and another way for
        // the sales would drop whichever days fell in the gap, and every
        // individual bucket would still look entirely reasonable.
        assertThat(daily).isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(weekly).isEqualByComparingTo(daily);
        assertThat(monthly).isEqualByComparingTo(daily);
    }

    private static java.math.BigDecimal sumRevenue(JsonNode body) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (JsonNode bucket : body.get("buckets")) {
            total = total.add(bucket.get("revenue").decimalValue());
        }
        return total;
    }

    @Test
    @DisplayName("a range entirely before the shop opened is all zeroes, not an empty array")
    void quietRange() {
        LocalDate today = today();
        JsonNode buckets = summary("from=%s&to=%s"
                .formatted(today.minusDays(400), today.minusDays(398))).get("buckets");

        assertThat(buckets.size()).isEqualTo(3);
        for (JsonNode bucket : buckets) {
            assertThat(bucket.get("revenue").decimalValue()).isEqualByComparingTo("0.00");
            assertThat(bucket.get("grossProfit").decimalValue()).isEqualByComparingTo("0.00");
        }
    }

    @Test
    @DisplayName("an inverted range is 400, not a silently empty answer")
    void invertedRange() {
        LocalDate today = today();
        HttpTestClient.Response response = http().get(
                "/reports/sales-summary?from=%s&to=%s".formatted(today, today.minusDays(7)),
                owner());

        // An empty list is a true answer to "what happened between March and
        // February" only by accident, and on screen it is indistinguishable from
        // a quiet week.
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).contains("is after");
    }

    @Test
    @DisplayName("an unknown groupBy is 400, and an upper-case one is accepted")
    void groupByValidation() {
        LocalDate today = today();
        String range = "from=%s&to=%s".formatted(today.minusDays(6), today);

        assertThat(http().get("/reports/sales-summary?" + range + "&groupBy=quarter", owner())
                .status()).isEqualTo(400);
        assertThat(http().get("/reports/sales-summary?" + range + "&groupBy=DAY", owner())
                .status()).isEqualTo(200);
    }

    /**
     * The absent case was already a 400. The <em>unparseable</em> case was a
     * <strong>500</strong>, on every typed parameter in the application, until
     * this test found it.
     *
     * <p>Spring MVC's own exceptions reach {@code GlobalExceptionHandler}'s
     * catch-all and are converted by an {@code instanceof ErrorResponse} check.
     * A missing parameter implements that interface and so was answered
     * correctly, which made the whole family look covered;
     * {@code MethodArgumentTypeMismatchException} does not, so a parameter that
     * was present and malformed fell through to "the server broke".
     *
     * <p>Worth the emphasis because of what a 500 tells a client to do: retry.
     * A phone with any retry logic would send the same unparseable date forever
     * against something that can never succeed.
     */
    @Test
    @DisplayName("a missing date is 400, and so is an unparseable one")
    void malformedDates() {
        assertThat(http().get("/reports/sales-summary", owner()).status()).isEqualTo(400);
        assertThat(http().get("/reports/sales-summary?from=" + today(), owner()).status())
                .isEqualTo(400);

        HttpTestClient.Response garbled =
                http().get("/reports/sales-summary?from=not-a-date&to=" + today(), owner());

        assertThat(garbled.status())
                .as("a 500 here tells the client to retry a request that can never work")
                .isEqualTo(400);
        assertThat(garbled.headers().first("Content-Type"))
                .startsWith("application/problem+json");
        assertThat(garbled.json().get("detail").asString())
                .as("naming the parameter is what lets the caller fix it")
                .contains("from");
    }

    @Test
    @DisplayName("another tenant's sales are invisible")
    void isolation() throws SQLException {
        // A second shop with its own history. RLS is the boundary (T2) and
        // nothing in ReportQueries carries a WHERE tenant_id — this is the
        // assertion that the policy, and not a forgotten clause, is doing it.
        ReportFixture other = ReportFixture.create(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), http(),
                (tenantId, userId) -> jwtService
                        .issueAccessToken(tenantId, userId, "owner").value());

        LocalDate today = today();
        String range = "from=%s&to=%s".formatted(today, today);

        java.math.BigDecimal mine = sumRevenue(summary(range));

        String theirToken = jwtService
                .issueAccessToken(other.tenantId, other.userId, "owner").value();
        HttpTestClient.Response theirs =
                http().get("/reports/sales-summary?" + range, theirToken);

        // Both shops are real and both sold today, so this is not the trivially
        // passing "A sees zero" — each sees exactly its own takings, and an
        // isolation failure would show up as a doubled total rather than as an
        // exception (CLAUDE.md §10).
        assertThat(mine).isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(sumRevenue(theirs.json())).isEqualByComparingTo(ReportFixture.EXPECTED_REVENUE);
        assertThat(other.tenantId).isNotEqualTo(shop.tenantId);
    }
}
