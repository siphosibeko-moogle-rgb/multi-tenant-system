package com.example.inventory.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.models.Product
import com.example.inventory.api.models.SaleWriteRequest
import com.example.inventory.api.models.SaleWriteRequestLinesInner
import com.example.inventory.mobile.net.ApiError
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

        // Product.id is nullable in the generated model because the contract's
        // Product schema marks no field required, while SaleWriteRequest's line
        // DOES require productId. So a client has to null-check an id that
        // always exists in practice. Noted as a contract finding rather than
        // papered over with !!, which would be a crash on a malformed response.
        val productId = product.id ?: return

        _state.value = current.copy(submitting = true, error = null)

        viewModelScope.launch {
            try {
                val response = sales.salesPost(
                    SaleWriteRequest(
                        lines = listOf(
                            SaleWriteRequestLinesInner(
                                productId = productId,
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

                _state.value = _state.value.copy(
                    submitting = false,
                    error = null,
                    recordedSaleNumber = response.body()?.saleNumber ?: "recorded",
                    // Resolved: the next sale gets a fresh key.
                    pendingRequestId = null,
                )
            } catch (e: Exception) {
                // A timeout is precisely the case the idempotency key exists for:
                // the request may have arrived. Keeping the key is what makes the
                // retry safe.
                _state.value = _state.value.copy(
                    submitting = false,
                    error = ApiError.fromNetworkFailure(e),
                )
            }
        }
    }
}
