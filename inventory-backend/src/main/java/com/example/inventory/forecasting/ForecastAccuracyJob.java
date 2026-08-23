package com.example.inventory.forecasting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.inventory.tenancy.TenantContext;

/**
 * Scores elapsed forecasts against what actually happened — and, in the same
 * pass, against the naive "same as last period" baseline.
 *
 * <h2>The baseline is not optional and not deferred</h2>
 *
 * <p>{@code docs/adr/forecasting.md} §7: populated and compared <strong>from day
 * one</strong>, not added later once a real model looks good enough to survive
 * the comparison. The reason is that the comparison is only trustworthy if
 * nobody chose when to start running it. A baseline introduced after six months
 * of tuning measures the tuning, not the method.
 *
 * <p>"Same as last period", precisely: for a forecast evaluated over
 * {@code [period_start, period_end)}, the naive prediction is the actual
 * {@code units_sold} total from the immediately preceding period of equal
 * length. No averaging, no trend, no seasonality — deliberately the dumbest
 * possible forecast. If a real method cannot beat it, "the model is not earning
 * its complexity" becomes a mechanical fact rather than an argument.
 *
 * <h2>Shape (a): the baseline is a forecast row, not a column</h2>
 *
 * <p>ADR §7 left the representation open and step 1 resolved it. Each evaluation
 * writes a synthetic {@code forecasts} row carrying {@code method = 'naive'}
 * (the value {@code V9} added) and scores it through this same table. One
 * scoring path rather than two, and no sibling columns sitting NULL on every
 * real row.
 *
 * <p>The synthetic rows are always {@code is_current = false}, so they never
 * collide with {@code forecasts_current_uq} and never appear as a product's live
 * forecast. They exist to be compared against, not to be shown.
 *
 * <h2>Nothing is scored before its period has fully elapsed</h2>
 *
 * <p>A forecast is due only once {@code today >= period_end}. Scoring a
 * half-finished period would compare a full-horizon prediction against a partial
 * actual and report every method as wildly over-forecasting — a bug that looks
 * like a finding.
 *
 * <h2>Real numbers need real time</h2>
 *
 * <p>This mechanism works from the moment it is wired, but it has nothing to
 * score until forecasts made today have had their horizon elapse. The figures it
 * produces only become meaningful after M7 has been running for a while, and a
 * MAPE computed over two evaluations says nothing about a method. Tests here use
 * synthetic before/after data precisely because real history cannot be
 * fast-forwarded.
 */
@Service
public class ForecastAccuracyJob {

    private final JdbcTemplate jdbc;

    public ForecastAccuracyJob(@Qualifier("appDataSource") DataSource appDataSource) {
        this.jdbc = new JdbcTemplate(appDataSource);
    }

    /** @param scored real forecasts scored; each also produced a naive baseline */
    public record ScoringResult(int scored) {
    }

    /**
     * One method's record, for the per-bucket comparison ADR §7 requires.
     *
     * @param meanAbsPctError null when every evaluation had a zero actual, so
     *                        no percentage error was defined for any of them
     */
    public record MethodAccuracy(ForecastMethod method, int evaluations,
                                 BigDecimal meanAbsPctError) {
    }

    public ScoringResult scoreDueForecasts() {
        return scoreDueForecasts(LocalDate.now());
    }

    /**
     * @param today evaluation cut-off; injectable so a test can score a period
     *              without waiting a month for it to elapse
     */
    @Transactional
    public ScoringResult scoreDueForecasts(LocalDate today) {
        TenantContext.currentTenantId().orElseThrow(() -> new IllegalStateException(
                "ForecastAccuracyJob requires a bound tenant — see DemandRollupJob's Javadoc "
                        + "and CLAUDE.md section 12."));

        List<Due> due = jdbc.query("""
                SELECT f.id,
                       f.product_id,
                       f.location_id,
                       f.horizon_days,
                       f.forecast_qty,
                       (f.generated_at AT TIME ZONE COALESCE(
                            (SELECT timezone FROM tenants WHERE id = current_tenant_id()),
                            'UTC'))::date + 1 AS period_start
                FROM forecasts f
                WHERE f.method <> 'naive'
                  AND f.method <> 'insufficient_data'
                ORDER BY f.id
                """,
                (rs, rowNum) -> new Due(
                        rs.getLong("id"),
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getInt("horizon_days"),
                        rs.getBigDecimal("forecast_qty"),
                        rs.getObject("period_start", LocalDate.class)));

        int scored = 0;
        for (Due forecast : due) {
            LocalDate periodEnd = forecast.periodStart().plusDays(forecast.horizonDays());
            if (today.isBefore(periodEnd)) {
                // The period is still running. See the class Javadoc: scoring it
                // now would compare a whole horizon against part of one.
                continue;
            }
            if (alreadyScored(forecast.id(), forecast.periodStart())) {
                continue;
            }

            BigDecimal actual = actualDemand(forecast.productId(), forecast.locationId(),
                    forecast.periodStart(), periodEnd);

            score(forecast.id(), forecast.productId(), forecast.periodStart(), periodEnd,
                    forecast.forecastQty(), actual);

            // The baseline, over the same window: what the immediately
            // preceding period of equal length actually sold.
            BigDecimal naivePrediction = actualDemand(forecast.productId(),
                    forecast.locationId(),
                    forecast.periodStart().minusDays(forecast.horizonDays()),
                    forecast.periodStart());
            long naiveId = insertNaiveForecast(forecast, naivePrediction);
            score(naiveId, forecast.productId(), forecast.periodStart(), periodEnd,
                    naivePrediction, actual);

            scored++;
        }
        return new ScoringResult(scored);
    }

