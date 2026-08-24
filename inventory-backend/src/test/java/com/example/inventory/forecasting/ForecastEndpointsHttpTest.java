package com.example.inventory.forecasting;

import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;
import com.example.inventory.seed.SeedDataRunner;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five forecasting endpoints, over real HTTP.
 *
 * <p>CLAUDE.md §5: a criterion written about a response is asserted over HTTP —
 * status code, content type, and every field the contract names. Service-level
 * coverage stops exactly where the interesting part begins, and M2 proved that
 * expensively when an oversell's {@code available} was correct in the service
 * and always zero on the wire.
 *
 * <p>Runs against M6's real seed data so the bodies contain real forecasts
 * rather than fixture placeholders — the explanation strings asserted here are
 * the ones a shop owner would actually read.
 */
@DisplayName("Forecasting endpoints")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForecastEndpointsHttpTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private com.example.inventory.inventory.StockLedgerService ledger;

    private static SeededTenant tenant;
    private static boolean prepared;

    @BeforeEach
    void seedAndRecompute() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Endpoint Bakery", "ep-" + tag, 1L,
                SeedDataRunner.WINDOW_WEEKS);

        asTenant(() -> {
            rollup.rollUp();
            // Draw the steady seller below its reorder point so the
            // recommendation endpoints have something real to return. An
            // adjustment rather than a sale, so the demand series the forecast
            // is built from does not move as a side effect of the fixture.
            java.math.BigDecimal onHand = new org.springframework.jdbc.core.JdbcTemplate(
                    appDataSource()).queryForObject("""
                            SELECT COALESCE(sum(quantity_on_hand), 0) FROM product_stock
                            WHERE product_id = ? AND location_id = ?
                            """, java.math.BigDecimal.class,
                    product("steady"), tenant.locationId());
            ledger.post(new com.example.inventory.inventory.StockLedgerService.MovementRequest(
                    product("steady"), tenant.locationId(), "adjustment",
                    new java.math.BigDecimal("14").subtract(onHand), null, null, null,
                    "endpoint fixture write-off",
                    java.time.LocalDate.now().atTime(14, 0)
                            .atOffset(java.time.ZoneOffset.UTC)));
            return reorderService.recomputeAll();
        });
        prepared = true;
    }

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private javax.sql.DataSource appDataSource() {
        return appDataSource;
    }

    private <T> T asTenant(Callable<T> work) throws Exception {
        TenantContext.bind(new TenantContext.TenantIdentity(
                tenant.tenantId(), tenant.ownerId(), "owner"));
        try {
            return work.call();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID product(String shape) {
        return tenant.productIdsByShape().get(shape);
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    /** A real signed token for this tenant carrying the given role. */
    private String tokenFor(String role) {
        return jwtService.issueAccessToken(tenant.tenantId(), tenant.ownerId(), role).value();
    }

    private String owner() {
        return tokenFor("owner");
    }

    /**
     * Restores at least one open recommendation before a test that needs one.
     *
     * <p>The dismiss tests consume the open row, and JUnit promises no ordering,
     * so a test asserting "there is an open recommendation" passes or fails
     * depending on which ran first — a flake wearing a real failure's clothes.
     * A recompute expires whatever is open and rebuilds it from current stock,
     * which is idempotent and makes each test independent of the others.
     */
    private void ensureOpenRecommendations() throws Exception {
        asTenant(() -> reorderService.recomputeAll());
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("GET /forecasts")
    class ListForecasts {

        @Test
        @DisplayName("returns 200, application/json, and a page of forecasts")
        void listReturnsAPage() {
            HttpTestClient.Response response = http().get("/forecasts", owner());

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers().first("Content-Type"))
                    .as("the contract declares application/json")
                    .startsWith("application/json");

            JsonNode body = response.json();
            assertThat(body.has("items"))
                    .as("Page declares items required")
                    .isTrue();
            assertThat(body.get("items").size())
                    .as("seven seeded shapes were all recomputed")
                    .isEqualTo(7);
            assertThat(body.has("nextCursor"))
                    .as("nextCursor is declared nullable, which means present-and-null rather "
                            + "than absent — an absent field and a null one break a generated "
                            + "client differently (CLAUDE.md §15)")
                    .isTrue();
        }

        @Test
        @DisplayName("every field the contract marks required is present and non-null")
        void requiredFieldsArePresent() {
            JsonNode items = http().get("/forecasts", owner()).json().get("items");

            for (JsonNode item : items) {
                for (String required : new String[]{"productId", "locationId", "method",
                        "generatedAt", "historyDays", "avgDailyDemand", "demandStddev",
                        "horizonDays", "forecastQty", "serviceLevel"}) {
                    assertThat(item.has(required))
                            .as("%s must be PRESENT — Jackson reports an absent field and a "
                                    + "null one identically through asDouble(), which is the "
                                    + "trap CLAUDE.md §5 names", required)
                            .isTrue();
                    assertThat(item.get(required).isNull())
                            .as("%s must be non-null", required)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("horizonDays rescales forecastQty rather than being ignored")
        void theHorizonParameterIsHonoured() {
            JsonNode atThirty = http().get("/forecasts?horizonDays=30", owner())
                    .json().get("items");
            JsonNode atSixty = http().get("/forecasts?horizonDays=60", owner())
                    .json().get("items");

            JsonNode thirty = forecastFor(atThirty, product("steady"));
            JsonNode sixty = forecastFor(atSixty, product("steady"));

            assertThat(thirty.get("horizonDays").asInt()).isEqualTo(30);
            assertThat(sixty.get("horizonDays").asInt()).isEqualTo(60);
            assertThat(sixty.get("forecastQty").asDouble())
                    .as("twice the horizon, twice the quantity — the daily rate is the model's "
                            + "output and the horizon is a multiplier on it. Silently dropping "
                            + "a declared parameter only surfaces when a client trusts it.")
                    .isCloseTo(thirty.get("forecastQty").asDouble() * 2,
                            org.assertj.core.data.Offset.offset(0.01));
            assertThat(sixty.get("avgDailyDemand").asDouble())
                    .as("and the daily rate itself is unchanged — asking for a longer horizon "
                            + "must not appear to re-model the product")
                    .isEqualTo(thirty.get("avgDailyDemand").asDouble());
        }

        private JsonNode forecastFor(JsonNode items, UUID productId) {
            for (JsonNode item : items) {
                if (item.get("productId").asString().equals(productId.toString())) {
                    return item;
                }
            }
            throw new AssertionError("no forecast for " + productId);
        }
    }

    @Nested
    @DisplayName("GET /products/{id}/forecast")
    class ProductForecast {

        @Test
        @DisplayName("returns the forecast, its explanation, and history only when asked")
        void detailCarriesTheExplanation() {
            HttpTestClient.Response response = http().get(
                    "/products/" + product("steady") + "/forecast", owner());

            assertThat(response.status()).isEqualTo(200);
            JsonNode body = response.json();

            assertThat(body.has("explanation")).isTrue();
            assertThat(body.get("explanation").asString())
                    .as("ForecastDetail marks explanation required unconditionally — the whole "
                            + "point of the field is that a person reads it")
                    .isNotBlank()
                    .contains("You sell about");

            assertThat(body.has("history"))
                    .as("history is required too, so it is present-and-empty rather than "
                            + "absent when includeHistory is false — the contract's two "
                            + "statements cannot both be honoured by omitting it")
                    .isTrue();
            assertThat(body.get("history").size()).isZero();
        }

        @Test
        @DisplayName("includeHistory=true returns the demand series, zero-demand days included")
        void historyIsReturnedWhenRequested() {
            JsonNode body = http().get(
                    "/products/" + product("steady") + "/forecast?includeHistory=true", owner())
                    .json();

            JsonNode history = body.get("history");
            assertThat(history.size())
                    .as("30 weeks of daily rows")
                    .isGreaterThan(200);

            boolean sawZero = false;
            for (JsonNode point : history) {
                assertThat(point.has("day")).isTrue();
                assertThat(point.has("unitsSold")).isTrue();
                assertThat(point.has("hadStockout")).isTrue();
                if (point.get("unitsSold").asDouble() == 0.0) {
                    sawZero = true;
                }
            }
            assertThat(sawZero)
                    .as("the contract's own description says zero-demand days are included on "
                            + "purpose, and this is the endpoint that would reveal a rollup "
                            + "that dropped them")
                    .isTrue();
        }

        @Test
        @DisplayName("an insufficient_data product returns nulls and a 'still learning' sentence")
        void anUnreadyProductIsHonestOverHttp() {
            JsonNode body = http().get(
                    "/products/" + product("dead") + "/forecast", owner()).json();

            assertThat(body.get("method").asString()).isEqualTo("insufficient_data");
            assertThat(body.get("projectedStockoutOn").isNull())
                    .as("the contract's description of this endpoint asks for a null here "
                            + "rather than a confident number")
                    .isTrue();
            assertThat(body.get("reorderPoint").isNull()).isTrue();
            assertThat(body.get("explanation").asString())
                    .as("and a real sentence saying why — ADR §6")
                    .contains("Still learning")
                    .contains("guess rather than a forecast");
        }

        @Test
        @DisplayName("the seasonal caveat reaches the wire, not just the service")
        void theSeasonalCaveatIsInTheResponseBody() {
            String seasonal = http().get(
                    "/products/" + product("seasonal") + "/forecast", owner())
                    .json().get("explanation").asString();
            String steady = http().get(
                    "/products/" + product("steady") + "/forecast", owner())
                    .json().get("explanation").asString();

            assertThat(seasonal)
                    .as("a caveat that stops at the service boundary protects nobody. Actual: %s",
                            seasonal)
                    .contains("cannot fully account for that pattern");
            assertThat(steady).doesNotContain("cannot fully account");
        }

        @Test
        @DisplayName("another tenant's product is 404, not 403")
        void aForeignProductIsNotFound() throws Exception {
            String otherTag = UUID.randomUUID().toString().substring(0, 8);
            SeededTenant other = seeder.seedTenant("Other Shop", "other-" + otherTag, 2L, 8);

            assertThat(http().get("/products/" + other.productIdsByShape().get("steady")
                    + "/forecast", owner()).status())
                    .as("T8: a 403 would confirm the id exists. RLS makes another tenant's "
                            + "product indistinguishable from one that never existed, so this "
                            + "needs no branch to get right — but it does need asserting.")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an unknown product id is 404 with a problem body")
        void anUnknownProductIsNotFound() {
            HttpTestClient.Response response = http().get(
                    "/products/" + UUID.randomUUID() + "/forecast", owner());

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.headers().first("Content-Type"))
                    .as("RFC 9457 problem details, never a bare string (CLAUDE.md §4)")
                    .startsWith("application/problem+json");
            assertThat(response.json().has("type")).isTrue();
        }
    }

    @Nested
    @DisplayName("POST /forecasts/recompute")
    class Recompute {

        @Test
        @DisplayName("returns 202 with a jobId and a product count")
        void recomputeIsAccepted() {
            HttpTestClient.Response response = http().postWithToken(
                    "/forecasts/recompute", "{}", owner());

            assertThat(response.status())
                    .as("the contract declares 202")
                    .isEqualTo(202);

            JsonNode body = response.json();
            assertThat(body.has("jobId")).isTrue();
            assertThat(body.get("jobId").isNull()).isFalse();
            assertThat(body.has("queuedProducts")).isTrue();
            assertThat(body.get("queuedProducts").asInt())
                    .as("seven seeded shapes")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("a body omitting productIds recomputes the whole catalogue")
        void anEmptyBodyIsFine() {
            // The contract's requestBody is not marked required and its
            // description says "Omit to recompute the whole catalog", so an
            // empty object must be accepted rather than 400.
            HttpTestClient.Response response = http().postWithToken(
                    "/forecasts/recompute", "{\"productIds\":null}", owner());

            assertThat(response.status()).isEqualTo(202);
            assertThat(response.json().get("queuedProducts").asInt())
                    .as("the whole catalogue, not nothing")
                    .isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("GET /reorder-recommendations")
    class Recommendations {

        @Test
        @DisplayName("returns the open recommendations with every required field")
        void listReturnsRecommendations() throws Exception {
            ensureOpenRecommendations();
            HttpTestClient.Response response = http().get("/reorder-recommendations", owner());

            assertThat(response.status()).isEqualTo(200);
            JsonNode items = response.json().get("items");
            assertThat(items.size())
                    .as("the steady seller was written down below its reorder point")
                    .isGreaterThan(0);

            for (JsonNode item : items) {
                for (String required : new String[]{"id", "productId", "sku", "name",
                        "locationId", "quantityOnHand", "reorderPoint", "recommendedQty",
                        "urgency", "rationale", "status", "createdAt"}) {
                    assertThat(item.has(required)).as("%s present", required).isTrue();
                    assertThat(item.get(required).isNull()).as("%s non-null", required).isFalse();
                }
                assertThat(item.get("urgency").asString())
                        .isIn("critical", "high", "normal");
                assertThat(item.get("status").asString()).isEqualTo("open");
                assertThat(item.get("rationale").asString())
                        .as("the rationale is the reason a person acts on this row")
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("the status filter is honoured")
        void statusFilters() throws Exception {
            ensureOpenRecommendations();

            // Asserted as a property of the filter rather than as a row count.
            // Counting depends on whether the dismiss tests have run yet, and
            // JUnit promises no ordering — "nothing has been dismissed yet" is
            // true or false depending on which test went first, which is a
            // flake, not a test.
            JsonNode open = http().get("/reorder-recommendations?status=open", owner())
                    .json().get("items");
            JsonNode expired = http().get("/reorder-recommendations?status=expired", owner())
                    .json().get("items");

            assertThat(open.size())
                    .as("the steady seller is below its reorder point, so something is open")
                    .isGreaterThan(0);
            for (JsonNode item : open) {
                assertThat(item.get("status").asString()).isEqualTo("open");
            }
            for (JsonNode item : expired) {
                assertThat(item.get("status").asString())
                        .as("a filter that ignored the parameter would return open rows here")
                        .isEqualTo("expired");
            }
            assertThat(expired.size())
                    .as("recomputing expires the previous advice rather than deleting it, so "
                            + "by now there is some — and this is the positive twin that stops "
                            + "the loop above passing over an empty list")
                    .isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("POST /reorder-recommendations/{id}/dismiss")
    class Dismiss {

        @Test
        @DisplayName("dismissing returns 204 and removes the row from the open list")
        void dismissWorks() throws Exception {
            ensureOpenRecommendations();
            JsonNode open = http().get("/reorder-recommendations", owner()).json().get("items");
            String id = open.get(0).get("id").asString();

            HttpTestClient.Response response = http().postWithToken(
                    "/reorder-recommendations/" + id + "/dismiss",
                    "{\"reason\":\"already ordered by phone\"}", owner());

            assertThat(response.status())
                    .as("the contract declares 204")
                    .isEqualTo(204);
            assertThat(response.body()).isEmpty();

            JsonNode stillOpen = http().get("/reorder-recommendations", owner())
                    .json().get("items");
            for (JsonNode item : stillOpen) {
                assertThat(item.get("id").asString())
                        .as("a dismissed recommendation must leave the open list, or the shop "
                                + "owner keeps being told to order something they decided not to")
                        .isNotEqualTo(id);
            }

            JsonNode dismissed = http().get("/reorder-recommendations?status=dismissed", owner())
                    .json().get("items");
            assertThat(dismissed.size())
                    .as("and it is still there under its new status — the advice was genuinely "
                            + "given and the decision genuinely made")
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("dismissing an unknown id is 404")
        void dismissingNothingIsNotFound() {
            assertThat(http().postWithToken(
                    "/reorder-recommendations/" + UUID.randomUUID() + "/dismiss", "{}", owner())
                    .status())
                    .isEqualTo(404);
        }
    }
}
