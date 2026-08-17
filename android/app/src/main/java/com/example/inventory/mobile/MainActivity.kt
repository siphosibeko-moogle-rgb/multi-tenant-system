package com.example.inventory.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inventory.mobile.auth.SessionManager
import com.example.inventory.mobile.ui.LoginScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Hosts the one screen M3 has so far.
 *
 * Navigation is a single conditional rather than a NavHost: with two
 * destinations (signed out, signed in) a graph would be more machinery than
 * the milestone needs. The product list replaces the placeholder in step 3.
 *
 * The signed-in/signed-out decision reads [SessionManager.state], which is also
 * what the token authenticator flips when a refresh fails — so an expired
 * session that cannot be refreshed sends the user back here rather than leaving
 * them staring at a screen that never loads.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by session.state.collectAsStateWithLifecycle()
                    when (state) {
                        is SessionManager.State.LoggedOut -> LoginScreen()
                        is SessionManager.State.LoggedIn -> SignedInPlaceholder(
                            state = state as SessionManager.State.LoggedIn,
                            onSignOut = { session.signOut() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignedInPlaceholder(
    state: SessionManager.State.LoggedIn,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Signed in", style = MaterialTheme.typography.headlineMedium)
        if (state.displayName.isNotBlank()) {
            Text(state.displayName, style = MaterialTheme.typography.bodyLarge)
        }
        if (state.tenantName.isNotBlank()) {
            Text(state.tenantName, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Product list arrives in step 3.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onSignOut, modifier = Modifier.padding(top = 24.dp)) {
            Text("Sign out")
        }
    }
}
