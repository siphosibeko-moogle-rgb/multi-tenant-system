package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.inventory.forecasting.ForecastExplainer.Urgency;
import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.forecasting.LeadTimeResolver.LeadTime;
import com.example.inventory.forecasting.LeadTimeResolver.Source;
import com.example.inventory.forecasting.MethodSelector.Selection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sentences a shop owner reads.
 *
 * <p>These assert on wording, which is unusual and deliberate. The seasonality
 * caveat exists to change somebody's ordering decision; a boolean that no
 * rendered string reflects protects nobody, so "the flag is set" is not
 * evidence the caveat works. What matters is that the text a person sees is
 * <em>different</em>, and different in a way they can act on.
 */
@DisplayName("ForecastExplainer")
class ForecastExplainerTest {

    private final ForecastExplainer explainer = new ForecastExplainer();

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();

    private static Selection selection(ForecastMethod method, BigDecimal seasonality) {
        return new Selection(method, 212, 212, 185, new BigDecimal("0.873"),
                new BigDecimal("0.014"), seasonality, 0, 0);
    }

    /** A ready forecast, identical in every respect except the cycle indicator. */
    private static Forecast forecast(BigDecimal seasonalityIndicator) {
        Selection selection = selection(ForecastMethod.MOVING_AVERAGE, seasonalityIndicator);
        return new Forecast(PRODUCT, LOCATION, selection,
                new BigDecimal("2.6510"), new BigDecimal("1.2590"), 30,
                new BigDecimal("79.530"), new BigDecimal("40.000"), new BigDecimal("15.09"),
                LocalDate.of(2026, 9, 7), new BigDecimal("18.772"), new BigDecimal("4.760"),
                new LeadTime(SUPPLIER, "Golden Wheat", new BigDecimal("5.29"), Source.OBSERVED, 7),
                new BigDecimal("0.950"), new BigDecimal("0.700"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31),
                selection.isSeasonalitySuspected());
    }

    private static Forecast clean() {
        return forecast(new BigDecimal("0.069"));
    }

