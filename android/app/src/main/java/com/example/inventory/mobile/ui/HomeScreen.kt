package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val forecasting: ForecastingApi,
) : ViewModel() {

    /**
     * Loading, loaded and failed are separate states rather than a loaded state
     * with an empty list and an error field.
     *
     * The reason is the requirement this screen is most likely to get wrong: an
     * empty list and a failed request must never look the same. "Nothing needs
     * reordering" is good news; "we could not ask" is not, and a shop owner who
     * cannot tell them apart will eventually trust the first when it was really
     * the second.
     */
    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(
            val needsAttention: Int,
            val top: List<ReorderRecommendation>,
        ) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                // status is deliberately omitted rather than passed as the
                // generated StatusReorderRecommendationsGet.OPEN.
                //
                // Retrofit renders a @Query enum with toString(), which for the
                // generated Kotlin enum is the CONSTANT NAME — so the default
                // value goes on the wire as "OPEN" while the contract and the
                // database enum are lower case, and the request 500s. Sending
                // nothing lets the server apply its own documented default of
                // "open", which is what this screen wants anyway.
                val response = forecasting.reorderRecommendationsGet(limit = 50, status = null)
                if (!response.isSuccessful) {
                    UiState.Failed(response.toApiError())
                } else {
                    val items = response.body()?.items.orEmpty()
                    UiState.Loaded(
                        // The count of what the server returned, not a figure
                        // derived from anything. The server decides what needs
                        // reordering; this only reports how many it named.
                        needsAttention = items.size,
                        top = items.sortedBy { urgencyRank(it.urgency) }.take(3),
                    )
                }
            } catch (e: Exception) {
                UiState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }

    /**
     * Orders the list by the urgency the SERVER assigned — this is presentation,
     * not judgement. The ranking never decides that something is urgent; it only
     * decides what to show first among things the server already called urgent.
     */
    private fun urgencyRank(urgency: ReorderRecommendation.Urgency): Int = when (urgency) {
        ReorderRecommendation.Urgency.CRITICAL -> 0
        ReorderRecommendation.Urgency.HIGH -> 1
        ReorderRecommendation.Urgency.NORMAL -> 2
    }
}

/**
 * The home screen.
 *
 * <h2>What is deliberately NOT here: today's sales total</h2>
 *
 * It was asked for, and it is not shown, because there is nowhere honest to get
 * it. `GET /reports/dashboard` carries `salesTotal` and is **not implemented** —
 * it is M8 (Reporting), and its `DashboardSummary` requires ten fields including
 * gross profit, inventory value, a trend series and top products, so a stub
 * would either break the contract's `required` list or invent numbers.
 *
 * The alternative — fetching `GET /sales` and summing `totalAmount` in Kotlin —
 * is exactly the local calculation this round forbids. A total computed on the
 * phone is a second implementation of a figure the server will later compute
 * differently (net of returns? gross or after discount?), and the two would
 * disagree without either being obviously wrong.
 *
 * So the tile is absent rather than approximate. See the phase report: this is
 * the one Home item that needs M8's dashboard endpoint first.
 */
@Composable
fun HomeScreen(
    onRecordSale: () -> Unit,
    onSeeAllReorders: (() -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Home", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onRecordSale,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Record a sale")
        }
        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            is HomeViewModel.UiState.Loading ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading…")
                }

            is HomeViewModel.UiState.Failed ->
                Column(modifier = Modifier.fillMaxWidth()) {
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

            is HomeViewModel.UiState.Loaded -> {
                Text(
                    "${current.needsAttention} " +
                        if (current.needsAttention == 1) "product needs attention"
                        else "products need attention",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))

                if (current.top.isEmpty()) {
                    // Says what is true, and it is good news — distinct from the
                    // failure branch above, which says something else entirely.
                    Text(
                        "No products currently need reordering.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(current.top) { recommendation ->
                            HomeRecommendationCard(recommendation)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    // Only rendered once it leads somewhere. A button that does
                    // nothing teaches people not to press buttons.
                    onSeeAllReorders?.let { seeAll ->
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = seeAll, modifier = Modifier.fillMaxWidth()) {
                            Text("See all reorders")
                        }
                    }
                }
            }
        }
    }
}

/**
 * One recommendation, summarised.
 *
 * Every figure comes straight from the response — quantities, urgency and the
 * rationale the server wrote. Nothing here recomputes or rephrases them.
 */
@Composable
private fun HomeRecommendationCard(recommendation: ReorderRecommendation) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(recommendation.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${recommendation.urgency.value} · ${plain(recommendation.quantityOnHand)} left " +
                    "· order ${plain(recommendation.recommendedQty)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Renders a decimal the way a person writes it: 84.299 stays, 14.000 becomes 14. */
internal fun plain(value: java.math.BigDecimal): String =
    value.stripTrailingZeros().toPlainString()
