package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.inventory.AbstractIntegrationTest;
import com.example.inventory.inventory.StockLedgerService;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.seed.SeedDataRunner;
import com.example.inventory.seed.TenantSeeder;
import com.example.inventory.seed.TenantSeeder.SeededTenant;
import com.example.inventory.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReorderService} end to end over M6's real seed data.
 *
 * <h2>Why stock has to be written down first</h2>
 *
 * <p>M6's generator keeps every product comfortably stocked — that is what makes
 * it realistic history rather than a crisis. So nothing in it is below its
 * reorder point, and a recompute over untouched seed data correctly produces
 * zero recommendations.
 *
 * <p>This fixture therefore writes stock down through the real ledger before
 * recomputing, using {@code adjustment} movements — a stock write-off, which is
 * what a shop would actually record. Deliberately <strong>not</strong> sales:
 * a sale would add to today's demand and shift the very series the forecast is
 * computed from, so the reorder point being tested would move as a side effect
 * of setting up the test. An adjustment moves the balance and leaves the demand
 * history untouched.
 *
 * <p>Written down to just <em>below</em> the reorder point rather than to zero,
 * so the product is short without also being flagged as a stockout — otherwise
 * the censoring rule would start applying to the day this test created.
 */
@DisplayName("Reorder recommendations over M6 seed data")
class ReorderRecommendationSeedDataTest extends AbstractIntegrationTest {

    private static final int WINDOW_WEEKS = SeedDataRunner.WINDOW_WEEKS;

    @Autowired
    private TenantSeeder seeder;

    @Autowired
    private DemandRollupJob rollup;

    @Autowired
    private ReorderService reorderService;

    @Autowired
    private StockLedgerService ledger;

    @Autowired
    @Qualifier("appDataSource")
    private javax.sql.DataSource appDataSource;

    private static SeededTenant tenant;
    private static ReorderService.RecomputeResult result;
    private static boolean prepared;

