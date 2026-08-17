package com.example.inventory.mobile.auth

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * Adds the bearer token to every request that is not itself an auth call.
 */
class AuthInterceptor(private val tokens: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // /auth/login and /auth/refresh must go out unauthenticated. Attaching a
        // stale access token to a refresh would be harmless today but is exactly
        // the kind of thing that turns into a puzzling 401 later.
        if (request.url.encodedPath.contains("/auth/")) {
            return chain.proceed(request)
        }

        val token = tokens.accessToken() ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}

/**
 * Refreshes the access token when the server answers 401, and retries the
 * original request exactly once.
 *
 * <h2>Single-flight, and why it is not optional</h2>
 *
 * A screen that fires four requests in parallel gets four 401s when the access
 * token expires. Without coordination, each would refresh independently — four
 * refresh calls with the same refresh token.
 *
 * On this backend that is not merely wasteful, it is destructive. Refresh tokens
 * are single-use and rotated: the first call consumes the token and returns a
 * new one, and the second call presenting the *same* token looks exactly like a
 * stolen token being replayed. `RefreshTokenService` treats it as theft and
 * revokes every live token for the user, so the user is silently logged out of
 * every device — caused by their own app opening a screen.
 *
 * So the refresh is serialised on a lock, and a caller that arrives after
 * another thread has already refreshed uses the new token rather than starting
 * its own refresh. That check compares the token that *failed* against what is
 * in the store now: if they differ, somebody else has already done the work.
 *
 * <h2>Retry exactly once</h2>
 *
 * OkHttp calls an [Authenticator] again if the retried request also 401s, which
 * without a guard is an infinite loop against a server that always says no.
 * [responseCount] walks the prior-response chain and gives up at the second
 * attempt.
 *
 * <h2>A failed refresh logs out rather than looping</h2>
 *
 * If the refresh itself fails — expired refresh token, revoked family, backend
 * down — the tokens are cleared and [onSessionExpired] fires so the UI can send
 * the user to the login screen. Returning null tells OkHttp to give up and let
 * the caller see the 401.
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val baseUrl: String,
    private val refreshClient: OkHttpClient,
    private val moshi: Moshi,
    private val onSessionExpired: () -> Unit,
) : Authenticator {

    /**
     * Moshi rather than org.json, for a practical reason: org.json ships as an
     * unimplemented stub in JVM unit tests, so every method throws
     * "not mocked". Using it here would have forced these tests onto an
     * emulator to exercise ordinary refresh logic. Moshi is already a
     * dependency of the generated client, so this adds nothing.
     */
    @JsonClass(generateAdapter = false)
    internal data class RefreshRequest(val refreshToken: String)

    @JsonClass(generateAdapter = false)
    internal data class RefreshResponse(val accessToken: String?, val refreshToken: String?)

    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) {
            // Already retried once with a fresh token and still 401. Refreshing
            // again would loop; the credential is simply not accepted.
            expireSession()
            return null
        }

        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        synchronized(refreshLock) {
            val current = tokens.accessToken()

            // Somebody else refreshed while this thread waited on the lock. Reuse
            // their token instead of burning another refresh — this is the whole
            // point of the lock. Verified by mutation: deleting this block makes
            // `two concurrent 401s cause exactly one refresh` fail with 2, and
            // nothing else in the suite notices.
            if (current != null && current != failedToken) {
                return response.request.retryWith(current)
            }

            val refreshToken = tokens.refreshToken() ?: run {
                expireSession()
                return null
            }

            val newAccess = refresh(refreshToken) ?: run {
                expireSession()
                return null
            }

            return response.request.retryWith(newAccess)
        }
    }

    /**
     * Calls `/auth/refresh` on a client with no authenticator and no auth
     * interceptor.
     *
     * Using the main client here would be a recursion: the refresh call itself
     * could 401 and re-enter this authenticator.
     *
     * Parsed with a local Moshi adapter rather than the generated client's
     * model, deliberately: this runs underneath Retrofit, and calling the
     * generated API from here would make the network layer depend on itself.
     */
    private fun refresh(refreshToken: String): String? {
        val requestAdapter = moshi.adapter(RefreshRequest::class.java)
        val responseAdapter = moshi.adapter(RefreshResponse::class.java)

        val body = requestAdapter.toJson(RefreshRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/auth/refresh")
            .post(body)
            .build()

        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val parsed = responseAdapter.fromJson(response.body?.string().orEmpty())
                val access = parsed?.accessToken?.takeIf { it.isNotBlank() } ?: return null
                val rotated = parsed.refreshToken?.takeIf { it.isNotBlank() } ?: return null

                // Store the rotated refresh token immediately. The old one is
                // already dead server-side; losing the new one would strand the
                // session on the next expiry.
                tokens.save(access, rotated)
                access
            }
        } catch (e: Exception) {
            // Network failure during refresh. Not evidence the session is
            // invalid, but there is nothing to retry with, so the caller sees the
            // original 401.
            null
        }
    }

    private fun expireSession() {
        tokens.clear()
        onSessionExpired()
    }

    private fun Request.retryWith(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
