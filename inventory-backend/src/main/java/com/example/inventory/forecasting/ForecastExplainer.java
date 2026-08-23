package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.example.inventory.forecasting.Forecaster.Forecast;
import com.example.inventory.forecasting.LeadTimeResolver.LeadTime;

/**
 * The sentence a shop owner actually reads — {@code docs/adr/forecasting.md}
 * §6.
 *
 * <p>This is the only place most of this milestone's arithmetic ever becomes
 * visible to a person. Nobody reads {@code nonzero_fraction} or
 * {@code relativeTrend}; they read one or two sentences and decide whether to
 * order. So the rules here are about honesty rather than presentation.
 *
 * <h2>Every forecast gets a real sentence, including the ones with no number</h2>
 *
 * <p>{@code ForecastDetail.explanation} is {@code required} in the contract
 * unconditionally — not conditioned on {@code method}. ADR §6 draws the
 * consequence explicitly: the {@code insufficient_data} path needs a real
 * sentence too, not an empty string waiting for the confident branch to be
 * written first. "Still learning, N more days needed" is a real explanation and
 * is arguably the most useful thing this system can say about a new product.
 *
 * <h2>It must be truthful about which lead time it quotes</h2>
 *
 * <p>ADR §6: the observed figure and the promised one can disagree, and this
 * sentence is the one place a shop owner ever sees either, so it cannot silently
 * mix them. "has been taking about 5 days" and "promises 21 days" are different
 * claims and are worded differently, with the sample size attached to the
 * measured one so the reader can judge how much to trust it.
 *
 * <h2>The seasonal caveat is a sentence, not a flag</h2>
 *
 * <p>A boolean nobody renders protects nobody. When
 * {@link Forecast#seasonalitySuspected()} holds, the explanation says so in
 * words a shop owner can act on — that the product's sales repeat on a cycle,
 * that this estimate averages the busy and quiet stretches together, and what to
 * do about it. The number is still given (see {@link Forecaster}), and the
 * sentence is what stops it being read as more reliable than it is.
 */
@Component
public class ForecastExplainer {

    /**
     * How the demand rate is phrased. A week is the unit a shop owner thinks in
     * — "about 19 a week" lands where "2.651 units per day" does not, and the
     * contract's own example is weekly.
     */
    private static final int DAYS_PER_WEEK = 7;

    /** The forecast's own explanation: what we think you sell, and for how long. */
    public String explain(Forecast forecast, String productName) {
        if (!forecast.selection().isReady()) {
            return stillLearning(forecast, productName);
        }

        StringBuilder text = new StringBuilder();
        BigDecimal weekly = forecast.avgDailyDemand()
                .multiply(BigDecimal.valueOf(DAYS_PER_WEEK), DemandSeries.MC);
        text.append("You sell about ").append(friendly(weekly)).append(" a week. ");

        text.append(friendly(forecast.quantityOnHand())).append(" in stock ");
        if (forecast.daysOfCover() == null) {
            text.append("and no recent sales, so that will last indefinitely on current demand.");
        } else {
            text.append("covers roughly ")
                    .append(forecast.daysOfCover().setScale(0, RoundingMode.HALF_UP))
                    .append(" days");
            if (forecast.leadTime() != null) {
                text.append(", and ").append(leadTimePhrase(forecast.leadTime()));
            }
            text.append('.');
        }

        if (forecast.hasReorderPoint()) {
            text.append(" Reorder when you get down to about ")
                    .append(friendly(forecast.reorderPoint())).append('.');
        } else if (forecast.leadTime() == null) {
            // Said plainly rather than left as a silent null. A shop owner who
            // wonders why one product has advice and another does not deserves
            // the actual reason, and it is one they can fix.
            text.append(" No supplier is recorded for this product, so there is no delivery "
                    + "time to plan around — add one to get a reorder level.");
        }

        appendSeasonalCaveat(text, forecast);
        return text.toString();
    }

    /**
     * Why this product is on the reorder list, how much to order and how urgent
     * it is. Separate from {@link #explain} because it answers a different
     * question: that one is "what do we think about this product", this one is
     * "why am I being told to act now".
     */
    public String explainRecommendation(Forecast forecast, BigDecimal recommendedQty,
                                        Urgency urgency, String supplierName) {
        StringBuilder text = new StringBuilder();

        text.append(switch (urgency) {
            case CRITICAL -> forecast.quantityOnHand().signum() <= 0
                    ? "You are out of stock now. "
                    : "This will run out before a new order can arrive. ";
            case HIGH -> "This is running low and needs ordering soon. ";
            case NORMAL -> "Stock has dropped to the reorder level. ";
        });

        text.append(friendly(forecast.quantityOnHand())).append(" left");
        if (forecast.daysOfCover() != null) {
            text.append(", about ").append(forecast.daysOfCover().setScale(0, RoundingMode.HALF_UP))
                    .append(" days at your usual rate");
        }
        text.append(". ");

        if (forecast.leadTime() != null) {
            String supplier = supplierName == null ? "Your supplier" : supplierName;
            text.append(supplier).append(' ').append(leadTimePhrase(forecast.leadTime()))
                    .append(". ");
        }

        text.append("Order about ").append(friendly(recommendedQty))
                .append(" to cover the wait plus roughly the next ")
                .append(forecast.horizonDays()).append(" days.");

        appendSeasonalCaveat(text, forecast);
        return text.toString();
    }

