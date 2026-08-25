package com.example.inventory.reporting;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.reporting.ReportDtos.DashboardSummary;
import com.example.inventory.reporting.ReportDtos.InventoryValuation;
import com.example.inventory.reporting.ReportDtos.SalesSummaryResponse;
import com.example.inventory.reporting.ReportDtos.TopProduct;
import com.example.inventory.web.BadRequestException;

/**
 * M8's four report endpoints. HTTP only — validate, map, delegate, return
 * (CLAUDE.md §4); every figure is computed in {@link ReportQueries}.
 *
 * <h2>Role gates: owner and manager, on all four</h2>
 *
 * <p>The contract's {@code UserRole} table lists "reports" under <strong>manager</strong>
 * and nowhere else, and gives owner everything. Clerk's three jobs are "record
 * sales, receive stock, count"; reports are none of them. Viewer is "read only",
 * which grants a <em>mode</em> and says nothing about <em>scope</em> — and per
 * CLAUDE.md §13 the narrower reading wins where the contract is silent.
 *
 * <p>Here the narrow reading is also the substantive answer, because
 * <strong>every one of these four responses carries cost or margin</strong>:
 * {@code grossProfit}, {@code inventoryValue} (valued at cost),
 * {@code totalCostValue} and {@code grossMarginPct} all disclose what the
 * business pays for stock and what it makes on it. There is no honest redacted
 * variant either — {@code grossProfit} and {@code inventoryValue} are both in
 * {@code DashboardSummary}'s {@code required} list, so omitting them for a
 * lesser role would break the contract and every generated client.
 *
 * <p>{@code ReportRoleTest} asserts allow <em>and</em> deny for all four roles
 * against all four endpoints. A gate that accidentally permits everyone passes a
 * happy-path test perfectly.
 *
 * <p><strong>Consequence for the Android client</strong>, recorded here so it is
 * not discovered on a device: the Home screen's sales tile is now servable, but
 * only to an owner or a manager. A clerk's Home must not call it —
 * {@code tabsFor(role)} is the existing mechanism, and hiding it is the point,
 * since nobody should be invited into a 403.
 *
 * <h2>Query parameters are validated, never passed through</h2>
 *
 * <p>{@code period}, {@code groupBy} and {@code order} are checked against the
 * contract's enums and answered with 400 when unknown. The Android pass found
 * the alternative the expensive way: a generated client's enum default went on
 * the wire upper-case, reached a lower-case PostgreSQL enum through a cast, and
 * surfaced as a 500 (CLAUDE.md §5). Matching is case-insensitive for the same
 * reason.
 */
@RestController
public class ReportsController {

    /** Rolling window lengths in days, today inclusive. See ADR §0. */
    private static final int TODAY_DAYS = 1;
    private static final int WEEK_DAYS = 7;
    private static final int MONTH_DAYS = 30;

    /** The contract's {@code Limit} parameter default and ceiling. */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ReportQueries queries;

    public ReportsController(ReportQueries queries) {
        this.queries = queries;
    }

    /**
     * The home screen summary.
     *
     * <p>{@code period} scopes {@code salesTotal}, {@code salesCount},
     * {@code grossProfit} and {@code topProducts}. It deliberately does not
     * scope {@code salesTrend}, which is always the last 14 days —
     * {@code period=today} would otherwise produce a one-point "trend". ADR §3.
     *
     * <p>The stock counts and {@code inventoryValue} are current state and have
     * no period at all.
     */
    @GetMapping("/reports/dashboard")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public DashboardSummary dashboard(
            @RequestParam(required = false, defaultValue = "week") String period) {

        String normalised = normalise(period);
        int days = switch (normalised) {
            case "today" -> TODAY_DAYS;
            case "week" -> WEEK_DAYS;
            case "month" -> MONTH_DAYS;
            default -> throw new BadRequestException(
                    "Unknown period '" + period + "'. Expected one of: today, week, month.");
        };
        return queries.dashboard(normalised, days);
    }