    private boolean alreadyScored(long forecastId, LocalDate periodStart) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM forecast_accuracy
                WHERE forecast_id = ? AND period_start = ?
                """, Integer.class, forecastId, periodStart);
        return count != null && count > 0;
    }

    /** Half-open: {@code [from, to)}. */
    private BigDecimal actualDemand(UUID productId, UUID locationId, LocalDate from, LocalDate to) {
        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(sum(units_sold), 0) FROM demand_daily
                WHERE product_id = ? AND location_id = ? AND day >= ? AND day < ?
                """, BigDecimal.class, productId, locationId, from, to);
        return total == null ? BigDecimal.ZERO : total;
    }

    private void score(long forecastId, UUID productId, LocalDate periodStart,
                       LocalDate periodEnd, BigDecimal predicted, BigDecimal actual) {
        jdbc.update("""
                INSERT INTO forecast_accuracy
                    (tenant_id, forecast_id, product_id, period_start, period_end,
                     predicted_qty, actual_qty, abs_pct_error)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?)
                """,
                forecastId, productId, periodStart, periodEnd, predicted, actual,
                absolutePercentageError(predicted, actual));
    }

    /**
     * {@code |predicted − actual| / actual × 100}, or null when nothing sold.
     *
     * <p>MAPE is undefined against a zero actual: the relative error is infinite
     * however small the prediction. Substituting a large finite number would let
     * one quiet period dominate every average it entered, and intermittent
     * products — the bucket ADR §7 most wants compared — produce zero periods
     * routinely. Null, and the aggregate skips it the way {@code AVG()} skips
     * any other null.
     */
    private BigDecimal absolutePercentageError(BigDecimal predicted, BigDecimal actual) {
        if (actual.signum() == 0) {
            return null;
        }
        return predicted.subtract(actual).abs()
                .divide(actual.abs(), DemandSeries.MC)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * The synthetic {@code naive} forecast row this evaluation is compared
     * against. Never current, so it cannot be mistaken for a product's live
     * forecast or collide with {@code forecasts_current_uq}.
     */
    private long insertNaiveForecast(Due forecast, BigDecimal prediction) {
        BigDecimal dailyRate = prediction.divide(
                BigDecimal.valueOf(forecast.horizonDays()), DemandSeries.MC)
                .setScale(4, RoundingMode.HALF_UP);

        return jdbc.queryForObject("""
                INSERT INTO forecasts
                    (tenant_id, product_id, location_id, method, generated_at, history_days,
                     history_from, history_to, avg_daily_demand, demand_stddev, horizon_days,
                     forecast_qty, service_level, is_current)
                VALUES (current_tenant_id(), ?, ?, 'naive', ?, ?, ?, ?, ?, 0, ?, ?, 0.950, false)
                RETURNING id
                """,
                Long.class,
                forecast.productId(),
                forecast.locationId(),
                forecast.periodStart().atStartOfDay().atOffset(java.time.ZoneOffset.UTC),
                forecast.horizonDays(),
                forecast.periodStart().minusDays(forecast.horizonDays()),
                forecast.periodStart(),
                dailyRate,
                forecast.horizonDays(),
                prediction);
    }

    /**
     * Mean absolute percentage error per method — ADR §7's per-bucket view.
     *
     * <p>Grouped by method rather than reported as one number, because that is
     * the finding the evaluation exists to surface: a method that beats naive
     * for steady sellers and loses for intermittent ones is a real result, and
     * averaging the two together hides exactly it.
     */
    public List<MethodAccuracy> accuracyByMethod() {
        return jdbc.query("""
                SELECT f.method::text          AS method,
                       count(*)                AS evaluations,
                       avg(a.abs_pct_error)    AS mean_error
                FROM forecast_accuracy a
                JOIN forecasts f ON f.id = a.forecast_id
                GROUP BY f.method
                ORDER BY f.method::text
                """,
                (rs, rowNum) -> new MethodAccuracy(
                        ForecastMethod.fromDbValue(rs.getString("method")),
                        rs.getInt("evaluations"),
                        rs.getBigDecimal("mean_error") == null ? null
                                : rs.getBigDecimal("mean_error").setScale(4, RoundingMode.HALF_UP)));
    }

    private record Due(long id, UUID productId, UUID locationId, int horizonDays,
                       BigDecimal forecastQty, LocalDate periodStart) {
    }
}
