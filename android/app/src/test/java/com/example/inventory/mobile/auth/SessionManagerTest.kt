package com.example.inventory.mobile.auth

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.infrastructure.Serializer
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Signing out, at the layer that decides it.
 *
 * <p>Written because "tapping Sign out does nothing" has two very different
 * causes — the state never changes, or the state changes and the UI does not
 * follow — and they need different fixes. This pins the first half so the
 * question can be answered without an emulator.
 */
class SessionManagerTest {

    /** A TokenStore that records rather than encrypts. */
    private class FakeTokenStore(
        private var access: String? = null,
        private var refresh: String? = null,
        /** Set to make clear() throw, modelling a keystore failure. */
        var failOnClear: Boolean = false,
    ) : TokenStore {
        var cleared = false

        override fun accessToken(): String? = access
        override fun refreshToken(): String? = refresh
        override fun save(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }

        override fun clear() {
            if (failOnClear) {
                throw IllegalStateException("keystore unavailable")
            }
            cleared = true
            access = null
            refresh = null
        }
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): AuthenticationApi = Retrofit.Builder()
        .baseUrl(server.url("/api/v1/"))
        .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
        .build()
        .create(AuthenticationApi::class.java)

    @Test
    fun `signOut clears the tokens and reports LoggedOut`() {
        val tokens = FakeTokenStore(access = "an-access-token", refresh = "a-refresh-token")
        val session = SessionManager(tokens, api())

        // A stored access token means the app starts up signed in.
        assertTrue(session.state.value is SessionManager.State.LoggedIn)

        session.signOut()

        assertEquals(SessionManager.State.LoggedOut, session.state.value)
        assertTrue("the tokens must be cleared, not merely forgotten", tokens.cleared)
        assertNull(tokens.accessToken())
        assertNull(tokens.refreshToken())
    }

    @Test
    fun `signOut reports LoggedOut even when clearing the tokens fails`() {
        // The ordering that matters. If clear() throws and the state is written
        // afterwards, the write never happens: the user taps Sign out, the app
        // stays on the product list, and the only way back to the login screen
        // is to clear app data — which is exactly the symptom reported from the
        // emulator.
        //
        // A keystore that will not clear is a bad situation, but refusing to
        // sign the user out of the UI makes it strictly worse: they cannot even
        // walk away from the session. Getting to LoggedOut must not depend on
        // the token wipe succeeding.
        val tokens = FakeTokenStore(access = "an-access-token", failOnClear = true)
        val session = SessionManager(tokens, api())

        session.signOut()

        assertEquals(
            "a failed token wipe must not strand the user on the signed-in screen",
            SessionManager.State.LoggedOut,
            session.state.value,
        )
    }

    @Test
    fun `a failed refresh lands in the same place as an explicit sign-out`() {
        // TokenAuthenticator calls onSessionExpired from an OkHttp thread. Both
        // routes must end at exactly one state, or the app can be signed out
        // according to the network layer and signed in according to the UI.
        val tokens = FakeTokenStore(access = "an-access-token")
        val session = SessionManager(tokens, api())

        session.onSessionExpired()

        assertEquals(SessionManager.State.LoggedOut, session.state.value)
    }
}