    /**
     * Sales totals bucketed over an explicit, inclusive date range.
     *
     * <p>{@code from} and {@code to} are both required by the contract, so
     * there is no default window to argue about. An inverted range is a 400
     * rather than an empty list: an empty list is a true answer to "what
     * happened between March and February" only by accident, and it looks
     * exactly like a quiet shop.
     */
    @GetMapping("/reports/sales-summary")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public SalesSummaryResponse salesSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "day") String groupBy) {

        String unit = normalise(groupBy);
        if (!List.of("day", "week", "month").contains(unit)) {
            throw new BadRequestException(
                    "Unknown groupBy '" + groupBy + "'. Expected one of: day, week, month.");
        }
        if (from.isAfter(to)) {
            throw new BadRequestException(
                    "from (" + from + ") is after to (" + to + ").");
        }
        return new SalesSummaryResponse(queries.salesSummary(from, to, unit));
    }

    /**
     * What the stock on hand is worth.
     *
     * <p><strong>{@code locationId} omitted means ALL locations here</strong>,
     * which contradicts {@code components/parameters/LocationIdQuery}'s
     * "Defaults to the tenant's default location". Flagged rather than resolved
     * silently (CLAUDE.md §1) and the parameter is inlined on this path in the
     * contract with its own description. That shared default was written for
     * stock listings, where one location is a reasonable view; for a valuation
     * it is a number that is wrong without saying so — a two-location shop
     * asking what its stock is worth would be told the worth of some of it.
     *
     * <p>{@code asOf} in the past replays the ledger. See
     * {@link ReportQueries#inventoryValuation} and ADR §2 — including what the
     * replay does <em>not</em> cover, which is the prices.
     */
    @GetMapping("/reports/inventory-valuation")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public InventoryValuation inventoryValuation(
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        LocalDate today = queries.todayForTenant();
        if (asOf != null && asOf.isAfter(today)) {
            throw new BadRequestException(
                    "asOf (" + asOf + ") is in the future; the latest valuation is " + today + ".");
        }
        LocalDate effective = asOf == null ? today : asOf;

        // Replay only when the caller actually asked for the past. Valuing today
        // from product_stock is O(products); replaying the ledger is
        // O(movements), and the dashboard makes this call on every screen open.
        // The two must agree at `today` — ValuationReplayHttpTest asserts it,
        // because a branch nothing compares is two implementations of one number.
        boolean replay = effective.isBefore(today);
        return queries.inventoryValuation(locationId, effective, replay);
    }

    /**
     * Best and worst movers, ranked by units sold on both this endpoint and the
     * dashboard's {@code topProducts} — one ranking key, never two. ADR §4.
     *
     * <p>Dates are optional here (unlike the sales summary, where the contract
     * marks them required), so an omitted range means the last 30 days ending
     * today — long enough for a slow mover to have had a fair chance to move.
     */
    @GetMapping("/reports/top-products")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    public List<TopProduct> topProducts(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "best") String order,
            @RequestParam(required = false) Integer limit) {

        String direction = normalise(order);
        if (!List.of("best", "worst").contains(direction)) {
            throw new BadRequestException(
                    "Unknown order '" + order + "'. Expected one of: best, worst.");
        }

        LocalDate today = queries.todayForTenant();
        LocalDate effectiveTo = to == null ? today : to;
        LocalDate effectiveFrom = from == null
                ? effectiveTo.minusDays(ReportQueries.TOP_PRODUCTS_DEFAULT_DAYS - 1L)
                : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new BadRequestException(
                    "from (" + effectiveFrom + ") is after to (" + effectiveTo + ").");
        }

        return queries.topProducts(effectiveFrom, effectiveTo, direction, clampLimit(limit));
    }

    /**
     * Trim and lower-case, so a client sending {@code OPEN}-style constants gets
     * an answer rather than a 500 out of a PostgreSQL enum cast. Null is left
     * alone; the {@code defaultValue}s above mean it cannot reach here.
     */
    private static String normalise(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
