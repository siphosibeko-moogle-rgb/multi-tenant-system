package com.example.inventory.forecasting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Forecasting configuration, bound from {@code app.forecasting.*}.
 *
 * @param historyWindowDays how far back a forecast looks. See below.
 */
@ConfigurationProperties("app.forecasting")
public record ForecastingProperties(
        @DefaultValue("365") int historyWindowDays,
        @DefaultValue("30") int horizonDays) {

    // Deliberately NO convenience constructor. A record with a second
    // constructor gives Spring two candidates for constructor binding and it
    // refuses to guess — the context fails at startup with "No default
    // constructor found", which names neither the extra constructor nor this
    // class's role. Tests pass both values.

    /**
     * Twelve months. Long enough to contain a full annual cycle if the product
     * has one, short enough that demand from over a year ago stops influencing
     * today's number.
     *
     * <p>Both halves of that matter. Too short and a seasonal product's forecast
     * is whatever season it happens to be in; too long and a product that
     * genuinely changed — a new competitor, a price change, a supplier switch —
     * keeps being forecast from a market that no longer exists.
     */
    public static final int DEFAULT_WINDOW_DAYS = 365;

    /**
     * How far ahead {@code forecast_qty} projects, in days.
     *
     * <p><strong>Not an ADR number.</strong> The contract requires
     * {@code horizonDays} and {@code forecastQty} on every forecast and neither
     * the ADR nor {@code MILESTONES.md} fixes a period, so 30 days is a choice
     * made here: it is the horizon a shop owner asking "how much will I sell
     * next month" has in mind, and it is independent of the reorder point, which
     * uses the lead time rather than this.
     */
    public static final int DEFAULT_HORIZON_DAYS = 30;

    public ForecastingProperties {
        if (horizonDays <= 0) {
            throw new IllegalArgumentException(
                    "app.forecasting.horizon-days must be positive; got " + horizonDays);
        }
        if (historyWindowDays < MethodSelector.MIN_HISTORY_DAYS) {
            // Below the readiness floor the window would refuse every product a
            // forecast, and it would do it silently: every series would look
            // too-new forever. Fail at startup instead.
            throw new IllegalArgumentException(
                    "app.forecasting.history-window-days must be at least "
                            + MethodSelector.MIN_HISTORY_DAYS + " (ADR §5's readiness floor) "
                            + "or no product could ever become ready; got " + historyWindowDays);
        }
    }
}
