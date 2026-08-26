package com.example.inventory.mobile.offline

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.AdjustmentRequest
import com.example.inventory.api.models.ProductWriteRequest
import com.example.inventory.api.models.SaleWriteRequest
import com.example.inventory.api.models.SaleWriteRequestLinesInner
import com.example.inventory.api.models.TenantRegistrationRequest
import java.io.File
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The offline loop end to end, against a **real running backend** — real
 * PostgreSQL, real RLS, real idempotency indexes, real ledger triggers.
 *
 * ## Why this exists alongside the MockWebServer tests
 *
 * `OutboxReplayerTest` proves the client sends the right bytes and classifies
 * the answers correctly, against responses this test suite invented. That is
 * exactly the gap CLAUDE.md §16 keeps recording: **a fixture asserts what you
 * tell it to**, and every invented 200 or 409 in that file is a response *this
 * repository believes* the server produces.
 *
 * This test asks the server. It queues a sale the way the app does, replays it
 * through the same [OutboxReplayer] the app uses, and then reads the stock back
 * to see what actually moved. The two questions the milestone asks —
 * "syncs exactly once" and "a queued sale refused for a real reason" — are
 * answered in the numbers rather than in a mock's script.
 *
 * ## Skipped, not failed, when nothing is listening
 *
 * A test that needs a server running on port 8080 cannot be a normal unit test;
 * it would fail on any machine that had not started one, and a suite that is red
 * by default gets ignored. So it checks first and skips.
 *
 * To run it:
 * ```
 * docker compose up -d db
 * cd inventory-backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
 * cd android && ./gradlew :app:testDebugUnitTest --tests '*OutboxLiveBackendTest*'
 * ```
 */
class OutboxLiveBackendTest {

    private companion object {
        const val BASE_URL = "http://localhost:8080/api/v1/"
        const val HEALTH_URL = "http://localhost:8080/api/v1/actuator/health"

        /**
         * ONE tenant for the whole class, not one per test.
         *
         * M1's AuthRateLimiter caps registrations per client address, and it is
         * a real limit rather than a test nuisance: registering four tenants in
         * four seconds is exactly the shape it exists to refuse. The first run
         * of this class hit it and three tests failed with 429 — the rate
         * limiter working, not the outbox failing.
         *
         * Shared state is then the trade, so every test below measures a
         * RELATIVE change and tops the shelf up itself rather than assuming an
         * opening balance somebody else left.
         */
        @Volatile var sharedToken: String? = null

        @Volatile var sharedProductId: UUID? = null
    }

    private lateinit var directory: File
    private lateinit var outbox: Outbox
    private lateinit var replayer: OutboxReplayer
    private lateinit var sales: SalesApi
    private lateinit var inventory: InventoryApi
    private lateinit var catalog: CatalogApi

    private lateinit var productId: UUID

    /** Set once login succeeds; the interceptor reads it on every later call. */
    @Volatile
    private var accessToken: String? = null

