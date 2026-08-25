package com.example.inventory.inventory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.auth.HttpTestClient;
import com.example.inventory.auth.JwtService;
import com.example.inventory.forecasting.ReorderService;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /products} and {@code GET /inventory} agree about {@code stockState}.
 *
 * <h2>Why this is not covered by StockStateTest</h2>
 *
 * <p>{@code StockStateTest} compares the CASE in {@code v_stock_status} with the
 * Java method that mirrors it for the per-product reads, across the interesting
 * quantity/threshold combinations. Those two have always agreed — and the
 * endpoints still disagreed, because what diverged was not the logic but its
 * <strong>input</strong>.
 *
 * <p>The view coalesces the product's reorder point with the CURRENT FORECAST's;
 * the catalogue query selected {@code p.reorder_point} alone. From the moment M7
 * started writing forecast reorder points, a product with no threshold of its
 * own but a forecast that had computed one came back {@code ok} from one
 * endpoint and {@code reorder} from the other, at the same instant, for the same
 * product.
 *
 * <p>A test that compares two expressions cannot see that. This one compares two
 * HTTP responses, which is the only level at which the disagreement exists —
 * and it needs a forecast to exist first, which is why it recomputes rather than
 * just seeding.
 */
@DisplayName("stockState agrees across /products and /inventory")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockStateAgreementTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private UUID product(String shape) {
        return tenant.productIdsByShape().get(shape);
    }

    private static SeededTenant tenant;
    private static boolean prepared;

    @BeforeEach
    void seedAndRecompute() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Agreement Bakery", "agree-" + tag, 1L, 30);

        asTenant(() -> {
            // Recompute so forecasts — and their reorder points — exist. Without
            // this the two endpoints agree trivially, because there is no
            // forecast figure for one to pick up and the other to miss.
            reorderService.recomputeAll();

            // And then put a product BELOW that forecast reorder point.
            //
            // This is the part the fixture cannot do without: M6 keeps every
            // product comfortably stocked, so on untouched seed data both
            // endpoints answer "ok" for everything and the comparison passes
            // whether or not the bug is present. Verified by mutation — with the
            // COALESCE removed from PRODUCT_COLUMNS, this class was still green
            // until this write-down was added.
            //
            // An adjustment rather than a sale, so the demand series the
            // forecast is built from does not move underneath the test.
            BigDecimal reorderPoint = new org.springframework.jdbc.core.JdbcTemplate(appDataSource)
                    .queryForObject("""
                            SELECT reorder_point FROM forecasts
                            WHERE product_id = ? AND is_current
                            """, BigDecimal.class, product("steady"));
            BigDecimal onHand = new org.springframework.jdbc.core.JdbcTemplate(appDataSource)
                    .queryForObject("""
                            SELECT COALESCE(sum(quantity_on_hand), 0) FROM product_stock
                            WHERE product_id = ? AND location_id = ?
                            """, BigDecimal.class, product("steady"), tenant.locationId());

            BigDecimal target = reorderPoint.subtract(BigDecimal.ONE).max(BigDecimal.ONE);
            ledger.post(new StockLedgerService.MovementRequest(
                    product("steady"), tenant.locationId(), "adjustment",
                    target.subtract(onHand), null, null, null, "agreement fixture write-off",
                    java.time.LocalDate.now().atTime(14, 0).atOffset(java.time.ZoneOffset.UTC)));
            return null;
        });
        prepared = true;
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

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private String owner() {
        return jwtService.issueAccessToken(tenant.tenantId(), tenant.ownerId(), "owner").value();
    }

    @Test
    @DisplayName("every product reports the same state from both endpoints")
    void theTwoEndpointsAgree() {
        Map<String, String> fromProducts = new HashMap<>();
        for (JsonNode item : http().get("/products?limit=200", owner()).json().get("items")) {
            fromProducts.put(item.get("sku").asString(), item.get("stockState").asString());
        }

        Map<String, String> fromInventory = new HashMap<>();
        for (JsonNode item : http().get("/inventory?limit=200", owner()).json().get("items")) {
            // /inventory is per location; this fixture seeds one, so a single
            // row per product. Asserted below rather than assumed.
            assertThat(fromInventory.put(item.get("sku").asString(),
                    item.get("stockState").asString()))
                    .as("fixture check: one location, so no product should appear twice — "
                            + "otherwise the comparison below is against an arbitrary row")
                    .isNull();
        }

        assertThat(fromProducts)
                .as("fixture check: the catalogue must actually have products, or the "
                        + "comparison below passes over an empty map")
                .isNotEmpty();

        for (Map.Entry<String, String> entry : fromInventory.entrySet()) {
            assertThat(fromProducts.get(entry.getKey()))
                    .as("%s: /products and /inventory must not disagree about the same "
                            + "product at the same moment. On screen that is the Stock list "
                            + "calling a product fine while the reorder list says to order it, "
                            + "with no way for the shop owner to tell which is right.",
                            entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("a product below its FORECAST reorder point reads 'reorder' from /products too")
    void theForecastReorderPointReachesTheCatalogue() throws Exception {
        // The concrete case that was broken: no product-level reorder point, a
        // forecast that computed one, and stock below it.
        JsonNode inventory = http().get("/inventory?limit=200", owner()).json().get("items");

        String skuBelowForecastPoint = null;
        for (JsonNode item : inventory) {
            JsonNode reorderPoint = item.get("reorderPoint");
            if (!reorderPoint.isNull()
                    && item.get("quantityOnHand").asDouble() <= reorderPoint.asDouble()) {
                skuBelowForecastPoint = item.get("sku").asString();
                break;
            }
        }

        if (skuBelowForecastPoint == null) {
            // M6 keeps everything well stocked, so this is the ordinary case and
            // not a failure. theTwoEndpointsAgree still covers the general
            // property; there is simply no below-threshold product to point at.
            return;
        }

        for (JsonNode item : http().get("/products?limit=200", owner()).json().get("items")) {
            if (item.get("sku").asString().equals(skuBelowForecastPoint)) {
                assertThat(item.get("stockState").asString())
                        .as("%s is at or below the reorder point the FORECAST computed, so the "
                                + "catalogue must say so too — reading p.reorder_point alone "
                                + "reports 'ok' for every product whose only threshold came "
                                + "from M7", skuBelowForecastPoint)
                        .isEqualTo("reorder");
            }
        }
    }
}
