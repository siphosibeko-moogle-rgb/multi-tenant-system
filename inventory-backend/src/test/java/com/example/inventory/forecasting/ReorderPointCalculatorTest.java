package com.example.inventory.forecasting;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.inventory.forecasting.ReorderPointCalculator.ReorderPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * ADR §1's formula, on numbers small enough to check by hand.
 *
 * <p>The properties matter more than any single value, so most of these assert
 * how the number <em>moves</em> — with the spread, with the lead time, with the
 * service level. A formula can produce a plausible figure for one input and be
 * wrong everywhere else; a formula that responds correctly to each input is much
 * harder to get accidentally right.
 */
@DisplayName("ReorderPointCalculator")
class ReorderPointCalculatorTest {

    private final ReorderPointCalculator calculator = new ReorderPointCalculator();

    private static final BigDecimal SERVICE_95 = new BigDecimal("0.950");

    @Nested
    @DisplayName("the formula")
    class Formula {

        @Test
        @DisplayName("worked by hand: 3/day, stddev 2, 9-day lead, 95% service")
        void theWorkedExample() {
            // lead time demand = 3 x 9                     = 27
            // safety stock     = 1.645 x 2 x sqrt(9) = 1.645 x 6 = 9.87
            // reorder point                                = 36.87
            ReorderPoint result = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("9"), SERVICE_95);

            assertThat(result.leadTimeDemand()).isEqualByComparingTo("27");
            assertThat(result.safetyStock()).isEqualByComparingTo("9.870");
            assertThat(result.reorderPoint()).isEqualByComparingTo("36.870");
        }