    @Before
    fun setUp() {
        assumeTrue("no backend on :8080 — skipping the live loop", backendIsUp())

        directory = Files.createTempDirectory("live-outbox").toFile()
        outbox = Outbox(File(directory, "pending.log"))

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = accessToken?.let {
                    chain.request().newBuilder().header("Authorization", "Bearer $it").build()
                } ?: chain.request()
                chain.proceed(request)
            })
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()

        sales = retrofit.create(SalesApi::class.java)
        inventory = retrofit.create(InventoryApi::class.java)
        catalog = retrofit.create(CatalogApi::class.java)
        replayer = OutboxReplayer(outbox, sales, inventory)

        runBlocking {
            if (sharedToken == null) {
                registerTenantWithAStockedProduct(retrofit)
                sharedToken = accessToken
                sharedProductId = productId
            } else {
                accessToken = sharedToken
                productId = sharedProductId!!
            }
            // Every test tops the shelf up itself. The conflict test
            // deliberately empties it, and JUnit promises no ordering, so an
            // assumed opening balance would make the others pass or fail on
            // which ran first — a flake wearing a real failure's clothes.
            restockTo(BigDecimal("20"))
        }
    }

    /** Brings the shelf to exactly [target] through the real ledger. */
    private suspend fun restockTo(target: BigDecimal) {
        val delta = target.subtract(onHand())
        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            val response = inventory.inventoryAdjustmentsPost(
                AdjustmentRequest(
                    productId = productId,
                    quantityDelta = delta,
                    reason = "test top-up",
                ),
                idempotencyKey = UUID.randomUUID(),
            )
            assertTrue("restock failed: ${response.errorBody()?.string()}", response.isSuccessful)
        }
        assertQuantity("restocked", target.toPlainString(), onHand())
    }

    @After
    fun tearDown() {
        if (this::directory.isInitialized) {
            directory.deleteRecursively()
        }
    }

    private fun backendIsUp(): Boolean = try {
        (URL(HEALTH_URL).openConnection() as HttpURLConnection).run {
            connectTimeout = 1500
            readTimeout = 1500
            requestMethod = "GET"
            responseCode == 200
        }
    } catch (e: Exception) {
        false
    }

    /**
     * A FIXED tenant, registered on the first run and logged into thereafter.
     *
     * A fresh tenant per run was the first design, and it does not survive
     * contact with M1's `AuthRateLimiter`: registrations are capped per HOUR, so
     * the second run of this class within an hour failed with 429 on every test.
     * That is the limiter working correctly, and it made the verification
     * unrepeatable — which is worse than useless, because a 429 in the output
     * reads like a regression in the thing being verified.
     *
     * Registration is hourly; login is per minute and recovers in seconds. So:
     * try to register, and fall back to logging in when the slug is already
     * taken (409) or the hourly allowance is spent (429).
     */
    private suspend fun registerTenantWithAStockedProduct(retrofit: Retrofit) {
        val auth = retrofit.create(AuthenticationApi::class.java)
        val slug = "outbox-live-fixture"
        val email = "owner@outbox-live-fixture.test"
        val password = "SeedPassw0rd!1"

        val registered = auth.authRegisterTenantPost(
            TenantRegistrationRequest(
                businessName = "Outbox Live Fixture",
                slug = slug,
                ownerEmail = email,
                ownerPassword = password,
                ownerName = "Olive Owner",
            )
        )

        accessToken = if (registered.isSuccessful) {
            registered.body()!!.accessToken
        } else {
            assertTrue(
                "registration failed for a reason other than an existing fixture: " +
                    "${registered.code()} ${registered.errorBody()?.string()}",
                registered.code() == 409 || registered.code() == 429,
            )
            val loggedIn = auth.authLoginPost(
                com.example.inventory.api.models.LoginRequest(
                    email = email,
                    password = password,
                    tenantSlug = slug,
                )
            )
            assertTrue("login failed: ${loggedIn.errorBody()?.string()}", loggedIn.isSuccessful)
            loggedIn.body()!!.accessToken
        }

        productId = findOrCreateProduct()
    }

    /** The fixture product: created on the first run, found by SKU afterwards. */
    private suspend fun findOrCreateProduct(): UUID {
        val sku = "LIVE-FIXTURE"

        val existing = catalog.productsGet(limit = 200, q = sku)
        assertTrue("product lookup failed", existing.isSuccessful)
        existing.body()?.items?.firstOrNull { it.sku == sku }?.let { return it.id }

        val created = catalog.productsPost(
            ProductWriteRequest(
                sku = sku,
                name = "Live Loaf",
                costPrice = BigDecimal("4.00"),
                sellingPrice = BigDecimal("10.00"),
                taxRate = BigDecimal("0.10"),
            )
        )
        assertTrue("product create failed: ${created.errorBody()?.string()}", created.isSuccessful)
        return created.body()!!.id
    }

    /** What the server says is on the shelf, right now. */
    private suspend fun onHand(): BigDecimal {
        val response = catalog.productsGet(limit = 200)
        assertTrue("product list failed", response.isSuccessful)
        val product = response.body()!!.items!!.single { it.id == productId }
        return product.quantityOnHand
    }

    /**
     * BigDecimal equality is SCALE-SENSITIVE, and quantities come back as
     * numeric(14,3). `assertEquals(BigDecimal("10"), tenPointZeroZeroZero)`
     * fails, and `stripTrailingZeros()` "fixes" it into `1E+1`, which fails
     * differently — this test hit both in one line. Compare by value.
     */
    private fun assertQuantity(message: String, expected: String, actual: BigDecimal) {
        assertEquals("$message (expected $expected, was ${actual.toPlainString()})",
            0, BigDecimal(expected).compareTo(actual))
    }

    private fun queueSale(quantity: String, key: UUID = UUID.randomUUID()): UUID {
        outbox.enqueue(
            OutboxEntry(
                clientRequestId = key,
                operation = OutboxOperation.SALE,
                productId = productId,
                quantity = BigDecimal(quantity),
                capturedAt = OffsetDateTime.now(),
            )
        )
        return key
    }

    // ------------------------------------------------------------------

    /**
     * The milestone's first criterion: a sale captured offline syncs on
     * reconnect and is recorded **exactly once**.
     */
    @Test
    fun `a queued sale syncs on reconnect and moves stock exactly once`() = runBlocking {
        val before = onHand()
        queueSale("3")

        val report = replayer.replayAll()

        assertEquals("the sale should have been recorded", 1, report.recorded.size)
        assertEquals(0, report.rejected.size)
        assertEquals("the queue should be empty", 0, outbox.size())

        assertQuantity("stock must reflect the sale once",
            before.subtract(BigDecimal("3")).toPlainString(), onHand())
    }

    /**
     * The case the idempotency key exists for: the request reached the server
     * and the response was lost, so the client replays a sale that is already
     * recorded.
     */
    @Test
    fun `replaying a sale the server already recorded does not take the stock twice`() =
        runBlocking {
            val before = onHand()
            val key = UUID.randomUUID()

            // First delivery — this is the attempt whose response we pretend was
            // lost on the way back to the phone.
            queueSale("2", key)
            assertEquals(1, replayer.replayAll().recorded.size)
            val afterFirst = onHand()
            assertQuantity("the first delivery moves the stock",
                before.subtract(BigDecimal("2")).toPlainString(), afterFirst)

            // The phone never saw the answer, so the entry is still queued and is
            // sent again with the SAME key.
            queueSale("2", key)
            val report = replayer.replayAll()

            assertEquals(
                "the server must recognise the replay rather than record a second sale",
                1, report.alreadyRecorded.size,
            )
            assertEquals(0, report.recorded.size)

            // THE assertion. Two deliveries, one sale, stock moved once.
            assertQuantity("stock must not move twice", afterFirst.toPlainString(), onHand())
        }

    /**
     * The milestone's second criterion, spelled out: queue a sale offline, have
     * the same product oversold through a different channel while offline, then
     * sync. The queued sale must be **refused**, not silently succeed and not
     * corrupt the balance.
     */
    @Test
    fun `a queued sale is refused when the stock went while the device was offline`() =
        runBlocking {
            val before = onHand()

            // The cashier sells 8 with no signal. It goes to the outbox.
            queueSale("8")

            // Meanwhile, through another till, the shelf is emptied.
            val elsewhere = sales.salesPost(
                SaleWriteRequest(
                    lines = listOf(
                        SaleWriteRequestLinesInner(
                            productId = productId,
                            quantity = before,
                        )
                    ),
                    clientRequestId = UUID.randomUUID(),
                )
            )
            assertTrue("the other till's sale should succeed", elsewhere.isSuccessful)
            assertQuantity("the other till emptied the shelf", "0", onHand())

            // The phone reconnects.
            val report = replayer.replayAll()

            assertEquals(
                "the queued sale must be REFUSED, not quietly recorded", 1,
                report.rejected.size,
            )
            assertEquals(0, report.recorded.size)
            assertEquals(0, report.alreadyRecorded.size)
            assertTrue("the cashier must be told", report.needsAttention)

            val rejection = report.rejected.single()
            assertNotNull("the refusal must carry the numbers", rejection.detail)
            assertTrue(
                "detail should name what was asked for: ${rejection.detail}",
                rejection.detail!!.contains("8"),
            )

            // And the balance is untouched by the refusal — no partial write,
            // no negative stock. The ledger trigger refused it, as T12 says it
            // must, and the client did not retry around the refusal.
            assertQuantity("a refusal must leave the balance untouched", "0", onHand())

            // Removed from the queue: it can never succeed, so retrying forever
            // would only hide the problem from the person who took the money.
            assertEquals(0, outbox.size())
        }

    /**
     * The same guarantee for the other operation the outbox carries. An
     * adjustment had NO idempotency at all before M8, so a replayed one posted
     * a second movement into an append-only ledger.
     */
    @Test
    fun `a replayed adjustment does not move stock twice`() = runBlocking {
        val before = onHand()
        val key = UUID.randomUUID()

        repeat(2) {
            outbox.enqueue(
                OutboxEntry(
                    clientRequestId = key,
                    operation = OutboxOperation.ADJUSTMENT,
                    productId = productId,
                    quantity = BigDecimal("-2"),
                    capturedAt = OffsetDateTime.now(),
                    reason = "Damaged in transit",
                )
            )
            replayer.replayAll()
        }

        assertQuantity("two deliveries of one adjustment must remove two units, not four",
            before.subtract(BigDecimal("2")).toPlainString(), onHand())
    }
}
