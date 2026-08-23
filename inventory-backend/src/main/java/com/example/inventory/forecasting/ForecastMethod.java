package com.example.inventory.forecasting;

/**
 * The forecasting methods, matching the {@code forecast_method} enum in the
 * database and the {@code Forecast.method} enum in {@code docs/openapi.yaml}.
 *
 * <p>All three lists must agree. The contract is the source of truth (CLAUDE.md
 * §1); {@code MethodSelectorTest} asserts every value here appears in it, so a
 * value added to one and not the others fails the build rather than serializing
 * something no client can parse.
 */
public enum ForecastMethod {

    /** Flat, regular demand. The plain mean over the eligible history. */
    MOVING_AVERAGE("moving_average"),

    /** Trending demand. Linearly recency-weighted, so a ramp is not averaged flat. */
    WEIGHTED_MOVING_AVERAGE("weighted_moving_average"),

    /**
     * Declared by the contract and by {@code V1}, and deliberately not selected
     * by anything yet — see {@link MethodSelector}'s Javadoc. The same reasoning
     * {@code docs/adr/forecasting.md} §4 applies to {@code ml_model}: an enum
     * slot existing is not a decision that it should be filled.
     */
    EXPONENTIAL_SMOOTHING("exponential_smoothing"),

    /** Intermittent demand: separate estimates of demand size and interval. */
    CROSTON("croston"),

    /** Declared, never selected. ADR §4 and §7 gate this behind the naive comparison. */
    ML_MODEL("ml_model"),

    /**
     * Below the readiness threshold (ADR §5). Not a method so much as a refusal
     * to state one — and it still carries a real explanation (ADR §6).
     */
    INSUFFICIENT_DATA("insufficient_data");

    private final String dbValue;

    ForecastMethod(String dbValue) {
        this.dbValue = dbValue;
    }

    /** The literal stored in {@code forecasts.method} and emitted over HTTP. */
    public String dbValue() {
        return dbValue;
    }

    public static ForecastMethod fromDbValue(String value) {
        for (ForecastMethod method : values()) {
            if (method.dbValue.equals(value)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Unknown forecast_method: " + value);
    }
}
