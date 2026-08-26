package com.example.inventory.inventory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.inventory.InventoryDtos.AdjustmentRequest;
import com.example.inventory.inventory.InventoryDtos.MovementPage;
import com.example.inventory.inventory.InventoryDtos.StockMovement;
import com.example.inventory.inventory.InventoryDtos.StockStatusPage;
import com.example.inventory.inventory.InventoryDtos.TransferRequest;
import com.example.inventory.inventory.StockLedgerService.MovementRequest;
import com.example.inventory.web.ConflictException;
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

    /**
     * Corrects a balance.
     *
     * <p><strong>{@code Idempotency-Key} is honoured from M8.</strong> The
     * contract has declared the parameter on this path since v1 and the
     * implementation ignored it, which made CLAUDE.md §4's "any endpoint that
     * moves stock or money accepts it" true of {@code POST /sales} and false
     * here. The code was the side that disagreed with the contract.
     *
     * <p>It became load-bearing rather than tidy when M8's offline outbox
     * gained the ability to queue an adjustment: a replay whose first response
     * was lost to a dropped connection would otherwise post a SECOND movement,
     * and the ledger is append-only, so the repair is a compensating row
     * somebody has to notice is needed.
     *
     * <p>200 on a replay, 201 on a first post — the same shape
     * {@code POST /sales} uses, so a client has one rule for both.
     */
    @PostMapping("/adjustments")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<StockMovement> adjust(
            @Valid @RequestBody AdjustmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        UUID locationId = request.locationId() != null
                ? request.locationId()
                : queries.defaultLocationId().orElseThrow(() -> new ConflictException(
                        // Not 404. Nothing was looked up and not found: the
                        // request named no location and the business has none
                        // marked default, which is a state of the tenant rather
                        // than a missing resource. As a 404 it was also
                        // indistinguishable from "that product does not exist",
                        // so a client could not tell a fixable configuration
                        // problem from a bad id.
                        "This business has no default location; specify locationId",
                        "no-default-location"));

        var posted = ledger.post(new MovementRequest(
                request.productId(),
                locationId,
                "adjustment",
                request.quantityDelta(),
                request.unitCost(),
                null,
                null,
                request.reason(),
                request.occurredAt()), idempotencyKey);

        return ResponseEntity
                .status(posted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toDto(posted.movement()));
    }

    /**
     * Moves stock between two locations.
     *
     * <p>Open to {@code clerk} as well as owner and manager, unlike an
     * adjustment. A transfer records something that physically happened — a
     * crate went from the back room to the shop floor — which is squarely
     * "receive stock, count" work. An adjustment asserts the count itself was
     * wrong, which is why that one stays with the roles accountable for it.
     *
     * <p>Returns both movements, as the contract's array of two specifies. The
     * pair is written in one transaction, so a caller that receives 201 knows
     * both halves landed.
     */
    @PostMapping("/transfers")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk')")
    ResponseEntity<List<StockMovement>> transfer(@Valid @RequestBody TransferRequest request) {
        var transferred = ledger.transfer(new StockLedgerService.TransferRequest(
                request.productId(),
                request.fromLocationId(),
                request.toLocationId(),
                request.quantity(),
                request.reason(),
                UUID.randomUUID(),
                request.occurredAt()));

        return ResponseEntity.status(HttpStatus.CREATED).body(List.of(
                toDto(transferred.out()), toDto(transferred.in())));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<StockStatusPage> stock(@RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) UUID locationId,
                                          @RequestParam(required = false) String stockState) {
        return ResponseEntity.ok(queries.stock(cursor, limit, locationId, stockState));
    }

    /**
     * The service's posted-movement view, as the contract's StockMovement.
     *
     * <p>These were previously nulled out, on the reasoning that a write
     * response echoing the caller's own request need not repeat what the caller
     * just sent. That holds for the product and fails for the actor: the client
     * never sends a user id — the server reads it from the token (T1) — so
     * nulling {@code createdBy} withheld the one field the client could not have
     * known. "Who moved this stock" is the first question asked when a count
     * disagrees with the system.
     *
     * <p>{@code productName} was additionally a contract violation: the
     * StockMovement schema marks it required.
     *
     * <p>{@code referenceType} and {@code referenceId} stay null here and are
     * genuinely absent for an adjustment — it references nothing. Both are
     * nullable in the contract.
     */
    private static StockMovement toDto(StockLedgerService.PostedMovement posted) {
        return new StockMovement(
                posted.id(),
                posted.productId(),
                posted.productName(),
                posted.locationId(),
                posted.movementType(),
                posted.quantityDelta(),
                posted.balanceAfter(),
                posted.unitCost(),
                null,
                null,
                posted.reason(),
                posted.occurredAt(),
                posted.createdBy(),
                posted.createdByName());
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
