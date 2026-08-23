package com.example.inventory.forecasting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both forecasting properties reject nonsense <strong>at startup</strong>, not
 * quietly at runtime.
 *
 * <p>The distinction is the point. A history window shorter than ADR §5's
 * 42-day readiness floor does not throw when a forecast is requested — it
 * returns {@code insufficient_data} for every product in the system, forever,
 * which looks exactly like a shop with no history rather than like a
 * misconfiguration. A non-positive horizon produces a zero or negative
 * {@code forecast_qty} on every response. Neither fails loudly on its own, so
 * both are refused before the application will start at all.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than a full
 * {@code @SpringBootTest}: this is a statement about binding and validation, and
 * a real context would also need a database to prove a point that has nothing to
 * do with one.
 */
@DisplayName("ForecastingProperties")
class ForecastingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableForecastingProperties.class);

    @EnableConfigurationProperties(ForecastingProperties.class)
    static class EnableForecastingProperties {
    }

    @Test
    @DisplayName("the defaults bind, and are the documented ones")
    void theDefaultsAreWhatTheDocsSay() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ForecastingProperties properties = context.getBean(ForecastingProperties.class);

            assertThat(properties.historyWindowDays())
                    .as("twelve months — ADR §4")
                    .isEqualTo(ForecastingProperties.DEFAULT_WINDOW_DAYS)
                    .isEqualTo(365);
            assertThat(properties.horizonDays())
                    .as("30 days; not an ADR number, a choice recorded on the property")
                    .isEqualTo(ForecastingProperties.DEFAULT_HORIZON_DAYS)
                    .isEqualTo(30);
        });
    }

    @Test
    @DisplayName("a window below the readiness floor refuses to start")
    void aTooShortWindowFailsAtBoot() {
        runner.withPropertyValues("app.forecasting.history-window-days=41").run(context -> {
            assertThat(context)
                    .as("41 is one day under ADR §5's 42-day floor. Started, this would make "
                            + "every product in the system insufficient_data forever — "
                            + "indistinguishable from a shop with no sales history.")
                    .hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("history-window-days");
        });
    }

    @Test
    @DisplayName("a window at the floor starts — the boundary is not refused")
    void theFloorItselfIsAllowed() {
        runner.withPropertyValues("app.forecasting.history-window-days=42").run(context -> {
            assertThat(context)
                    .as("the positive twin: without it, a validator that rejected every value "
                            + "would satisfy the assertion above perfectly")
                    .hasNotFailed();
            assertThat(context.getBean(ForecastingProperties.class).historyWindowDays())
                    .isEqualTo(42);
        });
    }

    @Test
    @DisplayName("a non-positive horizon refuses to start")
    void aNonPositiveHorizonFailsAtBoot() {
        for (String bad : new String[]{"0", "-30"}) {
            runner.withPropertyValues("app.forecasting.horizon-days=" + bad).run(context -> {
                assertThat(context)
                        .as("horizon %s would put a zero or negative forecastQty on every "
                                + "response the contract requires one on", bad)
                        .hasFailed();
                assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("horizon-days");
            });
        }
    }

    @Test
    @DisplayName("a positive horizon starts")
    void aPositiveHorizonIsAllowed() {
        runner.withPropertyValues("app.forecasting.horizon-days=90").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ForecastingProperties.class).horizonDays()).isEqualTo(90);
        });
    }

    @Test
    @DisplayName("the record itself refuses too, so a direct constructor call cannot bypass it")
    void theInvariantLivesOnTheRecordNotOnTheBinder() {
        // Tests construct these directly (DemandSeriesRepository takes one), so
        // the check has to be on the type rather than on Spring's binding, or a
        // test could hold a configuration production could never boot with.
        assertThatThrownBy(() -> new ForecastingProperties(10, 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("history-window-days");
        assertThatThrownBy(() -> new ForecastingProperties(365, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horizon-days");
    }
}
