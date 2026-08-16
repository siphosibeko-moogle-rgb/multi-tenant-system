package com.example.inventory.inventory;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.inventory.InventoryDtos.AdjustmentRequest;
import com.example.inventory.inventory.InventoryDtos.MovementPage;
import com.example.inventory.inventory.InventoryDtos.StockMovement;
import com.example.inventory.inventory.InventoryDtos.StockStatusPage;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

/**
 * Stock reads, and the one endpoint that corrects a balance.
 *
 * <h2>Role gates</h2>
 *
 * <p>From the contract's {@code UserRole} descriptions, which are the source of
 * truth for what each role does:
 *
 * <ul>
 *   <li><strong>Reads</strong> — every role, including {@code viewer}, which is
 *       defined as "read only" rather than "no access".</li>
 *   <li><strong>Adjustments</strong> — {@code owner} and {@code manager} only.
 *       {@code manager} is defined as "catalog, purchasing, reports,
 *       <em>adjustments</em>"; {@code clerk} is "record sales, receive stock,
 *       count", which are all movements a clerk generates as a side effect of
 *       doing their job, not a free-hand correction of a balance. An adjustment
 *       is the one movement type with no external event behind it — it says "the
 *       number was wrong" — so it stays with the roles accountable for the
 *       count.</li>
 * </ul>
 *
 * <p>Asserted both ways, per role, in {@code InventoryRoleTest}.
 *
 * <h2>No endpoint sets an absolute quantity</h2>
 *
 * <p>Deliberately, and there must not be one (T4). A correction is a new signed
 * row, so the history of how a balance got where it is survives. "Set stock to
 * 40" destroys that; "adjust by -3, reason: damaged" does not.
 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final StockLedgerService ledger;
    private final InventoryQueries queries;

    public InventoryController(StockLedgerService ledger, InventoryQueries queries) {
        this.ledger = ledger;
        this.queries = queries;
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<StockMovement> adjust(@Valid @RequestBody AdjustmentRequest request) {
        UUID locationId = request.locationId() != null
                ? request.locationId()
                : queries.defaultLocationId().orElseThrow(() -> new NotFoundException(
                        "This business has no default location; specify locationId"));

        var posted = ledger.post(new MovementRequest(
                request.productId(),
                locationId,
                "adjustment",
                request.quantityDelta(),
                request.unitCost(),
                null,
                null,
                request.reason(),
                request.occurredAt()));

        return ResponseEntity.status(HttpStatus.CREATED).body(new StockMovement(
                posted.id(),
                posted.productId(),
                null,
                posted.locationId(),
                posted.movementType(),
                posted.quantityDelta(),
                posted.balanceAfter(),
                posted.unitCost(),
                null,
                null,
                posted.reason(),
                posted.occurredAt(),
                null,
                null));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<StockStatusPage> stock(@RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) UUID locationId,
                                          @RequestParam(required = false) String stockState) {
        return ResponseEntity.ok(queries.stock(cursor, limit, locationId, stockState));
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<MovementPage> movements(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(
                queries.movements(cursor, limit, productId, locationId, type, from, to));
    }
}
