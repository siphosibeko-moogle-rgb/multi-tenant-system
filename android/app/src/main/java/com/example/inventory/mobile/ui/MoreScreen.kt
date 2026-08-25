package com.example.inventory.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The More section: everything that is real but not a daily task.
 *
 * <h2>Scoped by role, and by what actually exists</h2>
 *
 * Two different filters, and it is worth keeping them apart:
 *
 * - **Role.** Users is owner-only, matching the backend's owner-gated writes on
 *   `/users` (see [canManageUsers]). Hiding it is navigation courtesy; the
 *   server still refuses.
 * - **Built or not.** Sales history, purchase orders and suppliers have
 *   endpoints (`GET /sales`, `GET /purchase-orders`, `GET /suppliers/{id}`) but
 *   no screens yet in this pass. They are listed as coming rather than omitted,
 *   because a menu that silently lacks them tells the shop owner the feature
 *   does not exist, when the truth is that the screen has not been built.
 *
 * The second filter is the one that would normally get fudged — either by
 * showing entries that dead-end, or by hiding them and quietly redefining the
 * scope. Saying "not built yet" on the row is the honest third option.
 */
@Composable
fun MoreScreen(
    role: String?,
    onSignOut: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("More", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            role?.replaceFirstChar { it.uppercase() } ?: "Signed in",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        MoreRow("Sales history", "Every sale, newest first", built = false)
        MoreRow("Purchase orders", "What you have ordered and received", built = false)
        MoreRow("Suppliers", "Who you buy from, and their lead times", built = false)

        if (canManageUsers(role)) {
            MoreRow("Users", "Invite staff and set what they can do", built = false)
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, built: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = built) { }
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            // The honest label. See the screen's Javadoc: the endpoint exists,
            // the screen does not, and a shop owner deserves to know which.
            if (built) subtitle else "$subtitle — screen not built yet",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    HorizontalDivider()
}