        @Test
        @DisplayName("safety stock grows with the SPREAD, not with demand")
        void safetyStockTracksUncertaintyNotVolume() {
            // Two products with identical average demand and lead time. One
            // sells like clockwork, the other lurches. ADR §1 rules out
            // "a percentage of lead-time demand" precisely because it would
            // give these two the same buffer.
            ReorderPoint steady = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("0.5"), new BigDecimal("9"), SERVICE_95);
            ReorderPoint erratic = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("4"), new BigDecimal("9"), SERVICE_95);

            assertThat(steady.leadTimeDemand())
                    .as("same demand, same lead time — the non-buffer half is identical")
                    .isEqualByComparingTo(erratic.leadTimeDemand());
            assertThat(erratic.safetyStock())
                    .as("but the erratic product must be given a visibly bigger buffer. "
                            + "Steady %s, erratic %s.", steady.safetyStock(), erratic.safetyStock())
                    .isGreaterThan(steady.safetyStock());
        }

        @Test
        @DisplayName("a perfectly predictable product needs no safety stock at all")
        void zeroSpreadMeansZeroBuffer() {
            ReorderPoint result = calculator.calculate(
                    new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("9"), SERVICE_95);

            assertThat(result.safetyStock())
                    .as("nothing varies, so there is nothing to buffer against")
                    .isEqualByComparingTo("0");
            assertThat(result.reorderPoint())
                    .as("and the reorder point is exactly the lead-time demand")
                    .isEqualByComparingTo("27");
        }

        @Test
        @DisplayName("safety stock scales with sqrt(lead time), not with lead time")
        void theBufferGrowsWithTheSquareRoot() {
            ReorderPoint nine = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("9"), SERVICE_95);
            ReorderPoint thirtySix = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("36"), SERVICE_95);

            assertThat(thirtySix.leadTimeDemand())
                    .as("the lead-time demand half scales linearly: 4x the days, 4x the demand")
                    .isEqualByComparingTo(nine.leadTimeDemand().multiply(new BigDecimal("4")));
            assertThat(thirtySix.safetyStock().doubleValue())
                    .as("but the buffer only doubles — variances of independent days add, "
                            + "standard deviations do not. Treating this as linear would "
                            + "over-buffer every slow supplier.")
                    .isCloseTo(nine.safetyStock().doubleValue() * 2, within(0.01));
        }

        @Test
        @DisplayName("a longer lead time always means a higher reorder point")
        void theReorderPointRisesWithTheLeadTime() {
            // MILESTONES M7: "a product on that supplier gets a proportionally
            // higher reorder point at 21 promised days than the same product
            // at 3". Same series, two lead times.
            ReorderPoint fast = calculator.calculate(
                    new BigDecimal("2.65"), new BigDecimal("1.2"), new BigDecimal("3"), SERVICE_95);
            ReorderPoint slow = calculator.calculate(
                    new BigDecimal("2.65"), new BigDecimal("1.2"), new BigDecimal("21"), SERVICE_95);

            assertThat(slow.reorderPoint())
                    .as("21 days of cover to buy versus 3. Fast %s, slow %s.",
                            fast.reorderPoint(), slow.reorderPoint())
                    .isGreaterThan(fast.reorderPoint());
            assertThat(slow.reorderPoint().doubleValue() / fast.reorderPoint().doubleValue())
                    .as("and substantially so — seven times the lead time, so the dominant "
                            + "lead-time-demand term is seven times larger")
                    .isGreaterThan(4.0);
        }

        @Test
        @DisplayName("a zero lead time leaves only the buffer, and does not divide by zero")
        void zeroLeadTimeIsHandled() {
            ReorderPoint result = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("2"), BigDecimal.ZERO, SERVICE_95);

            assertThat(result.reorderPoint())
                    .as("sqrt(0) is 0, so an instantly-delivering supplier needs no stock held "
                            + "against its lead time — the schema permits lead_time_days = 0")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("negative inputs are refused rather than producing a negative reorder point")
        void nonsenseIsRefused() {
            assertThatThrownBy(() -> calculator.calculate(
                    new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ONE, SERVICE_95))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the service level")
    class ServiceLevel {

        @Test
        @DisplayName("z(0.95) is 1.645 — the ADR's worked value")
        void theDefaultIsTheAdrsValue() {
            assertThat(calculator.zFor(new BigDecimal("0.950"))).isEqualByComparingTo("1.645");
            assertThat(ReorderPointCalculator.DEFAULT_SERVICE_LEVEL)
                    .as("and it matches the schema's own default for forecasts.service_level")
                    .isEqualByComparingTo("0.950");
        }

        @Test
        @DisplayName("a higher service level demands more stock")
        void higherServiceMeansMoreBuffer() {
            ReorderPoint ninety = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("9"),
                    new BigDecimal("0.900"));
            ReorderPoint ninetyNine = calculator.calculate(
                    new BigDecimal("3"), new BigDecimal("2"), new BigDecimal("9"),
                    new BigDecimal("0.990"));

            assertThat(ninetyNine.safetyStock())
                    .as("promising to satisfy 99%% of demand costs more stock than 90%%. "
                            + "90%% %s, 99%% %s.", ninety.safetyStock(), ninetyNine.safetyStock())
                    .isGreaterThan(ninety.safetyStock());
        }

        @Test
        @DisplayName("a 50% service level means no buffer — half the time you run short")
        void fiftyPercentIsTheMedian() {
            assertThat(calculator.zFor(new BigDecimal("0.500")))
                    .as("z(0.5) is 0: cover the average and nothing more")
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("an untabulated level rounds to the nearer, ties going to more stock")
        void untabulatedLevelsRoundSafely() {
            assertThat(calculator.zFor(new BigDecimal("0.955")))
                    .as("nearer to 0.950 than to 0.975")
                    .isEqualByComparingTo("1.645");
            assertThat(calculator.zFor(new BigDecimal("0.9625")))
                    .as("exactly midway between 0.950 and 0.975 — the tie goes upward, "
                            + "because a stockout costs a sale and a customer while the extra "
                            + "unit costs shelf space")
                    .isEqualByComparingTo("1.960");
        }
    }
}
