package com.example.inventory.sales;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.sales.SalesDtos.SaleDetail;
import com.example.inventory.sales.SalesDtos.SaleWriteRequest;

import jakarta.validation.Valid;

/**
 * {@code POST /sales} — the one sales endpoint M3 needs.
 *
 * <p>Pulled forward from M4 so the Android slice has something real to record a
 * sale against. Void, returns and listing stay in M4.
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

    @PostMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk')")
    ResponseEntity<SaleDetail> record(@Valid @RequestBody SaleWriteRequest request) {
        var recorded = sales.record(request);
        return ResponseEntity
                .status(recorded.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(recorded.sale());
    }
}