    /**
     * ADR §5 and §6: a product below the readiness threshold gets a sentence
     * saying what is missing and roughly how long until it is not.
     */
    private String stillLearning(Forecast forecast, String productName) {
        MethodSelector.Selection selection = forecast.selection();
        StringBuilder text = new StringBuilder("Still learning ")
                .append(productName == null ? "this product" : productName).append(" — ");

        if (selection.historyDays() == 0) {
            return text.append("no sales history recorded yet. Once it has been on sale for "
                    + "about six weeks there will be enough to work from.").toString();
        }

        text.append(selection.historyDays())
                .append(selection.historyDays() == 1 ? " day" : " days")
                .append(" of history so far");

        if (selection.daysShortOfHistory() > 0) {
            text.append(", about ").append(selection.daysShortOfHistory())
                    .append(" more needed");
        }
        if (selection.nonZeroDaysShortOfCount() > 0) {
            // Deliberately phrased as SELLING days rather than calendar days.
            // Quoting the raw count as if it were days would promise a date this
            // cannot keep: a product needing 8 more selling days at one sale a
            // fortnight is four months away, not eight days.
            text.append(selection.daysShortOfHistory() > 0 ? ", and " : ", but ");
            if (selection.nonZeroEligibleDays() == 0) {
                // "it has only sold on 0 days" is technically correct and reads
                // like machine output. Dead stock is the single most common
                // product to land here, so it gets the sentence it deserves.
                text.append("it has not sold at all yet — about ")
                        .append(selection.nonZeroDaysShortOfCount())
                        .append(" days with a sale are needed before there is anything to "
                                + "forecast from");
            } else {
                text.append("it has only sold on ").append(selection.nonZeroEligibleDays())
                        .append(selection.nonZeroEligibleDays() == 1 ? " day" : " days")
                        .append(" — about ").append(selection.nonZeroDaysShortOfCount())
                        .append(" more days with a sale are needed");
            }
        }

        return text.append(". Until then a reorder level would be a guess rather than a "
                + "forecast.").toString();
    }

    /**
     * Never "the supplier takes N days" flatly. Measured and promised are
     * different claims and the reader is entitled to know which one this is —
     * ADR §6.
     */
    private String leadTimePhrase(LeadTime leadTime) {
        String days = friendly(leadTime.days()) + (isOne(leadTime.days()) ? " day" : " days");
        return switch (leadTime.source()) {
            case OBSERVED -> "has been taking about " + days + " to deliver (measured over "
                    + leadTime.sampleSize() + " deliveries)";
            case PROMISED_FOR_PRODUCT, PROMISED_BY_SUPPLIER ->
                    "quotes " + days + " to deliver (their stated time — you have not had "
                            + "enough deliveries yet to measure the real one)";
        };
    }

    private void appendSeasonalCaveat(StringBuilder text, Forecast forecast) {
        if (!forecast.seasonalitySuspected()) {
            return;
        }
        // The whole reason MethodSelector measures seasonality at all. Everything
        // it can choose averages a cycle flat, so this number is right for an
        // average week and wrong at both the peak and the trough — and the only
        // person who can compensate for that is the one reading this sentence.
        text.append(" Note: this product's sales repeat on a regular pattern — busier at some "
                + "times than others — and this estimate averages the busy and quiet stretches "
                + "together, so it cannot fully account for that pattern. Treat it as a rough "
                + "guide: you will likely need more than this going into a busy stretch, and "
                + "less coming out of one.");
    }

    /** Urgency, matching the contract's {@code ReorderRecommendation.urgency}. */
    public enum Urgency {
        CRITICAL("critical"), HIGH("high"), NORMAL("normal");

        private final String wireValue;

        Urgency(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    /**
     * Rounds the way a person would say the number aloud: whole units once it is
     * big enough for a fraction to be noise, one decimal below that.
     *
     * <p>"About 19 a week" is what a shop owner wants; "about 18.557 a week" is
     * a false precision that also reads as machine output. But "about 0 a week"
     * for a product selling twice a month would be actively wrong, which is why
     * small numbers keep a decimal.
     */
    static String friendly(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal rounded = value.abs().compareTo(BigDecimal.TEN) >= 0
                ? value.setScale(0, RoundingMode.HALF_UP)
                : value.setScale(1, RoundingMode.HALF_UP);
        return rounded.stripTrailingZeros().toPlainString();
    }

    private static boolean isOne(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).compareTo(BigDecimal.ONE) == 0;
    }
}
