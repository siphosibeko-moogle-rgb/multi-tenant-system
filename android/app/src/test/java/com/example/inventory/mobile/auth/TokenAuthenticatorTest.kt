package com.example.inventory.mobile.auth

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The authenticator against a real server.
 *
 * MockWebServer rather than a mocked OkHttp: the behaviour under test is how
 * OkHttp drives an [okhttp3.Authenticator] — when it calls it, what
 * `priorResponse` looks like on a retry, whether the retried request carries the
 * new header. Mocking that would be testing a model of OkHttp rather than OkHttp.
 */
class TokenAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokens: TokenStore
    private var sessionExpiredCount = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokens = InMemoryTokenStore(access = "stale-access", refresh = "refresh-1")
        sessionExpiredCount = 0
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): OkHttpClient {
        val bare = OkHttpClient.Builder().build()
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokens))
            .authenticator(
                TokenAuthenticator(
                    tokens = tokens,
                    baseUrl = server.url("/").toString(),
                    refreshClient = bare,
                    moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
                    onSessionExpired = { sessionExpiredCount++ },
                )
            )
            .build()
    }

    private fun get(path: String) =
        client().newCall(Request.Builder().url(server.url(path)).build()).execute()

    // ------------------------------------------------------------------

    @Test
    fun `401 triggers a refresh and the retry succeeds`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/refresh" -> MockResponse().setResponseCode(200).setBody(
                    """{"accessToken":"fresh-access","refreshToken":"refresh-2"}"""
                )
                request.getHeader("Authorization") == "Bearer fresh-access" ->
                    MockResponse().setResponseCode(200).setBody("""{"items":[]}""")
                else -> MockResponse().setResponseCode(401)
            }
        }

        val response = get("/products")

        assertEquals(200, response.code)
        assertEquals("the retry must carry the new token", "fresh-access", tokens.accessToken())
        assertEquals(
            "the rotated refresh token must be stored, or the next expiry strands the session",
            "refresh-2",
            tokens.refreshToken(),
        )
        assertEquals("a successful refresh is not a logout", 0, sessionExpiredCount)
    }

    @Test
    fun `a failed refresh logs the user out instead of looping`() {
        val refreshAttempts = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/refresh" -> {
                    refreshAttempts.incrementAndGet()
                    // The family was revoked, or the token expired.
                    MockResponse().setResponseCode(401)
                        .setBody("""{"title":"Authentication failed","status":401}""")
                }
                else -> MockResponse().setResponseCode(401)
            }
        }

        val response = get("/products")

        assertEquals("the caller still sees the 401", 401, response.code)
        assertEquals("refresh is attempted once, not repeatedly", 1, refreshAttempts.get())
        assertEquals("the session ends exactly once", 1, sessionExpiredCount)
        assertNull("tokens must be wiped, not left to fail again", tokens.accessToken())
        assertNull(tokens.refreshToken())
    }

    @Test
    fun `two concurrent 401s cause exactly one refresh`() {
        // The case that matters most. Without single-flighting, both requests
        // refresh with the same refresh token; the backend rotates on first use
        // and treats the second as a replayed — i.e. stolen — token, revoking
        // every live token for the user. The app would log itself out.
        val refreshCount = AtomicInteger()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    MockResponse().setResponseCode(200).setBody(
                        """{"accessToken":"fresh-access","refreshToken":"refresh-2"}"""
                    )
                }
                request.getHeader("Authorization") == "Bearer fresh-access" ->
                    MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                else -> {
                    // Both original requests 401 together, so both enter the
                    // authenticator at the same time.
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(401)
                }
            }
        }

        val shared = client()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Int> {
                shared.newCall(Request.Builder().url(server.url("/products")).build())
                    .execute().use { it.code }
            }
            val b = pool.submit<Int> {
                shared.newCall(Request.Builder().url(server.url("/inventory")).build())
                    .execute().use { it.code }
            }

            assertTrue("both requests should reach the server", started.await(10, TimeUnit.SECONDS))
            release.countDown()

            assertEquals(200, a.get(20, TimeUnit.SECONDS))
            assertEquals(200, b.get(20, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            "EXACTLY one refresh. Two would present the same single-use refresh token twice, " +
                "which this backend treats as a stolen token and answers by revoking the " +
                "user's whole token family.",
            1,
            refreshCount.get(),
        )
        assertEquals(0, sessionExpiredCount)
    }

    @Test
    fun `a retry that still fails gives up rather than looping forever`() {
        val refreshCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/auth/refresh" -> {
                    refreshCount.incrementAndGet()
                    MockResponse().setResponseCode(200).setBody(
                        """{"accessToken":"fresh-${refreshCount.get()}","refreshToken":"r"}"""
                    )
                }
                // Refuses every token, however fresh — a revoked user, say.
                else -> MockResponse().setResponseCode(401)
            }
        }

        val response = get("/products")

        assertEquals(401, response.code)
        assertTrue(
            "at most one refresh; the guard is the prior-response chain, not a timer",
            refreshCount.get() <= 1,
        )
        assertEquals("and the session is ended", 1, sessionExpiredCount)
    }

    @Test
    fun `auth endpoints do not get an Authorization header`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("{}")
        }

        client().newCall(
            Request.Builder().url(server.url("/auth/login")).build()
        ).execute().close()

        val recorded = server.takeRequest()
        assertNull(
            "login must go out unauthenticated — a stale token on an auth call is a " +
                "confusing 401 waiting to happen",
            recorded.getHeader("Authorization"),
        )
    }

    @Test
    fun `a request with no stored token is sent unauthenticated rather than crashing`() {
        tokens.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
        }

        val response = get("/products")

        assertEquals(200, response.code)
        assertFalse(
            "no Authorization header should be invented from a null token",
            server.takeRequest().headers.names().contains("Authorization"),
        )
    }
}
