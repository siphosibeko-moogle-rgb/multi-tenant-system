package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.PurchasingApi
import com.example.inventory.api.models.PurchaseOrderWriteRequest
import com.example.inventory.api.models.PurchaseOrderWriteRequestLinesInner
import com.example.inventory.api.models.ReorderRecommendation
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CreateOrderViewModel @Inject constructor(
    private val purchasing: PurchasingApi,
) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        val error: ApiError? = null,
        val createdPoNumber: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Creates a draft purchase order.
     *
     * <h2>All validation stays server-side</h2>
     *
     * The quantity is sent as typed. Nothing here checks it is positive, or a
     * number the supplier will accept, or within any limit — `POST
     * /purchase-orders` decides, and a 422 comes back as a readable message.
     *
     * A client-side copy of those rules is a second implementation that drifts,
     * and the drift is invisible: it either refuses something the server would
     * have taken, or lets through something the server refuses anyway. The only
     * check made here is that the field parses as a number at all, because
     * `BigDecimal` cannot be constructed otherwise and a crash is not a
     * validation message.
     */
    fun submit(supplierId: UUID, productId: UUID, quantity: String, locationId: UUID?) {
        if (_state.value.submitting) {
            return
        }
        val parsed = quantity.trim().toBigDecimalOrNull()
        if (parsed == null) {
            _state.value = UiState(error = ApiError("Enter the quantity as a number."))
            return
        }

        _state.value = UiState(submitting = true)
        viewModelScope.launch {
            _state.value = try {
                val response = purchasing.purchaseOrdersPost(
                    PurchaseOrderWriteRequest(
                        supplierId = supplierId,
                        lines = listOf(
                            PurchaseOrderWriteRequestLinesInner(
                                productId = productId,
                                quantityOrdered = parsed,
                            )
                        ),
                        locationId = locationId,
                    )
                )
                if (response.isSuccessful) {
                    UiState(createdPoNumber = response.body()?.poNumber)
                } else {
                    UiState(error = response.toApiError())
                }
            } catch (e: Exception) {
                UiState(error = ApiError.fromNetworkFailure(e))
            }
        }
    }
}

/**
 * Create a purchase order from a reorder recommendation.
 *
 * Product and quantity arrive pre-filled from the recommendation the shop owner
 * was looking at — that is the whole point of the shortcut. The quantity stays
 * editable, because the recommendation is advice and the person placing the
 * order may know something the forecast does not (a promotion coming, a pack
 * size, cash this week).
 *
 * A recommendation without a supplier cannot become an order: `supplierId` is
 * required by `PurchaseOrderWriteRequest`. That is stated on screen rather than
 * failing on submit, since it is a thing the owner can fix — by putting a
 * supplier against the product.
 */
@Composable
fun CreateOrderScreen(
    recommendation: ReorderRecommendation,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: CreateOrderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var quantity by remember {
        mutableStateOf(recommendation.recommendedQty.stripTrailingZeros().toPlainString())
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Create order", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text(recommendation.name, style = MaterialTheme.typography.titleMedium)
        Text(recommendation.sku, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        recommendation.supplierName?.let {
            Text("Supplier: $it", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))

        if (recommendation.supplierId == null) {
            Text(
                "This product has no supplier on file, so an order cannot be created for it " +
                    "yet. Add a supplier to the product first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text("Quantity to order") },
            singleLine = true,
            supportingText = {
                Text("Suggested ${plain(recommendation.recommendedQty)} — change it if you know better")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))

        state.createdPoNumber?.let { poNumber ->
            Text(
                "Order $poNumber created as a draft.",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@Column
        }

        Button(
            onClick = {
                viewModel.submit(
                    supplierId = recommendation.supplierId,
                    productId = recommendation.productId,
                    quantity = quantity,
                    locationId = recommendation.locationId,
                )
            },
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text("Create draft order")
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(
                error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            error.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
