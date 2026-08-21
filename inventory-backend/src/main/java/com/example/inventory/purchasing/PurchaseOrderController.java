package com.example.inventory.purchasing;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderDetail;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderPage;
import com.example.inventory.purchasing.PurchaseOrderDtos.PurchaseOrderWriteRequest;
import com.example.inventory.purchasing.PurchaseOrderDtos.ReceiptRequest;
import com.example.inventory.web.NotFoundException;

import jakarta.validation.Valid;

/**
 * {@code /purchase-orders} — draft, submit, read, list. Receiving is
 * {@code GoodsReceiptController}, added in the next step.
 *
 * <h2>Role gate</h2>
 *
 * <p>Creating and submitting a purchase order are {@code owner}/{@code manager}
 * only. The contract's {@code UserRole} table gives manager "catalog,
 * purchasing, reports, adjustments" — purchasing is explicitly theirs — and
 * gives clerk "record sales, receive stock, count", which names receiving
 * stock but not deciding what to order or committing to a supplier. That is
 * also why receiving (the next step) is gated differently from these two: a
 * clerk's job description includes one and not the other, and the gate
 * should say so rather than lump every purchasing endpoint together.
 *
 * <p>Reading is every role, same reasoning as {@code SalesController}: viewer
 * is "read only", and seeing a purchase order grants nothing a viewer
 * couldn't already ask a colleague to look up.
 */
@RestController
@RequestMapping("/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService receipts;

    public PurchaseOrderController(PurchaseOrderService purchaseOrders, GoodsReceiptService receipts) {
        this.purchaseOrders = purchaseOrders;
        this.receipts = receipts;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<PurchaseOrderPage> list(@RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) UUID supplierId) {
        return ResponseEntity.ok(purchaseOrders.list(cursor, limit, status, supplierId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<PurchaseOrderDetail> create(@Valid @RequestBody PurchaseOrderWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(purchaseOrders.create(request));
    }

    @GetMapping("/{poId}")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk', 'viewer')")
    ResponseEntity<PurchaseOrderDetail> get(@PathVariable UUID poId) {
        return purchaseOrders.read(poId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NotFoundException("No such purchase order"));
    }

    /**
     * Stamps {@code orderedAt}, which starts the lead-time clock the ADR's
     * reorder-point formula reads from (docs/adr/forecasting.md §2).
     */
    @PostMapping("/{poId}/submit")
    @PreAuthorize("hasAnyRole('owner', 'manager')")
    ResponseEntity<PurchaseOrderDetail> submit(@PathVariable UUID poId) {
        return ResponseEntity.ok(purchaseOrders.submit(poId));
    }

    /**
     * Receives stock against the order, in part or in full — the contract's
     * "supports partial deliveries". {@code owner}, {@code manager} AND
     * {@code clerk}: the contract's {@code UserRole} table names "receive
     * stock" as explicitly clerk's job, unlike deciding what to order or
     * submitting the order, which stay owner/manager above.
     */
    @PostMapping("/{poId}/receipts")
    @PreAuthorize("hasAnyRole('owner', 'manager', 'clerk')")
    ResponseEntity<PurchaseOrderDetail> receive(@PathVariable UUID poId,
                                                @Valid @RequestBody ReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(receipts.receive(poId, request));
    }
}
