package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.ForecastingApi
import com.example.inventory.api.models.ReorderRecommendation
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ReordersViewModel @Inject constructor(
    private val forecasting: ForecastingApi,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(
            val items: List<ReorderRecommendation>,
            /** Set while a dismiss is in flight, so its button can be disabled. */
            val dismissing: UUID? = null,
            /** A dismiss that failed. The list is still valid; only the action failed. */
            val actionError: ApiError? = null,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                // status omitted deliberately — see HomeViewModel. The generated
                // enum's default serialises upper case and the server's own
                // default is "open", which is what this screen wants.
                val response = forecasting.reorderRecommendationsGet(limit = 50, status = null)
                if (response.isSuccessful) {
                    UiState.Loaded(response.body()?.items.orEmpty())
                } else {
                    UiState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                UiState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    /**
     * Dismisses one recommendation.
     *
     * <h2>The row is removed only after the server confirms</h2>
     *
     * No optimistic removal. A dismiss that failed — a clerk's token lacking
     * permission, a lost connection — would otherwise take the row off the
     * screen while it stayed open on the server, so the shop owner believes
     * they have dealt with it and it reappears on the next load with no
     * explanation. Waiting for the 204 costs a moment and means the screen
     * never disagrees with the server about what was decided.
     *
     * A failure keeps the list intact and reports the action's error
     * separately, because the list itself is still perfectly good.
     */
    fun dismiss(recommendationId: UUID) {
        val current = _state.value as? UiState.Loaded ?: return
        if (current.dismissing != null) {
            return
        }
        _state.value = current.copy(dismissing = recommendationId, actionError = null)

        viewModelScope.launch {
            try {
                // The body is passed explicitly even though the contract makes
                // it optional and the generated parameter defaults to null.
                //
                // Retrofit refuses a null @Body outright — "Body parameter value
                // must not be null" — so relying on that default throws inside
                // the client and the request never leaves the phone. It surfaced
                // as "Something went wrong. Try again." with nothing in the
                // server log at all, which is exactly what a request that was
                // never sent looks like.
                //
                // Same class of trap as the status enum on the list call: a
                // generated default that cannot actually be used.
                val response = forecasting.reorderRecommendationsRecommendationIdDismissPost(
                    recommendationId,
                    com.example.inventory.api.models.SalesSaleIdVoidPostRequest(reason = null),
                )
                val latest = _state.value as? UiState.Loaded ?: return@launch
                _state.value = if (response.isSuccessful) {
                    latest.copy(
                        items = latest.items.filterNot { it.id == recommendationId },
                        dismissing = null,
                    )
                } else {
                    latest.copy(dismissing = null, actionError = response.toApiError())
                }
            } catch (e: Exception) {
                val latest = _state.value as? UiState.Loaded ?: return@launch
                _state.value = latest.copy(
                    dismissing = null,
                    actionError = ApiError.fromNetworkFailure(e),
                )
            }
        }
    }
}

/**
 * What to reorder, how much, and why.
 *
 * Every card shows the server's own `rationale` in full. That string is where
 * M7 put the lead-time honesty ("has been taking about 5 days to deliver
 * (measured over 7 deliveries)" versus "quotes 21 days — their stated time"),
 * the seasonal caveat and the low-confidence hedge. Truncating it would drop
 * exactly the parts that qualify the number.
 */
@Composable
fun ReordersScreen(
    onBack: (() -> Unit)? = null,
    onCreateOrder: ((ReorderRecommendation) -> Unit)? = null,
    loadKey: Int = 0,
    viewModel: ReordersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Keyed on loadKey so arriving on this tab refetches. See SignedInApp.
    LaunchedEffect(loadKey) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        onBack?.let { TextButton(onClick = it) { Text("← Back") } }
        Text("Reorders", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        when (val current = state) {
            is ReordersViewModel.UiState.Loading ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading…")
                }

            is ReordersViewModel.UiState.Failed ->
                Column {
                    Text(
                        "Unable to load — check your connection.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.load() }) { Text("Try again") }
                }

            is ReordersViewModel.UiState.Loaded -> {
                current.actionError?.let { error ->
                    Text(
                        error.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (current.items.isEmpty()) {
                    // True, and good news. Distinct from the failure branch,
                    // which says something else entirely.
                    Text(
                        "No products currently need reordering.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(current.items, key = { it.id }) { recommendation ->
                            RecommendationCard(
                                recommendation = recommendation,
                                dismissing = current.dismissing == recommendation.id,
                                onDismiss = { viewModel.dismiss(recommendation.id) },
                                onCreateOrder = onCreateOrder?.let { { it(recommendation) } },
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: ReorderRecommendation,
    dismissing: Boolean,
    onDismiss: () -> Unit,
    onCreateOrder: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                recommendation.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(recommendation.sku, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            Text(
                "${plain(recommendation.quantityOnHand)} in stock · " +
                    "order ${plain(recommendation.recommendedQty)} · " +
                    recommendation.urgency.value,
                style = MaterialTheme.typography.bodyMedium,
            )
            recommendation.supplierName?.let {
                Text("Supplier: $it", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))

            // In full. See the screen's Javadoc.
            Text(recommendation.rationale, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onCreateOrder?.let {
                    OutlinedButton(onClick = it, enabled = !dismissing) { Text("Create order") }
                }
                OutlinedButton(onClick = onDismiss, enabled = !dismissing) {
                    Text(if (dismissing) "Dismissing…" else "Dismiss")
                }
            }
        }
    }
}
