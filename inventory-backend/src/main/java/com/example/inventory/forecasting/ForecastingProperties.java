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
        @DefaultValue("365") int historyWindowDays) {

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

    public ForecastingProperties {
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
