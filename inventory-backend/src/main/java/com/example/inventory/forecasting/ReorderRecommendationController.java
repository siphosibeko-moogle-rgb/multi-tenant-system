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

        return queries.listRecommendations(normaliseStatus(status), supplierId, cursor,
                clampLimit(limit));
    }

    /**
     * Validates the {@code status} filter before it reaches a SQL enum cast.
     *
     * <h2>Why this is not just a pass-through</h2>
     *
     * <p>It was, and the failure was a 500. {@code CAST(? AS
     * recommendation_status)} raises SQLSTATE 22P02 for any value outside the
     * enum, which surfaces as {@code DataIntegrityViolationException} and an
     * "Unhandled data access failure" — a server error for what is plainly a bad
     * request. A caller passing {@code status=banana} deserves a 400 telling
     * them the four valid values, not a 500 telling them nothing.
     *
     * <p>Found by the Android client, which sent {@code status=OPEN}: the
     * generated Kotlin enum's {@code toString()} is the Kotlin constant name,
     * and Retrofit uses that for a {@code @Query} enum, so the default parameter
     * value serialised in upper case while the contract and the database enum
     * are both lower case. Every generated client using that default would have
     * hit it.
     *
     * <h2>Case-insensitive, but not otherwise lenient</h2>
     *
     * <p>Upper case is accepted because a case difference here cannot mean two
     * different things — there is no {@code OPEN} distinct from {@code open} for
     * it to be confused with — and refusing it would break a generated client
     * over presentation. Anything that is not one of the four values is still
     * refused: leniency about case is not leniency about meaning.
     */
    private String normaliseStatus(String status) {
        if (status == null || status.isBlank()) {
            return DEFAULT_STATUS;
        }
        String candidate = status.trim().toLowerCase(java.util.Locale.ROOT);
        if (!VALID_STATUSES.contains(candidate)) {
            throw new com.example.inventory.web.BadRequestException(
                    "Unknown status '" + status + "'. Valid values are "
                            + String.join(", ", VALID_STATUSES) + ".");
        }
        return candidate;
    }

    /** The contract's {@code ReorderRecommendation.status} enum, in order. */
    private static final java.util.List<String> VALID_STATUSES =
            java.util.List.of("open", "ordered", "dismissed", "expired");

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
