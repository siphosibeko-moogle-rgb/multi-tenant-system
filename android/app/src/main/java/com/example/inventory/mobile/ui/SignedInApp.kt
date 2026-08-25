package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.inventory.api.models.ReorderRecommendation
import java.util.UUID

/**
 * Everything behind a signed-in session, with role-aware bottom navigation.
 *
 * <h2>Each tab reloads when you arrive on it</h2>
 *
 * `loadKey` is bumped on every tab selection and passed down as the key of each
 * screen's `LaunchedEffect`, so switching to a tab refetches rather than showing
 * whatever it last held.
 *
 * That is not gold-plating. Phase 2 left a concrete bug: dismissing a
 * recommendation on Reorders left Home still reading "1 product needs
 * attention", because Home had loaded on first entry and nothing told it the
 * world had changed. Two screens showing different counts of the same thing is
 * the same class of contradiction as the `stockState` disagreement between two
 * endpoints — and refreshing on arrival is the fix that belongs here, in
 * navigation, rather than a special case wiring Reorders to poke Home.
 */
@Composable
fun SignedInApp(
    role: String?,
    tenantName: String,
    onSignOut: () -> Unit,
) {
    val tabs = tabsFor(role)
    var selected by remember(role) { mutableStateOf(Tab.HOME) }
    var loadKey by remember { mutableStateOf(0) }

    // Screens reached from a tab rather than being one.
    var detail by remember { mutableStateOf<Pair<UUID, String>?>(null) }
    var ordering by remember { mutableStateOf<ReorderRecommendation?>(null) }

    // A role change can remove the tab currently selected — a clerk cannot be
    // left standing on Reorders. Falls back to Home rather than to whatever
    // happens to be first.
    LaunchedEffect(tabs) {
        if (selected !in tabs) {
            selected = Tab.HOME
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab && detail == null && ordering == null,
                        onClick = {
                            // Leaving any pushed screen, so tapping the tab you
                            // are already on returns to its root.
                            detail = null
                            ordering = null
                            selected = tab
                            loadKey++
                        },
                        icon = {},
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)

        val currentOrder = ordering
        val currentDetail = detail

        when {
            currentOrder != null -> androidx.compose.foundation.layout.Box(content) {
                CreateOrderScreen(
                    recommendation = currentOrder,
                    onDone = {
                        ordering = null
                        // The order changes nothing about the recommendation
                        // until a recompute, but the list should still be
                        // re-read rather than trusted from before the round trip.
                        loadKey++
                    },
                    onBack = { ordering = null },
                )
            }

            currentDetail != null -> androidx.compose.foundation.layout.Box(content) {
                ProductDetailScreen(
                    productId = currentDetail.first,
                    productName = currentDetail.second,
                    onBack = { detail = null },
                )
            }

            else -> androidx.compose.foundation.layout.Box(content) {
                when (selected) {
                    Tab.HOME -> HomeScreen(
                        loadKey = loadKey,
                        onRecordSale = { selected = Tab.SELL; loadKey++ },
                        onSeeAllReorders =
                            if (Tab.REORDERS in tabs) {
                                { selected = Tab.REORDERS; loadKey++ }
                            } else {
                                null
                            },
                    )

                    Tab.SELL, Tab.STOCK -> ProductListScreen(
                        tenantName = tenantName,
                        onSignOut = onSignOut,
                        onOpenDetail = { id, name -> detail = id to name },
                        loadKey = loadKey,
                    )

                    Tab.REORDERS -> ReordersScreen(
                        loadKey = loadKey,
                        onCreateOrder = { ordering = it },
                    )

                    Tab.MORE -> MoreScreen(role = role, onSignOut = onSignOut)
                }
            }
        }
    }
}
