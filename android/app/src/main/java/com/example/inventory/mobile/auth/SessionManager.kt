package com.example.inventory.mobile.auth

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.models.LoginRequest
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.SessionExpiryHandler
import com.example.inventory.mobile.net.toApiError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is signed in, and the one place that changes.
 *
 * The authenticator calls [onSessionExpired] from an OkHttp thread when a
 * refresh fails; the UI observes [state] and navigates to login. Keeping that in
 * one object means a failed refresh and an explicit sign-out end up in exactly
 * the same place, rather than the network layer having its own quiet idea of
 * whether the user is logged in.
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokens: TokenStore,
    private val authApi: AuthenticationApi,
) : SessionExpiryHandler {

    sealed interface State {
        data object LoggedOut : State
        data class LoggedIn(val displayName: String, val tenantName: String) : State
    }

    private val _state = MutableStateFlow<State>(
        if (tokens.accessToken() != null) State.LoggedIn("", "") else State.LoggedOut
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * @return null on success, or the error to show.
     *
     * Login failures are deliberately indistinguishable server-side — wrong
     * password and unknown email return the same 401 with the same body — so
     * there is nothing here to tell apart either.
     */
    suspend fun login(email: String, password: String, tenantSlug: String?): ApiError? {
        return try {
            val response = authApi.authLoginPost(
                LoginRequest(
                    email = email.trim(),
                    password = password,
                    tenantSlug = tenantSlug?.trim()?.takeIf { it.isNotBlank() },
                    deviceLabel = "Android",
                )
            )

            if (!response.isSuccessful) {
                return response.toApiError()
            }

            val body = response.body()
            val access = body?.accessToken
            val refresh = body?.refreshToken
            if (access == null || refresh == null) {
                // Both are optional in the contract's AuthTokens schema, so the
                // generated model makes them nullable. A 200 without them is a
                // server bug, but the client still has to say something useful
                // rather than throw a null-pointer at the user.
                return ApiError("Signed in, but the server did not return a session token.")
            }

            tokens.save(access, refresh)
            _state.value = State.LoggedIn(
                displayName = body.user.fullName,
                tenantName = body.user.tenant.name,
            )
            null
        } catch (e: Exception) {
            ApiError.fromNetworkFailure(e)
        }
    }

    /**
     * Called by [TokenAuthenticator] when a refresh fails, and on sign-out.
     *
     * **The state is written first, and the token wipe cannot prevent it.**
     *
     * The order used to be the other way round, which made leaving the session
     * conditional on the wipe succeeding. It does not always: this store is
     * backed by EncryptedSharedPreferences, whose editor throws
     * `SecurityException` when the keystore will not co-operate. One such throw
     * and the state assignment below never ran — the user tapped Sign out, the
     * app stayed on the product list, and clearing app data was the only way
     * back to the login screen.
     *
     * A keystore that will not clear is a bad situation. Refusing to sign the
     * user out of the UI makes it strictly worse, because now they cannot even
     * walk away from the session. Whatever happens to the bytes on disk, the
     * app must agree that the user has left.
     */
    override fun onSessionExpired() {
        _state.value = State.LoggedOut

        try {
            tokens.clear()
        } catch (e: Exception) {
            // Deliberately swallowed. There is no useful recovery and nothing to
            // tell the user: they asked to sign out and, as far as the app is
            // concerned, they have. The tokens that survive are already expired
            // or about to be, and the next request to use one gets a 401 that
            // lands right back here.
            //
            // Not logged, either. This class holds tokens and CLAUDE.md's M3
            // brief is explicit that they never reach a log or a crash report;
            // an exception from a token store is exactly the kind of object
            // that carries one in its message.
        }
    }

    fun signOut() {
        onSessionExpired()
    }
}