    private static Forecast seasonal() {
        return forecast(new BigDecimal("0.908"));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the seasonal caveat")
    class SeasonalCaveat {

        @Test
        @DisplayName("fixture check: the two forecasts differ ONLY in the cycle indicator")
        void theOnlyDifferenceIsTheIndicator() {
            // Without this, a difference in the text below could come from any
            // of the eighteen fields rather than from the caveat.
            assertThat(clean().avgDailyDemand()).isEqualByComparingTo(seasonal().avgDailyDemand());
            assertThat(clean().quantityOnHand()).isEqualByComparingTo(seasonal().quantityOnHand());
            assertThat(clean().reorderPoint()).isEqualByComparingTo(seasonal().reorderPoint());
            assertThat(clean().daysOfCover()).isEqualByComparingTo(seasonal().daysOfCover());

            assertThat(clean().seasonalitySuspected()).isFalse();
            assertThat(seasonal().seasonalitySuspected()).isTrue();
        }

        @Test
        @DisplayName("the explanation says, in words, that a repeating pattern is not accounted for")
        void theExplanationCarriesTheCaveatInEnglish() {
            String cleanText = explainer.explain(clean(), "Steady Seller Bread");
            String seasonalText = explainer.explain(seasonal(), "Weekend Party Platter");

            assertThat(seasonalText)
                    .as("a shop owner must be able to read this as 'we cannot fully account "
                            + "for a seasonal pattern here'. Actual text: %s", seasonalText)
                    .contains("repeat on a regular pattern")
                    .contains("averages the busy and quiet stretches together")
                    .contains("cannot fully account for that pattern")
                    .contains("rough guide");

            assertThat(cleanText)
                    .as("and an ordinary product must NOT carry any of it — a caveat on every "
                            + "product is a caveat nobody reads")
                    .doesNotContain("pattern")
                    .doesNotContain("rough guide");
        }

        @Test
        @DisplayName("the two explanations differ by a substantial sentence, not by a token")
        void theDifferenceIsMeaningful() {
            String cleanText = explainer.explain(clean(), "Bread");
            String seasonalText = explainer.explain(seasonal(), "Bread");

            assertThat(seasonalText).isNotEqualTo(cleanText);
            assertThat(seasonalText)
                    .as("the caveat is additive: everything the clean explanation says is still "
                            + "said, so the caveat qualifies the number rather than replacing it")
                    .startsWith(cleanText);

            String added = seasonalText.substring(cleanText.length());
            assertThat(added.length())
                    .as("a real sentence, not a marker. A '(seasonal)' suffix would technically "
                            + "differ and would tell a shop owner nothing. Added: %s", added)
                    .isGreaterThan(150);
            assertThat(added)
                    .as("and it must tell them what to DO about it, not merely that a problem "
                            + "exists")
                    .contains("more than this going into a busy stretch");
        }

        @Test
        @DisplayName("the recommendation rationale carries it too, not only the forecast")
        void theRecommendationAlsoCarriesTheCaveat() {
            String cleanRationale = explainer.explainRecommendation(
                    clean(), new BigDecimal("58"), Urgency.NORMAL, "Golden Wheat");
            String seasonalRationale = explainer.explainRecommendation(
                    seasonal(), new BigDecimal("58"), Urgency.NORMAL, "Golden Wheat");

            assertThat(seasonalRationale)
                    .as("the reorder list is where somebody actually decides to spend money, "
                            + "so it is the more important of the two places to say this")
                    .contains("cannot fully account for that pattern");
            assertThat(cleanRationale).doesNotContain("cannot fully account");
        }
    }

    @Nested
    @DisplayName("a ready forecast")
    class ReadyForecast {

        @Test
        @DisplayName("follows ADR §6's template — weekly rate, stock, cover, lead time")
        void theTemplateIsTheAdrs() {
            String text = explainer.explain(clean(), "Steady Seller Bread");

            assertThat(text)
                    .as("2.651/day x 7 = 18.6, friendly-rounded to 19. Actual: %s", text)
                    .startsWith("You sell about 19 a week.")
                    .contains("40 in stock")
                    .contains("covers roughly 15 days")
                    .contains("Reorder when you get down to about 19");
        }

        @Test
        @DisplayName("says the lead time is MEASURED when it is, with the sample size")
        void anObservedLeadTimeIsLabelledAsMeasured() {
            String text = explainer.explain(clean(), "Bread");

            assertThat(text)
                    .as("ADR §6: this sentence is the one place a shop owner sees the figure, "
                            + "so it cannot silently mix measured with promised")
                    .contains("has been taking about 5.3 days")
                    .contains("measured over 7 deliveries");
        }

        @Test
        @DisplayName("says the lead time is the supplier's CLAIM when it is not yet measured")
        void aPromisedLeadTimeIsLabelledAsAClaim() {
            Forecast promised = new Forecast(PRODUCT, LOCATION,
                    selection(ForecastMethod.MOVING_AVERAGE, new BigDecimal("0.05")),
                    new BigDecimal("3.1980"), new BigDecimal("3.6120"), 30,
                    new BigDecimal("95.940"), new BigDecimal("40.000"), new BigDecimal("12.51"),
                    LocalDate.of(2026, 9, 4), new BigDecimal("94.383"), new BigDecimal("27.225"),
                    new LeadTime(SUPPLIER, "Riverbend", new BigDecimal("21"),
                            Source.PROMISED_BY_SUPPLIER, 2),
                    new BigDecimal("0.950"), new BigDecimal("0.400"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31), false);

            String text = explainer.explain(promised, "Party Platter");

            assertThat(text)
                    .as("the reader is entitled to know this is what the supplier says rather "
                            + "than what they do. Actual: %s", text)
                    .contains("quotes 21 days")
                    .contains("their stated time")
                    .contains("not had enough deliveries yet to measure");
            assertThat(text)
                    .as("and it must not claim measurement it does not have")
                    .doesNotContain("has been taking");
        }
    }

    @Nested
    @DisplayName("insufficient_data still gets a real explanation")
    class StillLearning {

        private Forecast unready(int historyDays, int nonZeroDays) {
            int shortOfHistory = Math.max(0, 42 - historyDays);
            int shortOfCount = Math.max(0, 10 - nonZeroDays);
            Selection selection = new Selection(ForecastMethod.INSUFFICIENT_DATA,
                    historyDays, historyDays, nonZeroDays, new BigDecimal("0.4"),
                    BigDecimal.ZERO, BigDecimal.ZERO, shortOfHistory, shortOfCount);
            return new Forecast(PRODUCT, LOCATION, selection, BigDecimal.ZERO, BigDecimal.ZERO,
                    30, BigDecimal.ZERO, new BigDecimal("12"), null, null, null, null, null,
                    new BigDecimal("0.950"), null, LocalDate.of(2026, 7, 20),
                    LocalDate.of(2026, 7, 31), false);
        }

        @Test
        @DisplayName("a brand-new product is told what is missing and roughly how much")
        void aNewProductGetsARealSentence() {
            String text = explainer.explain(unready(11, 5), "Brand New Sourdough");

            assertThat(text)
                    .as("ADR §6: explanation is required unconditionally, so this path needs a "
                            + "real sentence rather than an empty string waiting for the "
                            + "confident branch. Actual: %s", text)
                    .isNotBlank()
                    .contains("Still learning Brand New Sourdough")
                    .contains("11 days of history")
                    .contains("31 more needed");
            assertThat(text)
                    .as("and it says why there is no number, rather than leaving a blank where "
                            + "one should be")
                    .contains("a reorder level would be a guess rather than a forecast");
        }

        @Test
        @DisplayName("a slow mover is told in SELLING days, not calendar days")
        void theNonZeroShortfallIsPhrasedAsSellingDays() {
            // 200 calendar days, only 4 with a sale. The calendar floor is
            // satisfied; the non-zero floor is not.
            String text = explainer.explain(unready(200, 4), "Intermittent Spice Mix");

            assertThat(text)
                    .as("quoting '6 more days' flatly would promise a date this cannot keep: "
                            + "six more SELLING days at one sale a fortnight is three months "
                            + "away. Actual: %s", text)
                    .contains("only sold on 4 days")
                    .contains("6 more days with a sale are needed");
        }

        @Test
        @DisplayName("a product with no history at all still gets a sentence")
        void noHistoryIsStillExplained() {
            String text = explainer.explain(unready(0, 0), "Untouched Item");

            assertThat(text).isNotBlank().contains("no sales history recorded yet");
        }
    }

    @Nested
    @DisplayName("urgency wording")
    class UrgencyWording {

        @Test
        @DisplayName("each urgency opens with a different, plain statement of the problem")
        void theThreeUrgenciesReadDifferently() {
            String critical = explainer.explainRecommendation(
                    clean(), new BigDecimal("58"), Urgency.CRITICAL, "Golden Wheat");
            String high = explainer.explainRecommendation(
                    clean(), new BigDecimal("58"), Urgency.HIGH, "Golden Wheat");
            String normal = explainer.explainRecommendation(
                    clean(), new BigDecimal("58"), Urgency.NORMAL, "Golden Wheat");

            assertThat(critical).startsWith("This will run out before a new order can arrive.");
            assertThat(high).startsWith("This is running low and needs ordering soon.");
            assertThat(normal).startsWith("Stock has dropped to the reorder level.");

            assertThat(java.util.Set.of(critical, high, normal))
                    .as("three distinct strings — an urgency that reads identically to another "
                            + "is not an urgency")
                    .hasSize(3);
        }

        @Test
        @DisplayName("being already out of stock says so, rather than predicting it")
        void alreadyOutIsStatedAsFact() {
            Forecast out = new Forecast(PRODUCT, LOCATION,
                    selection(ForecastMethod.MOVING_AVERAGE, new BigDecimal("0.05")),
                    new BigDecimal("2.6510"), new BigDecimal("1.2590"), 30,
                    new BigDecimal("79.530"), BigDecimal.ZERO, new BigDecimal("0.00"),
                    LocalDate.of(2026, 8, 23), new BigDecimal("18.772"), new BigDecimal("4.760"),
                    new LeadTime(SUPPLIER, "Golden Wheat", new BigDecimal("5.29"),
                            Source.OBSERVED, 7),
                    new BigDecimal("0.950"), new BigDecimal("0.700"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31), false);

            assertThat(explainer.explainRecommendation(
                    out, new BigDecimal("98"), Urgency.CRITICAL, "Golden Wheat"))
                    .as("'will run out' is wrong when it already has")
                    .startsWith("You are out of stock now.");
        }

        @Test
        @DisplayName("the rationale names the supplier and the quantity to order")
        void theRationaleIsActionable() {
            String text = explainer.explainRecommendation(
                    clean(), new BigDecimal("58"), Urgency.NORMAL, "Golden Wheat");

            assertThat(text)
                    .as("somebody has to be able to act on this without opening another screen. "
                            + "Actual: %s", text)
                    .contains("Golden Wheat")
                    .contains("Order about 58")
                    .contains("next 30 days");
        }
    }
}
