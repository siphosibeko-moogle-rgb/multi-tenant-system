package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.inventory.api.models.ForecastDetail
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    private val forecasting: ForecastingApi,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Failed(val error: ApiError) : UiState
        data class Loaded(val forecast: ForecastDetail) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(productId: UUID) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = forecasting.productsProductIdForecastGet(productId)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body == null) {
                        UiState.Failed(ApiError("The server returned an empty forecast."))
                    } else {
                        UiState.Loaded(body)
                    }
                } else {
                    UiState.Failed(response.toApiError())
                }
            } catch (e: Exception) {
                UiState.Failed(ApiError.fromNetworkFailure(e))
            }
        }
    }
}

/**
 * One product's forecast.
 *
 * <h2>The explanation is rendered as prose, at length, on purpose</h2>
 *
 * The backend writes a real sentence — what the shop sells in a week, how long
 * the shelf lasts, how long the supplier takes, and any caveat about a seasonal
 * pattern or a rough estimate. Compressing that into a stat block would throw
 * away the only part a person can actually act on, and the caveats in
 * particular exist as *words* precisely because a flag nobody renders protects
 * nobody (`docs/adr/forecasting.md` §6).
 *
 * So it gets a full-width paragraph in body type, above the numbers rather than
 * beneath them.
 *
 * <h2>insufficient_data has its own screen state</h2>
 *
 * Not a blank panel and not zeros. The server withholds `reorderPoint` and
 * `projectedStockoutOn` deliberately for a product it cannot forecast yet, and
 * it still writes an explanation saying what is missing and roughly how much
 * more is needed. Showing "0" where the server said "nothing" would be the one
 * mistake this whole milestone was built to avoid.
 */
@Composable
fun ProductDetailScreen(
    productId: UUID,
    productName: String,
    onBack: () -> Unit,
    viewModel: ProductDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(productId) { viewModel.load(productId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(productName, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        when (val current = state) {
            is ProductDetailViewModel.UiState.Loading ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading forecast…")
                }

            is ProductDetailViewModel.UiState.Failed ->
                Column {
                    Text(
                        "Unable to load this forecast — check your connection.",
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
                    OutlinedButton(onClick = { viewModel.load(productId) }) { Text("Try again") }
                }

            is ProductDetailViewModel.UiState.Loaded -> ForecastBody(current.forecast)
        }
    }
}

@Composable
private fun ForecastBody(forecast: ForecastDetail) {
    val stillLearning = forecast.method == ForecastDetail.Method.INSUFFICIENT_DATA

    // The sentence first. It is the part a person reads.
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (stillLearning) {
                Text(
                    "Still learning this product",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                forecast.explanation,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    if (stillLearning) {
        // Deliberately no numbers. The server withheld the reorder point and
        // the projected stockout date; printing zeros for them would turn "we
        // cannot say" into "the answer is nothing".
        Text(
            "There is not enough sales history yet for a reorder level. " +
                "It will appear here once there is.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Fact("History so far", "${forecast.historyDays} days")
        return
    }

    Fact("Sells about", "${forecast.avgDailyDemand.setScale(2, RoundingMode.HALF_UP)} a day")
    forecast.daysOfCover?.let {
        Fact("Stock covers", "about ${it.setScale(0, RoundingMode.HALF_UP)} days")
    }
    forecast.reorderPoint?.let {
        Fact("Reorder at", plain(it.setScale(0, RoundingMode.HALF_UP)))
    }
    forecast.projectedStockoutOn?.let {
        Fact("Expected to run out", it.toString())
    }
    Fact("Method", forecast.method.value.replace('_', ' '))
    Fact("Based on", "${forecast.historyDays} days of history")
    forecast.confidence?.let {
        Fact("Confidence", it.setScale(2, RoundingMode.HALF_UP).toPlainString())
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
