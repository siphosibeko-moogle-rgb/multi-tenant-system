package com.example.inventory.forecasting;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link ForecastingProperties}, the same way {@code SecurityConfig} binds
 * {@code AuthProperties} — one enabling point per feature package rather than a
 * blanket scan, so it is greppable which properties this feature actually reads.
 */
@Configuration
@EnableConfigurationProperties(ForecastingProperties.class)
public class ForecastingConfig {
}
