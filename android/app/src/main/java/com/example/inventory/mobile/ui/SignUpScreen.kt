package com.example.inventory.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inventory.mobile.auth.SessionManager
import com.example.inventory.mobile.net.ApiError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SignUpViewModel @Inject constructor(
    private val session: SessionManager,
) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        val error: ApiError? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun submit(
        businessName: String,
        slug: String,
        ownerName: String,
        ownerEmail: String,
        ownerPassword: String,
    ) {
        if (_state.value.submitting) {
            // Same reasoning as LoginViewModel: a second tap while the first
            // request is in flight would try to create a second business, and
            // the second attempt would collide with the first on the slug —
            // reporting "that name is taken" about the user's own tenant.
            return
        }
        _state.value = UiState(submitting = true)
        viewModelScope.launch {
            val error = session.register(
                businessName = businessName,
                slug = slug,
                ownerName = ownerName,
                ownerEmail = ownerEmail,
                ownerPassword = ownerPassword,
            )
            _state.value = UiState(submitting = false, error = error)
            // No navigation on success: SessionManager flips its state to
            // LoggedIn and MainActivity follows it, exactly as login does. One
            // path into the signed-in app rather than two.
        }
    }
}

/**
 * Sign up a new business and its first owner.
 *
 * One form, one request. The contract's `register-tenant` takes every field at
 * once and answers with a full token pair, so a multi-step wizard would be
 * ceremony around a single call — and there is no verification step to wait for
 * (see [SessionManager.register]).
 *
 * Deliberately plain Material 3, like the rest of this pass. What it does have
 * to get right is the slug: it is the only field whose rules the server enforces
 * strictly and the only one a user cannot guess the purpose of, so it is
 * explained rather than labelled.
 */
@Composable
fun SignUpScreen(
    onBackToSignIn: () -> Unit,
    viewModel: SignUpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var businessName by remember { mutableStateOf("") }
    var slug by remember { mutableStateOf("") }
    var slugEdited by remember { mutableStateOf(false) }
    var ownerName by remember { mutableStateOf("") }
    var ownerEmail by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }

    // Suggested from the business name until the user takes it over. The server
    // pattern is ^[a-z0-9][a-z0-9-]{1,62}$ — this only proposes something
    // plausible, and the server remains the judge. Deliberately stops updating
    // once edited, so a suggestion cannot overwrite a deliberate choice.
    val effectiveSlug = if (slugEdited) slug else suggestSlug(businessName)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Create a business account", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = businessName,
            onValueChange = { businessName = it },
            label = { Text("Business name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = effectiveSlug,
            onValueChange = {
                slugEdited = true
                slug = it
            },
            label = { Text("Web address") },
            singleLine = true,
            supportingText = {
                Text("Lowercase letters, numbers and hyphens. Your staff use this to sign in.")
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ownerName,
            onValueChange = { ownerName = it },
            label = { Text("Your name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ownerEmail,
            onValueChange = { ownerEmail = it },
            label = { Text("Your email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = ownerPassword,
            onValueChange = { ownerPassword = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("At least 8 characters") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.submit(
                    businessName = businessName,
                    slug = effectiveSlug,
                    ownerName = ownerName,
                    ownerEmail = ownerEmail,
                    ownerPassword = ownerPassword,
                )
            },
            // Presence only. Length, pattern and uniqueness are the server's to
            // judge — duplicating its rules here would mean two places to keep
            // in step, and the client's copy would be the one that went stale.
            enabled = !state.submitting &&
                businessName.isNotBlank() &&
                effectiveSlug.isNotBlank() &&
                ownerName.isNotBlank() &&
                ownerEmail.isNotBlank() &&
                ownerPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text("Create account")
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = error.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            error.detail?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBackToSignIn, enabled = !state.submitting) {
            Text("Already have an account? Sign in")
        }
    }
}

/**
 * A plausible slug for a business name, as a starting point only.
 *
 * Lowercases, replaces anything outside `[a-z0-9]` with a hyphen, collapses
 * runs and trims the ends, then caps at the server's 63 characters. It does not
 * attempt to guarantee validity — the server's pattern is the authority and its
 * 422 is the answer — but it means the common case needs no thought from the
 * person signing up.
 */
internal fun suggestSlug(businessName: String): String =
    businessName
        .lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(63)
        .trimEnd('-')
