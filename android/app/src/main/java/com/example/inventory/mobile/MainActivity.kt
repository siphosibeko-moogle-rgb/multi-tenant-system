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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inventory.mobile.auth.SessionManager
import com.example.inventory.mobile.ui.HomeScreen
import com.example.inventory.mobile.ui.LoginScreen
import com.example.inventory.mobile.ui.ProductListScreen
import com.example.inventory.mobile.ui.SignUpScreen
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

                    // Which signed-out screen to show. Deliberately not part of
                    // SessionManager's state: whether the user is looking at
                    // sign-in or sign-up is a UI concern, and putting it in the
                    // session would mean the network layer's expiry handler
                    // could land someone on a sign-up form.
                    var showSignUp by rememberSaveable { mutableStateOf(false) }

                    when (state) {
                        is SessionManager.State.LoggedOut ->
                            if (showSignUp) {
                                SignUpScreen(onBackToSignIn = { showSignUp = false })
                            } else {
                                LoginScreen(onCreateAccount = { showSignUp = true })
                            }

                        is SessionManager.State.LoggedIn -> {
                            // Reset, so signing out later lands on sign-in
                            // rather than back on the sign-up form the user
                            // just completed.
                            showSignUp = false

                            // Still a conditional rather than a NavHost. Phase 3
                            // introduces role-aware bottom navigation and a real
                            // graph; adding one now would be machinery for two
                            // destinations, and it would be rewritten then.
                            var onSell by rememberSaveable { mutableStateOf(false) }
                            if (onSell) {
                                ProductListScreen(
                                    tenantName = (state as SessionManager.State.LoggedIn).tenantName,
                                    onSignOut = { session.signOut() },
                                )
                            } else {
                                HomeScreen(onRecordSale = { onSell = true })
                            }
                        }
                    }
                }
            }
        }
    }
}
