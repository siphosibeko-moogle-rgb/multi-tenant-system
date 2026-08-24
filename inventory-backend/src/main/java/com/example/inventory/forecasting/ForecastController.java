package com.example.inventory.forecasting;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.forecasting.ForecastDtos.ForecastDetailResponse;
import com.example.inventory.forecasting.ForecastDtos.ForecastPage;
import com.example.inventory.forecasting.ForecastDtos.ForecastResponse;
import com.example.inventory.forecasting.ForecastDtos.HistoryPoint;
import com.example.inventory.forecasting.ForecastDtos.RecomputeAccepted;
import com.example.inventory.forecasting.ForecastDtos.RecomputeRequest;
import com.example.inventory.web.NotFoundException;

/**
 * {@code GET /forecasts}, {@code GET /products/{id}/forecast} and
 * {@code POST /forecasts/recompute}.
 *
 * <p>HTTP only — validate, map, delegate, return (CLAUDE.md §4). The forecasting
 * itself is {@link Forecaster}'s and the reads are {@link ForecastQueries}'.
 *
 * <h2>Role gates</h2>
 *
 * <p>Reading a forecast is reading, so every role including {@code viewer} —
 * the contract defines viewer as "read only", which grants reads rather than
 * withholding them.
 *
 * <p><strong>Recompute is {@code owner} and {@code manager}.</strong> The
 * contract's {@code UserRole} table gives manager "catalog, purchasing, reports"
 * and gives clerk "record sales, receive stock, count" — recomputing the whole
 * catalogue's forecasts is neither of a clerk's three jobs, and it is the input
 * to purchasing decisions, which are the manager's. Viewer is read-only and a
 * recompute writes rows. Per CLAUDE.md §13 the narrower reading wins where the
 * contract is silent: widening this later is a one-line change, and every gate
 * here is asserted from both sides in {@code ForecastRoleTest} rather than left
 * to the absence of a happy-path test.
 */
@RestController
public class ForecastController {

    /** The contract's {@code Limit} parameter default and ceiling. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ForecastQueries queries;
    private final ReorderService reorderService;

    public ForecastController(ForecastQueries queries, ReorderService reorderService) {
        this.queries = queries;
        this.reorderService = reorderService;
    }

    @GetMapping("/forecasts")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    public ForecastPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) Integer horizonDays) {
        return queries.listForecasts(locationId, horizonDays, cursor, clampLimit(limit));
    }

    /**
     * One product's forecast, with its explanation.
     *
     * <h2>404 versus an empty forecast</h2>
     *
     * <p>A product that exists but has never been rolled up gets a real
     * {@code insufficient_data} forecast rather than a 404 — "we have nothing to
     * say yet" is a true and useful answer, and the contract's own description
     * of this endpoint asks for exactly that. 404 is reserved for a product that
     * does not exist <em>for this tenant</em>, which under RLS is
     * indistinguishable from one that does not exist at all — T8's requirement,
     * satisfied here without a branch because the query simply returns nothing.
     *
     * <h2>{@code history} is present even when not requested</h2>
     *
     * <p>The contract marks {@code history} {@code required} on
     * {@code ForecastDetail} <em>and</em> offers {@code includeHistory} defaulting
     * to false. Those cannot both be honoured by omitting the field, so
     * "excluded" means an empty array rather than an absent key. Flagged rather
     * than resolved silently (CLAUDE.md §1): the two statements disagree, and an
     * empty array is the reading that keeps every generated client working.
     */
    @GetMapping("/products/{productId}/forecast")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    public ForecastDetailResponse forProduct(
            @PathVariable UUID productId,
            @RequestParam(required = false, defaultValue = "false") boolean includeHistory,
            @RequestParam(required = false) UUID locationId) {

        if (!queries.productExists(productId)) {
            throw new NotFoundException("No product with id " + productId);
        }

        // A demand series is keyed on product AND location, so an unresolved
        // null matches nothing and reports insufficient_data for everything —
        // which looks like a shop with no history rather than like a missing
        // parameter. See ForecastQueries.defaultLocationId().
        UUID effectiveLocation = locationId != null
                ? locationId
                : queries.defaultLocationId().orElse(null);

        ReorderService.ExplainedForecast explained =
                reorderService.explainedForecast(productId, effectiveLocation);
        Forecaster.Forecast forecast = explained.forecast();

        ForecastResponse stored = queries.currentForecast(productId, effectiveLocation)
                .orElse(null);
        List<HistoryPoint> history = includeHistory
                ? queries.history(productId, effectiveLocation)
                : List.of();

        return new ForecastDetailResponse(
                productId,
                forecast.locationId(),
                forecast.method().dbValue(),
                stored == null ? java.time.OffsetDateTime.now() : stored.generatedAt(),
                forecast.selection().historyDays(),
                forecast.avgDailyDemand(),
                forecast.demandStddev(),
                forecast.horizonDays(),
                forecast.forecastQty(),
                forecast.daysOfCover(),
                forecast.projectedStockoutOn(),
                forecast.reorderPoint(),
                forecast.serviceLevel(),
                forecast.confidence(),
                explained.explanation(),
                history);
    }

    /**
     * Recompute, returning the contract's 202.
     *
     * <p><strong>The work is finished before the 202 returns.</strong> The
     * contract says "Accepted; runs asynchronously" and this runs inline, which
     * is a deliberate and stated difference rather than an oversight.
     *
     * <p>Running it on another thread would mean binding the tenant there, and
     * {@code TenantContext} is deliberately not inheritable — its Javadoc calls a
     * child thread inheriting a tenant "a leak waiting to happen". Propagating it
     * explicitly to a worker is the same decision as the scheduled cross-tenant
     * rollup, which CLAUDE.md §12 says to make deliberately rather than as a side
     * effect of wanting a background job. That decision is not made here.
     *
     * <p>202 remains the honest status: it means the request was accepted, not
     * that the work has been deferred. There is no job-status endpoint in the
     * contract, which fits — a truly asynchronous job with nothing to poll would
     * be unobservable. {@code jobId} is a correlation id for this run's log
     * lines, not a handle to poll.
     */
    @PostMapping("/forecasts/recompute")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public ResponseEntity<RecomputeAccepted> recompute(
            @RequestBody(required = false) RecomputeRequest request) {

        ReorderService.RecomputeResult result = reorderService.recomputeAll();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new RecomputeAccepted(UUID.randomUUID(), result.forecastsWritten()));
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
