package com.example.inventory.sales;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.sales.SalesDtos.SaleDetail;
import com.example.inventory.sales.SalesDtos.SalePage;
import com.example.inventory.sales.SalesDtos.VoidRequest;
import com.example.inventory.sales.SalesDtos.ReturnRequest;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

/**
 * {@code POST /sales} — the one sales endpoint M3 needs.
 *
 * <p>Pulled forward from M4 so the Android slice has something real to record a
 * sale against.
 *
 * <h2>Role gate</h2>
 *
 * <p>{@code owner}, {@code manager} and {@code clerk}. The contract's
 * {@code UserRole} table defines clerk as "record sales, receive stock, count" —
 * recording sales is the clerk's whole job, so a gate that excluded them would
 * be wrong in the most obvious way possible. {@code viewer} is "read only" and
 * is refused, which {@code SaleRoleTest} asserts rather than leaving to the
 * absence of a happy-path test.
 *
 * <h2>201 or 200</h2>
 *
 * <p>201 when this call recorded the sale, 200 when it was an idempotent replay
 * of one already recorded — as the contract specifies. The distinction is
 * visible to the client on purpose: a 200 means "your retry worked and nothing
 * moved twice", which is exactly what a cashier on a flaky link needs to know.
 */
@RestController
@RequestMapping("/sales")
public class SalesController {

    private final SaleService sales;

    public SalesController(SaleService sales) {
        this.sales = sales;
    }

    /**
     * Read access is every role, including {@code viewer} — the contract's
     * {@code UserRole} table gives viewer "read only", which means exactly
     * this: seeing sales without being able to record, void or return one.
     * A token with no {@code role} claim at all is still refused, same as
     * every other gated endpoint.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<SalePage> list(@RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) String status) {
        return ResponseEntity.ok(sales.list(cursor, limit, from, to, status));
    }

    @GetMapping("/{saleId}")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<SaleDetail> get(@PathVariable UUID saleId) {
        return sales.read(saleId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such sale"));
    }

    /**
     * @param idempotencyKey the {@code Idempotency-Key} header. Declared as
     *                       {@code UUID} rather than {@code String} so Spring's
     *                       own converter rejects a malformed value before this
     *                       method runs — the existing catch-all in
     *                       {@code GlobalExceptionHandler} already turns that
     *                       rejection into a proper problem response, the same
     *                       way it already does for a path variable or any
     *                       other UUID-typed input, so nothing new was needed
     *                       to keep this on the RFC 9457 shape (CLAUDE.md §4).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk')")
    ResponseEntity<SaleDetail> record(
            @Valid @RequestBody SaleWriteRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        var recorded = sales.record(request, idempotencyKey);
        return ResponseEntity
                .status(recorded.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(recorded.sale());
    }

    /**
     * Voids a sale and returns its outstanding stock.
     *
     * <p>200, not 201: the contract says so, and it is right — nothing new is
     * created that the caller can address. The compensating movements are
     * consequences of the void, not resources of their own.
     *
     * <p>Owner and manager only. A clerk records sales; reversing one is the
     * decision the till is protected from, and it is the same reasoning that
     * keeps adjustments away from clerks in {@code InventoryController} — an
     * operation with no external event behind it, that moves stock and money.
     * The contract's UserRole descriptions give clerk "record sales, receive
     * stock, count", which does not include undoing them.
     */
    @PostMapping("/{saleId}/void")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<SaleDetail> voidSale(@PathVariable UUID saleId,
                                        @RequestBody(required = false) VoidRequest request) {
        // The contract marks the body optional, so a void with no reason at all
        // must work rather than 400 on a missing object.
        String reason = request == null ? null : request.reason();
        return ResponseEntity.ok(sales.voidSale(saleId, reason));
    }

    /**
     * Returns part of a sale: some or all of what is still outstanding, with
     * an optional {@code restock: false} for damaged goods that get refunded
     * but never go back on the shelf.
     *
     * <p>201, not 200: unlike void, this creates something new the caller
     * could conceivably address later — the return itself — even though the
     * response body is the updated sale rather than the return record. The
     * contract makes the same call.
     *
     * <p>Owner and manager only, for the same reason as void: this reverses a
     * sale rather than recording one, and the contract's UserRole table gives
     * clerk "record sales, receive stock, count" — not undoing them.
     */
    @PostMapping("/{saleId}/returns")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<SaleDetail> returnSale(@PathVariable UUID saleId,
                                          @Valid @RequestBody ReturnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sales.returnSale(saleId, request));
    }
}
