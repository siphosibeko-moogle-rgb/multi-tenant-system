package com.example.inventory.forecasting;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.forecasting.ForecastDtos.DismissRequest;
import com.example.inventory.forecasting.ForecastDtos.RecommendationPage;
import com.example.inventory.tenancy.TenantContext;
import com.example.inventory.web.NotFoundException;

/**
 * {@code GET /reorder-recommendations} and its dismiss action.
 *
 * <h2>Role gates</h2>
 *
 * <p>Reading the list is every role, {@code viewer} included. <strong>Dismissing
 * is {@code owner} and {@code manager}</strong>: the contract's {@code UserRole}
 * table assigns purchasing to the manager, and dismissing a recommendation is a
 * purchasing decision — it is the act of saying "we are not ordering this", and
 * it hides the product from the reorder list until the next recompute. A clerk's
 * listed duties are recording sales, receiving stock and counting; none of them
 * is deciding what the shop does not buy. Asserted both ways in
 * {@code ForecastRoleTest}.
 */
@RestController
@RequestMapping("/reorder-recommendations")
public class ReorderRecommendationController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    /** The contract's default for the {@code status} filter. */
    private static final String DEFAULT_STATUS = "open";

    private final ForecastQueries queries;

    public ReorderRecommendationController(ForecastQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    public RecommendationPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID supplierId) {

        String effectiveStatus = status == null || status.isBlank() ? DEFAULT_STATUS : status;
        return queries.listRecommendations(effectiveStatus, supplierId, cursor,
                clampLimit(limit));
    }

    /**
     * Dismiss one recommendation.
     *
     * <h2>404 for an id that is not an open recommendation of this tenant</h2>
     *
     * <p>Another tenant's id, an id that never existed, and one already
     * dismissed all produce the same 404. The first two are required to be
     * indistinguishable by T8 — a 403 would confirm the id exists — and RLS
     * makes them so without a branch, since another tenant's row is simply not
     * visible to the {@code UPDATE}.
     *
     * <p>The already-resolved case returning 404 rather than 409 is a choice:
     * dismissing something already dismissed has no effect to report and no
     * conflict to resolve, and the caller's next step is the same either way —
     * refresh the list. A 409 would imply there is something to reconcile.
     */
    @PostMapping("/{recommendationId}/dismiss")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public ResponseEntity<Void> dismiss(
            @PathVariable UUID recommendationId,
            @RequestBody(required = false) DismissRequest request) {

        String reason = request == null ? null : request.reason();
        UUID actor = TenantContext.currentUserId().orElse(null);

        if (!queries.dismiss(recommendationId, reason, actor)) {
            throw new NotFoundException(
                    "No open reorder recommendation with id " + recommendationId);
        }
        return ResponseEntity.noContent().build();
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
