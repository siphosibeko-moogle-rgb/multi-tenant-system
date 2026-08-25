package com.example.inventory.mobile.auth

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.models.LoginRequest
import com.example.inventory.api.models.TenantRegistrationRequest
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
    private val usersApi: com.example.inventory.api.apis.UsersApi,
) : SessionExpiryHandler {

    sealed interface State {
        data object LoggedOut : State

        /**
         * @param role the `role` claim's value — "owner", "manager", "clerk" or
         *             "viewer" — or null when it is not yet known.
         *
         * **Null is a real state, not a placeholder to code around.** On a cold
         * start the app has a stored token and nothing else: the role lives in
         * the token's claims and in `GET /me`, neither of which has been read
         * yet. [refreshCurrentUser] fills it in.
         *
         * Navigation must therefore treat null as "show the least, not the
         * most" — see `RoleTabs`. Guessing generously for a moment would flash
         * tabs a clerk is not allowed into and then remove them, which looks
         * like the app taking something away.
         */
        data class LoggedIn(
            val displayName: String,
            val tenantName: String,
            val role: String? = null,
        ) : State
    }

    private val _state = MutableStateFlow<State>(
        if (tokens.accessToken() != null) State.LoggedIn("", "") else State.LoggedOut
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Fills in who the user is after a cold start, from `GET /me`.
     *
     * The token survives a restart; the details that came back with it do not,
     * so without this the app knows it is signed in and nothing else — no name,
     * no tenant, and no role to decide navigation by.
     *
     * Deliberately quiet on failure. This is supplementary: the session is
     * already established, every screen fetches its own data and reports its own
     * errors, and dropping the user to the login screen because one identity
     * lookup failed would be a far worse answer than a temporarily sparse menu.
     * A token that is genuinely dead is caught by [TokenAuthenticator] on the
     * next real request, which is where that decision belongs.
     */
    suspend fun refreshCurrentUser() {
        if (_state.value !is State.LoggedIn) {
            return
        }
        try {
            val response = usersApi.meGet()
            val user = response.body()
            if (response.isSuccessful && user != null) {
                _state.value = State.LoggedIn(
                    displayName = user.fullName,
                    tenantName = user.tenant.name,
                    role = user.role.value,
                )
            }
        } catch (e: Exception) {
            // See above.
        }
    }

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
                role = body.user.role.value,
            )
            null
        } catch (e: Exception) {
            ApiError.fromNetworkFailure(e)
        }
    }

    /**
     * Signs up a new business and its first owner, and signs them straight in.
     *
     * @return null on success, or the error to show.
     *
     * **No second login call.** `POST /auth/register-tenant` answers 201 with a
     * full `AuthTokens` — the same body `/auth/login` returns — so the tokens to
     * establish the session are already in hand. Bouncing the owner back to a
     * login form to retype the email and password they just chose would be a
     * round trip the contract does not ask for and the user would rightly find
     * absurd.
     *
     * **No email-verification step.** Checked against the contract rather than
     * assumed: `register-tenant` has exactly three failure responses — 409, 422
     * and 429 — and none of them is a pending-verification state. Inventing one
     * would be inventing a requirement.
     */
    suspend fun register(
        businessName: String,
        slug: String,
        ownerName: String,
        ownerEmail: String,
        ownerPassword: String,
    ): ApiError? {
        return try {
            val response = authApi.authRegisterTenantPost(
                TenantRegistrationRequest(
                    businessName = businessName.trim(),
                    slug = slug.trim().lowercase(),
                    ownerEmail = ownerEmail.trim(),
                    ownerPassword = ownerPassword,
                    ownerName = ownerName.trim(),
                )
            )

            if (!response.isSuccessful) {
                // A 409 here means the slug is taken — the contract records that
                // disclosure as deliberate. The generic 409 wording ("that
                // conflicts with something already saved") is useless to someone
                // filling in a sign-up form, and the server's own detail names
                // the slug, which is a field they did not necessarily type. This
                // is the one place that knows a 409 means "pick another name".
                //
                // A primary-key collision returns the same 409 with the same
                // body by design, so the client cannot tell the two apart — and
                // "try another name" is the right advice for both.
                if (response.code() == 409) {
                    return ApiError(
                        "That business name is taken, try another.",
                        "Business web addresses have to be unique across all businesses here.",
                    )
                }
                return response.toApiError()
            }

            val body = response.body()
            val access = body?.accessToken
            val refresh = body?.refreshToken
            if (access == null || refresh == null) {
                return ApiError("Your business was created, but the server did not return a session token.")
            }

            tokens.save(access, refresh)
            _state.value = State.LoggedIn(
                displayName = body.user.fullName,
                tenantName = body.user.tenant.name,
                role = body.user.role.value,
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
