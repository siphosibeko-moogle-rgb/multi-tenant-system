package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.models.Product
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@HiltViewModel
class ProductListViewModel @Inject constructor(
    private val catalog: CatalogApi,
) : ViewModel() {

    /**
     * All four states a list can be in, as one type.
     *
     * Modelled as an explicit state rather than a bag of booleans so that
     * "loading" and "empty" cannot both be true — which is what produces a
     * screen that says "No products yet" for a second before the first page
     * arrives, and makes a shop owner think their catalogue is gone.
     */
    data class UiState(
        val products: List<Product> = emptyList(),
        val loadingFirstPage: Boolean = true,
        val loadingNextPage: Boolean = false,
        val error: ApiError? = null,
        val nextCursor: String? = null,
    ) {
        /** Empty only once the first page has actually come back without error. */
        val isEmpty: Boolean get() = products.isEmpty() && !loadingFirstPage && error == null

        val canLoadMore: Boolean get() = nextCursor != null && !loadingNextPage && error == null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load(reset = true)
    }

    fun retry() = load(reset = true)

    /**
     * Loads a page.
     *
     * Cursor pagination exactly as the contract defines it: pass the
     * `nextCursor` from the previous page back as `cursor`, and stop when the
     * server stops sending one. The cursor is opaque — nothing here parses or
     * constructs it, which is the point of it being base64 server-side.
     */
    fun loadNextPage() {
        if (!_state.value.canLoadMore) {
            return
        }
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        val current = _state.value
        if (reset) {
            _state.value = current.copy(
                loadingFirstPage = true,
                error = null,
                products = emptyList(),
                nextCursor = null,
            )
        } else {
            _state.value = current.copy(loadingNextPage = true, error = null)
        }

        viewModelScope.launch {
            try {
                val cursor = if (reset) null else _state.value.nextCursor
                val response = catalog.productsGet(cursor = cursor, limit = PAGE_SIZE)

                if (!response.isSuccessful) {
                    fail(response.toApiError(), reset)
                    return@launch
                }

                val page = response.body()
                val newItems = page?.items.orEmpty()

                _state.value = _state.value.copy(
                    // Append, never replace, or scrolling would reset to the top
                    // of page one every time.
                    products = if (reset) newItems else _state.value.products + newItems,
                    loadingFirstPage = false,
                    loadingNextPage = false,
                    error = null,
                    nextCursor = page?.nextCursor,
                )
            } catch (e: Exception) {
                // A stopped backend arrives here as an IOException. It must not
                // escape the coroutine — an M3 acceptance criterion is that
                // killing the backend produces a readable error, not a crash.
                fail(ApiError.fromNetworkFailure(e), reset)
            }
        }
    }

    private fun fail(error: ApiError, reset: Boolean) {
        _state.value = _state.value.copy(
            loadingFirstPage = false,
            loadingNextPage = false,
            // A failed page-2 keeps page 1 on screen. Throwing away rows the
            // user is already reading, because the next page failed, would be a
            // worse answer than showing the error underneath them.
            error = error,
            products = if (reset) emptyList() else _state.value.products,
        )
    }

    private companion object {
        /**
         * Small enough that the next-page path is exercised in an ordinary shop
         * catalogue rather than only in theory. The contract allows up to 200.
         */
        const val PAGE_SIZE = 20
    }
}

@Composable
fun ProductListScreen(
    tenantName: String,
    onSignOut: () -> Unit,
    viewModel: ProductListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Fetch the next page when the last row comes into view. Watching the list
    // state rather than the scroll offset means it works the same whatever the
    // row height turns out to be.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .filter { index -> index != null && index >= state.products.size - 3 }
            .collect { viewModel.loadNextPage() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Products", style = MaterialTheme.typography.headlineSmall)
                if (tenantName.isNotBlank()) {
                    Text(tenantName, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        HorizontalDivider()

        when {
            state.loadingFirstPage -> CentredMessage { CircularProgressIndicator() }

            state.error != null && state.products.isEmpty() -> CentredMessage {
                ErrorBlock(state.error!!, onRetry = viewModel::retry)
            }

            state.isEmpty -> CentredMessage {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No products yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Products you add will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.products, key = { it.id.toString() }) { product ->
                    ProductRow(product)
                    HorizontalDivider()
                }

                if (state.loadingNextPage) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                }

                // A page-2 failure shows underneath the rows already on screen,
                // rather than replacing them.
                state.error?.let { error ->
                    item {
                        Box(modifier = Modifier.padding(16.dp)) {
                            ErrorBlock(error, onRetry = viewModel::loadNextPage)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(product: Product) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(product.name.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            Text(
                product.sku.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                // stripTrailingZeros so a numeric(14,3) column does not show
                // "12.000" to someone counting tins on a shelf.
                product.quantityOnHand?.stripTrailingZeros()?.toPlainString() ?: "—",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                product.stockState?.value.orEmpty().replace('_', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = if (product.stockState == Product.StockState.OUT_OF_STOCK) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun ErrorBlock(error: ApiError, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            error.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        error.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun CentredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
