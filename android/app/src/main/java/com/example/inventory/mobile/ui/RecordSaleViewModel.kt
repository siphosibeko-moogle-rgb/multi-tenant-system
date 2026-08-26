package com.example.inventory.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.models.Product
import com.example.inventory.api.models.SaleWriteRequest
import com.example.inventory.api.models.SaleWriteRequestLinesInner
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.offline.Outbox
import com.example.inventory.mobile.offline.OutboxCoordinator
import com.example.inventory.mobile.offline.OutboxEntry
import com.example.inventory.mobile.offline.OutboxOperation
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Records one sale of one product.
 *
 * Deliberately a single line: M3's slice is login -> list -> record a sale, and
 * a basket is M4's problem. The endpoint already takes a list, so widening this
 * later is a UI change rather than a protocol one.
 */
@HiltViewModel
class RecordSaleViewModel @Inject constructor(
    private val sales: SalesApi,
    private val outbox: Outbox,
    private val coordinator: OutboxCoordinator,
) : ViewModel() {

    data class UiState(
        val product: Product? = null,
        val quantity: String = "1",
        val submitting: Boolean = false,
        val error: ApiError? = null,
        val recordedSaleNumber: String? = null,
        /**
         * The idempotency key for the sale currently being attempted.
         *
         * Generated when the cashier taps Save and held until that sale
         * succeeds — NOT generated per request. A retry after a timeout must
         * carry the same value, because the first attempt may well have reached
         * the server and been recorded. A fresh id would record the sale twice
         * and take the stock twice, and nobody would notice until someone
         * counted the shelf.
         */
        val pendingRequestId: UUID? = null,
        /**
         * Business time, also captured at the tap.
         *
         * A retry three minutes later then reports when the sale HAPPENED rather
         * than when the network recovered. That matters beyond tidiness: the
         * tenant's daily rollup buckets by this value, so a sale tapped at 23:59
         * and retried at 00:02 still belongs to the day it was made.
         */
        val soldAt: OffsetDateTime? = null,
        /**
         * The sale was captured with no signal and is waiting in the outbox.
         *
         * Distinct from [recordedSaleNumber] on purpose: "saved, will sync" and
         * "recorded, here is the receipt number" are different promises, and a
         * cashier told the wrong one either double-sells later or thinks a sale
         * was lost. There is no sale number yet — the server assigns it.
         */
        val queuedOffline: Boolean = false,
    ) {
        val quantityOrNull: BigDecimal?
            get() = quantity.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

        val canSubmit: Boolean get() = product != null && quantityOrNull != null && !submitting
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Opens the sheet for a product, discarding any previous attempt's key. */
    fun startSale(product: Product) {
        _state.value = UiState(product = product)
    }

    fun setQuantity(quantity: String) {
        _state.value = _state.value.copy(quantity = quantity, error = null)
    }

    fun dismiss() {
        _state.value = UiState()
    }

    /**
     * Called when the cashier taps Save.
     *
     * The idempotency key and the business time are minted HERE, once, and then
     * reused by [retry] for as long as this sale is unresolved.
     */
    fun save() {
        val current = _state.value
        if (!current.canSubmit) {
            return
        }
        _state.value = current.copy(
            pendingRequestId = current.pendingRequestId ?: UUID.randomUUID(),
            soldAt = current.soldAt ?: OffsetDateTime.now(),
        )
        submit()
    }

    /** Re-sends the same sale — same key, same business time. */
    fun retry() {
        if (_state.value.pendingRequestId == null) {
            save()
        } else {
            submit()
        }
    }

    private fun submit() {
        val current = _state.value
        val product = current.product ?: return
        val quantity = current.quantityOrNull ?: return
        val requestId = current.pendingRequestId ?: return
        val soldAt = current.soldAt ?: return

        _state.value = current.copy(submitting = true, error = null)

        viewModelScope.launch {
            try {
                val response = sales.salesPost(
                    SaleWriteRequest(
                        lines = listOf(
                            SaleWriteRequestLinesInner(
                                productId = product.id,
                                quantity = quantity,
                            )
                        ),
                        clientRequestId = requestId,
                        // OffsetDateTime carries the device's UTC offset, and the
                        // generated OffsetDateTimeAdapter writes RFC 3339. The
                        // server stores the instant; the tenant's timezone decides
                        // which business day it lands in.
                        soldAt = soldAt,
                    )
                )

                if (!response.isSuccessful) {
                    _state.value = _state.value.copy(
                        submitting = false,
                        // The key is KEPT on failure. Whatever went wrong, the
                        // next attempt must be recognisable as the same sale.
                        error = response.toApiError(),
                    )
                    return@launch
                }

                // If an earlier attempt at THIS sale had been queued — a blip
                // followed by a successful in-place retry — take it back out.
                // Leaving it would be harmless to the ledger (the server
                // recognises the key and answers 200) but would show the
                // cashier a pending count that never goes down.
                outbox.remove(requestId)

                _state.value = _state.value.copy(
                    submitting = false,
                    error = null,
                    queuedOffline = false,
                    recordedSaleNumber = response.body()?.saleNumber ?: "recorded",
                    // Resolved: the next sale gets a fresh key.
                    pendingRequestId = null,
                )

                // A sale just reached the server, which is the strongest
                // evidence available that connectivity is back — so drain
                // anything captured while it was not. Costs nothing when the
                // queue is empty, which it usually is: replayAll() makes no
                // requests at all in that case.
                //
                // This is one of the only two things that make the outbox
                // drain. Without it the queue fills and never empties, which is
                // exactly how this shipped before OutboxCoordinator existed:
                // every replay test called replayAll() itself, so nothing
                // noticed the app never did.
                coordinator.syncNow()
            } catch (e: Exception) {
                // No HTTP response at all — the phone is offline, or the link
                // died mid-flight. The sale still happened: goods left the shelf
                // and money changed hands, and the one unacceptable outcome is
                // losing that record because there was no signal.
                //
                // So it goes to the outbox rather than to an error message, with
                // the SAME key and the SAME business time this attempt used. A
                // timeout is precisely the case the key exists for: the request
                // may well have arrived and only the response was lost, and the
                // server will recognise the replay as this same sale rather than
                // recording it twice.
                outbox.enqueue(
                    OutboxEntry(
                        clientRequestId = requestId,
                        operation = OutboxOperation.SALE,
                        productId = product.id,
                        quantity = quantity,
                        capturedAt = soldAt,
                    )
                )
                coordinator.refreshPendingCount()
                _state.value = _state.value.copy(
                    submitting = false,
                    error = null,
                    queuedOffline = true,
                    // THE KEY AND THE TIME ARE KEPT, not cleared.
                    //
                    // Clearing them was the first cut and it was a regression:
                    // M3 deliberately holds both across a network failure so
                    // that Retry resends the SAME sale rather than a new one.
                    // Dropping them meant a cashier who tapped Retry after a
                    // blip minted a fresh key, and the queued copy plus the
                    // retried copy were two different sales to the server —
                    // double-selling the stock, which is the exact outcome this
                    // whole feature exists to prevent. Two of M3's own tests
                    // caught it.
                    //
                    // Keeping them costs nothing: the outbox entry carries the
                    // same key, so whichever of the two gets through first wins
                    // and the other is recognised as a replay.
                )
            }
        }
    }
}