    @BeforeEach
    void seedRollUpAndRecompute() throws Exception {
        if (prepared) {
            return;
        }
        String tag = UUID.randomUUID().toString().substring(0, 8);
        tenant = seeder.seedTenant("Recommend Bakery", "rec-" + tag, 1L, WINDOW_WEEKS);

        asTenant(() -> {
            rollup.rollUp();

            // Draw two products down below their reorder points. See the class
            // Javadoc for why this is an adjustment and not a sale.
            writeStockDownTo("steady", new BigDecimal("14"));
            writeStockDownTo("seasonal", new BigDecimal("50"));

            return result = reorderService.recomputeAll();
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

    private void writeStockDownTo(String shape, BigDecimal target) {
        UUID productId = product(shape);
        BigDecimal onHand = new JdbcTemplate(appDataSource).queryForObject("""
                SELECT COALESCE(sum(quantity_on_hand), 0) FROM product_stock
                WHERE product_id = ? AND location_id = ?
                """, BigDecimal.class, productId, tenant.locationId());

        BigDecimal delta = target.subtract(onHand);
        if (delta.signum() >= 0) {
            throw new IllegalStateException("Fixture expects " + shape + " to be stocked above "
                    + target + "; found " + onHand);
        }
        ledger.post(new MovementRequest(productId, tenant.locationId(), "adjustment",
                delta, null, null, null, "test write-off",
                java.time.LocalDate.now().atTime(14, 0).atOffset(ZoneOffset.UTC)));
    }

    private UUID product(String shape) {
        return tenant.productIdsByShape().get(shape);
    }

    private List<Map<String, Object>> query(String sql, Object... args) throws Exception {
        return asTenant(() -> new JdbcTemplate(appDataSource).queryForList(sql, args));
    }

    private Map<String, Object> recommendationFor(String shape) throws Exception {
        List<Map<String, Object>> rows = query("""
                SELECT * FROM reorder_recommendations
                WHERE product_id = ? AND status = 'open'
                """, product(shape));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the recompute writes a forecast for every product and recommendations for the short ones")
    void reportWhatTheRecomputeProduced() throws Exception {
        List<Map<String, Object>> recommendations = query("""
                SELECT p.sku, r.quantity_on_hand, r.reorder_point, r.recommended_qty,
                       r.estimated_cost, r.rationale
                FROM reorder_recommendations r
                JOIN products p ON p.id = r.product_id
                WHERE r.status = 'open'
                ORDER BY p.sku
                """);

        StringBuilder report = new StringBuilder("\n=== Recompute over M6 seed data ===\n");
        report.append("forecasts written: ").append(result.forecastsWritten())
                .append(", recommendations: ").append(result.recommendationsWritten())
                .append("\n\n");
        for (Map<String, Object> row : recommendations) {
            report.append(row.get("sku")).append("\n  on hand ").append(row.get("quantity_on_hand"))
                    .append(", reorder point ").append(row.get("reorder_point"))
                    .append(", order ").append(row.get("recommended_qty"))
                    .append(", est. cost ").append(row.get("estimated_cost"))
                    .append("\n  \"").append(row.get("rationale")).append("\"\n\n");
        }
        System.out.println(report);

        assertThat(result.forecastsWritten())
                .as("every product with demand history gets a forecast, including the ones "
                        + "that produce no recommendation — insufficient_data is a forecast")
                .isEqualTo(7);
        assertThat(recommendations)
                .as("the two products written down must appear, and only those")
                .hasSize(2);
    }

    @Test
    @DisplayName("a forecast is stored for every product, with exactly one current per product")
    void forecastsArePersistedAndExactlyOneIsCurrent() throws Exception {
        List<Map<String, Object>> current = query("""
                SELECT product_id, method, avg_daily_demand, reorder_point, confidence
                FROM forecasts WHERE is_current
                """);

        assertThat(current)
                .as("seven shapes, seven current forecasts")
                .hasSize(7);

        // Counted relative to whatever is already there, not against an absolute
        // number: other tests in this class recompute too, and JUnit does not
        // promise an order. An absolute count would pass or fail depending on
        // which test ran first, which is a flake wearing a real failure's
        // clothes.
        int before = query("SELECT id FROM forecasts").size();

        // Re-running must supersede rather than accumulate. forecasts_current_uq
        // would refuse a second current row outright, so a bug there surfaces as
        // a constraint violation — but the count assertion is what proves the
        // old row was cleared rather than the insert being skipped.
        asTenant(() -> reorderService.recomputeAll());

        assertThat(query("SELECT id FROM forecasts WHERE is_current"))
                .as("still exactly one current forecast per product after another run")
                .hasSize(7);
        assertThat(query("SELECT id FROM forecasts"))
                .as("and the superseded rows are KEPT — forecast_accuracy scores past "
                        + "predictions, and a forecast deleted when it stopped being current "
                        + "could never be scored")
                .hasSize(before + 7);
    }

    @Test
    @DisplayName("a second recompute leaves one open recommendation, not two")
    void recomputingDoesNotDuplicateOpenRecommendations() throws Exception {
        asTenant(() -> reorderService.recomputeAll());

        assertThat(query("SELECT id FROM reorder_recommendations WHERE status = 'open'"))
                .as("reorder_recommendations_open_uq permits one open row per "
                        + "product/location, so a recompute has to expire the previous one")
                .hasSize(2);
        assertThat(query("SELECT id FROM reorder_recommendations WHERE status = 'expired'"))
                .as("and the superseded advice is expired rather than deleted — a shop owner "
                        + "who acted on yesterday's number should still see what it said")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the steady seller's recommendation reads as ordinary, actionable advice")
    void theSteadyRecommendationIsClean() throws Exception {
        Map<String, Object> row = recommendationFor("steady");
        String rationale = (String) row.get("rationale");
        System.out.println("\nsteady rationale:\n  " + rationale + "\n");

        assertThat(rationale)
                .as("plain English a shop owner can act on without opening another screen")
                .contains("Golden Wheat")
                .contains("Order about")
                .contains("has been taking about");

        assertThat(rationale)
                .as("and NO seasonal caveat — this product has no cycle, and a warning on "
                        + "every recommendation is one nobody reads")
                .doesNotContain("cannot fully account")
                .doesNotContain("rough guide");

        assertThat((BigDecimal) row.get("recommended_qty"))
                .as("ordering back only to the reorder point would put this product straight "
                        + "back on the list tomorrow, so the quantity covers the horizon too")
                .isGreaterThan((BigDecimal) row.get("reorder_point"));
    }

    @Test
    @DisplayName("the seasonal product's recommendation says, in words, that the pattern is not accounted for")
    void theSeasonalRecommendationCarriesTheCaveat() throws Exception {
        Map<String, Object> row = recommendationFor("seasonal");
        String rationale = (String) row.get("rationale");
        System.out.println("\nseasonal rationale:\n  " + rationale + "\n");

        assertThat(rationale)
                .as("this is the string a shop owner reads before spending money on a product "
                        + "whose reorder point averages its busy and quiet weeks together. A "
                        + "boolean flag nothing renders would protect nobody.")
                .contains("repeat on a regular pattern")
                .contains("cannot fully account for that pattern")
                .contains("rough guide")
                .contains("more than this going into a busy stretch");

        // The comparison that makes the above mean something: the same system,
        // the same run, a different product — and visibly different advice.
        String steadyRationale = (String) recommendationFor("steady").get("rationale");
        assertThat(rationale.length())
                .as("the caveated advice must be substantially longer, i.e. it says something "
                        + "extra rather than merely being worded differently")
                .isGreaterThan(steadyRationale.length() + 150);
    }

    @Test
    @DisplayName("the intermittent product's explanation says the estimate is rough — on real data")
    void lowConfidenceReachesTheProseOnRealData() throws Exception {
        var intermittent = asTenant(() -> reorderService.explainedForecast(
                product("intermittent"), tenant.locationId()));
        var steady = asTenant(() -> reorderService.explainedForecast(
                product("steady"), tenant.locationId()));

        System.out.printf("%nintermittent (confidence %s):%n  %s%n%nsteady (confidence %s):%n  %s%n%n",
                intermittent.forecast().confidence(), intermittent.explanation(),
                steady.forecast().confidence(), steady.explanation());

        assertThat(intermittent.forecast().confidence())
                .as("fixture check: a Croston forecast off ~21 selling days in 212 really is "
                        + "thin, and must measure below the threshold or this test is about "
                        + "the wrong product")
                .isLessThan(ForecastExplainer.LOW_CONFIDENCE_THRESHOLD);
        assertThat(steady.forecast().confidence())
                .as("and the steady seller must measure above it — otherwise the contrast "
                        + "below proves nothing")
                .isGreaterThan(ForecastExplainer.LOW_CONFIDENCE_THRESHOLD);

        assertThat(intermittent.explanation())
                .as("the prose is the whole point of the explanation field. A shop owner "
                        + "reading only this must not come away as sure of it as of the steady "
                        + "seller's, just because both sentences are equally definite.")
                .contains("Treat this as a rough estimate rather than a firm one")
                .contains("only sold on");
        assertThat(steady.explanation())
                .as("and the well-evidenced product is stated plainly, with no hedge")
                .doesNotContain("rough estimate");
    }

    @Test
    @DisplayName("insufficient_data products get an explanation but no recommendation")
    void unreadyProductsAreExplainedNotRecommended() throws Exception {
        for (String shape : List.of("dead", "new")) {
            assertThat(recommendationFor(shape))
                    .as("%s has no reorder point, so recommending a quantity would be inventing "
                            + "one — and a reorder list with invented entries teaches a shop "
                            + "owner to distrust the whole list", shape)
                    .isNull();

            var explained = asTenant(() -> reorderService.explainedForecast(
                    product(shape), tenant.locationId()));
            System.out.printf("%s explanation:%n  %s%n%n", shape, explained.explanation());

            assertThat(explained.forecast().method()).isEqualTo(ForecastMethod.INSUFFICIENT_DATA);
            assertThat(explained.explanation())
                    .as("%s: ADR §6 — explanation is required unconditionally, so the path with "
                            + "no number needs the most careful sentence of the lot", shape)
                    .isNotBlank()
                    .contains("Still learning")
                    .contains("a reorder level would be a guess rather than a forecast");
        }
    }

    @Test
    @DisplayName("a well-stocked product gets a forecast and no recommendation")
    void wellStockedProductsAreNotRecommended() throws Exception {
        // trending and intermittent were never written down, so they remain
        // comfortably above their reorder points.
        for (String shape : List.of("trending", "intermittent")) {
            assertThat(recommendationFor(shape))
                    .as("%s is above its reorder point — a recommendation here would be noise",
                            shape)
                    .isNull();

            assertThat(query("SELECT id FROM forecasts WHERE product_id = ? AND is_current",
                    product(shape)))
                    .as("%s: but it still gets a forecast. No recommendation is not the same "
                            + "as no forecast, and conflating them would hide the products that "
                            + "are simply fine.", shape)
                    .hasSize(1);
        }
    }
}
