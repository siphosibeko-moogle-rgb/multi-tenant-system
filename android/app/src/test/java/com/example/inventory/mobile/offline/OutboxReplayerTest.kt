package com.example.inventory.mobile.offline

import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Replaying the queue: what reaches the server, and what happens to the entry.
 *
 * Driven through a real Retrofit client against MockWebServer, so the
 * assertions are on the bytes actually sent. Asserting on the replayer's own
 * bookkeeping would only prove it believes what it decided to believe; the
 * questions here are whether the idempotency key goes on the wire and whether
 * a refused sale survives as something the cashier can be told about.
 *
 * CLAUDE.md §16, three times over: a fixture asserts what you tell it to. The
 * response bodies below are shaped like the ones the backend's own HTTP tests
 * produce — including the 409's `available`, which is `0.000` because that is
 * what `numeric(14,3)` renders and what no hand-written fixture would contain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxReplayerTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var outbox: Outbox
    private lateinit var replayer: OutboxReplayer

    private val productId: UUID = UUID.randomUUID()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        directory = Files.createTempDirectory("replayer-test").toFile()
        outbox = Outbox(File(directory, "pending.log"))

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()

        replayer = OutboxReplayer(
            outbox = outbox,
            sales = retrofit.create(SalesApi::class.java),
            inventory = retrofit.create(InventoryApi::class.java),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun queueSale(key: UUID = UUID.randomUUID(), quantity: String = "2"): UUID {
        outbox.enqueue(
            OutboxEntry(
                clientRequestId = key,
                operation = OutboxOperation.SALE,
                productId = productId,
                quantity = BigDecimal(quantity),
                capturedAt = OffsetDateTime.parse("2026-08-26T23:59:00+02:00"),
            )
        )
        return key
    }

    private fun queueAdjustment(key: UUID = UUID.randomUUID(), delta: String = "-3"): UUID {
        outbox.enqueue(
            OutboxEntry(
                clientRequestId = key,
                operation = OutboxOperation.ADJUSTMENT,
                productId = productId,
                quantity = BigDecimal(delta),
                capturedAt = OffsetDateTime.parse("2026-08-26T23:59:00+02:00"),
                reason = "Damaged in transit",
            )
        )
        return key
    }

    /**
     * Every field `SaleDetail` marks required, because the generated model is
     * non-null on all of them and a response missing one fails to deserialize —
     * which surfaces here as a *deferral*, indistinguishable from a network
     * failure.
     *
     * This fixture originally omitted `itemCount`, and four tests failed as
     * though the replayer had defered every sale. CLAUDE.md §16's rule, for the
     * fourth recorded time: a fixture asserts what you tell it to, and one
     * shaped like a response the server cannot produce tests nothing.
     */
    private fun saleBody(saleNumber: String = "S-0001") = """
        {"id":"${UUID.randomUUID()}","saleNumber":"$saleNumber","status":"completed",
         "itemCount":1,"locationId":"${UUID.randomUUID()}","subtotalAmount":25.00,
         "discountAmount":0.00,"taxAmount":2.50,"totalAmount":27.50,
         "soldAt":"2026-08-26T21:59:00Z","createdBy":"${UUID.randomUUID()}","lines":[]}
    """.trimIndent()

    private fun movementBody() = """
        {"id":42,"productId":"$productId","productName":"Bread",
         "locationId":"${UUID.randomUUID()}","movementType":"adjustment",
         "quantityDelta":-3,"balanceAfter":7,"occurredAt":"2026-08-26T21:59:00Z"}
    """.trimIndent()

    // ------------------------------------------------------------------

    @Test
    fun `a queued sale replays with the capture-time key and leaves the queue`() = runTest {
        val key = queueSale()
        server.enqueue(MockResponse().setResponseCode(201).setBody(saleBody()))

        val report = replayer.replayAll()

        val request = server.takeRequest()
        val body = request.body.readUtf8()

        // THE assertion of the whole feature: the key that reaches the server is
        // the one minted when the cashier tapped save, not one made up at send
        // time. A fresh key here records the sale twice and takes the stock
        // twice, and nobody notices until someone counts the shelf.
        assertTrue("clientRequestId must be on the wire: $body", body.contains(key.toString()))

        // And the business time, not the reconnect time — sent with the device's
        // offset intact, which is what lets the server place it in the right
        // business day. Replaying with "now" would move a sale made at 23:59
        // into the next day's rollup.
        assertTrue(
            "soldAt must be the capture time with its offset: $body",
            body.contains("2026-08-26T23:59:00+02:00"),
        )

        assertEquals(1, report.recorded.size)
        assertEquals(0, outbox.size())
    }

    @Test
    fun `a 200 means an earlier attempt already landed, and is not an error`() = runTest {
        queueSale()
        server.enqueue(MockResponse().setResponseCode(200).setBody(saleBody()))

        val report = replayer.replayAll()

        // The idempotency key doing its job: a previous attempt reached the
        // server and the response was lost on the way back. Treating this as a
        // failure would leave the entry queued forever, re-sending a sale that
        // is already recorded.
        assertEquals(1, report.alreadyRecorded.size)
        assertEquals(0, report.rejected.size)
        assertEquals(1, report.syncedCount)
        assertEquals(0, outbox.size())
    }

    /**
     * The case the milestone names explicitly: queue a sale offline, have the
     * product oversold through another till in the meantime, then sync.
     */
    @Test
    fun `a sale refused for insufficient stock is surfaced, not retried and not dropped`() =
        runTest {
            val key = queueSale(quantity = "5")
            server.enqueue(
                MockResponse().setResponseCode(409)
                    .setHeader("Content-Type", "application/problem+json")
                    .setBody(
                        """
                        {"type":"https://api.example.com/problems/insufficient-stock",
                         "title":"Insufficient stock","status":409,
                         "detail":"The requested quantity is not available",
                         "productId":"$productId","requested":5.000,"available":0.000}
                        """.trimIndent()
                    )
            )

            val report = replayer.replayAll()

            // Refused, and reported as such.
            assertEquals(1, report.rejected.size)
            assertTrue("the report must demand attention", report.needsAttention)

            val rejection = report.rejected.single()
            assertEquals(key, rejection.entry.clientRequestId)

            // The numbers are carried through, because "not enough stock" without
            // them is an error and with them it is an instruction.
            assertNotNull("the shortfall must reach the user", rejection.detail)
            assertTrue(
                "detail should name the numbers: ${rejection.detail}",
                rejection.detail!!.contains("5") && rejection.detail.contains("0"),
            )

            // NOT retried: it left the queue, so the next reconnect does not
            // re-send a sale the server will refuse identically forever.
            assertEquals(0, outbox.size())

            // NOT silently dropped either: it is in the report. A sale that will
            // never be recorded is money the shop took with no record of it, and
            // the person who took it has to find out.
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `a deleted product is a rejection too, not an endless retry`() = runTest {
        queueSale()
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(
                    """
                    {"type":"https://api.example.com/problems/not-found",
                     "title":"Not found","status":404,"detail":"No product with that id"}
                    """.trimIndent()
                )
        )

        val report = replayer.replayAll()

        // The product was deleted while the device was offline. No amount of
        // waiting brings it back, so this is permanent for this payload.
        assertEquals(1, report.rejected.size)
        assertEquals(0, outbox.size())
    }

    @Test
    fun `no network defers the entry and keeps it queued`() = runTest {
        val key = queueSale()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val report = replayer.replayAll()

        assertEquals(1, report.deferred.size)
        assertEquals(0, report.rejected.size)

        // Still there, with the SAME key — which is what makes the next attempt
        // safe even if this one actually reached the server.
        val stuck = outbox.pending().single()
        assertEquals(key, stuck.clientRequestId)
        assertEquals(1, stuck.attempts)
        assertNotNull(stuck.lastError)
    }

    @Test
    fun `a 500 defers rather than discarding a real sale`() = runTest {
        queueSale()
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        val report = replayer.replayAll()

        // The server's problem, not the request's. Rejecting here would throw
        // away a perfectly valid sale because a backend was restarting.
        assertEquals(1, report.deferred.size)
        assertEquals(1, outbox.size())
    }

    @Test
    fun `a 401 defers, so a sale survives an expired session`() = runTest {
        queueSale()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val report = replayer.replayAll()

        // By the time a 401 reaches here TokenAuthenticator has already tried a
        // refresh, so the session is genuinely gone. The sale is still real and
        // must outlive a re-login — rejecting it would destroy the record of
        // money taken because somebody's password changed.
        assertEquals(1, report.deferred.size)
        assertEquals(1, outbox.size())
        assertTrue(outbox.pending().single().lastError!!.contains("Sign in"))
    }

    @Test
    fun `a queued adjustment sends its key as the Idempotency-Key header`() = runTest {
        val key = queueAdjustment()
        server.enqueue(MockResponse().setResponseCode(201).setBody(movementBody()))

        val report = replayer.replayAll()

        val request = server.takeRequest()

        // The generated signature defaults idempotencyKey to null. Taking that
        // default would send no key, and every replay would post a SECOND
        // movement into an append-only ledger — repairable only by a
        // compensating row somebody has to notice is needed.
        assertEquals(key.toString(), request.getHeader("Idempotency-Key"))
        assertEquals("/api/v1/inventory/adjustments", request.path)

        // The signed delta and the reason survive the round trip.
        val body = request.body.readUtf8()
        assertTrue("delta must keep its sign: $body", body.contains("-3"))
        assertTrue("reason must be sent: $body", body.contains("Damaged in transit"))

        assertEquals(1, report.recorded.size)
        assertEquals(0, outbox.size())
    }

    @Test
    fun `the queue drains in order`() = runTest {
        val first = queueSale(quantity = "1")
        val second = queueSale(quantity = "2")
        server.enqueue(MockResponse().setResponseCode(201).setBody(saleBody("S-0001")))
        server.enqueue(MockResponse().setResponseCode(201).setBody(saleBody("S-0002")))

        val report = replayer.replayAll()

        assertEquals(2, report.recorded.size)
        assertEquals(
            listOf(first, second),
            report.recorded.map { it.entry.clientRequestId },
        )
        assertEquals(0, outbox.size())
    }

    @Test
    fun `a deferral stops the drain, leaving later entries untouched`() = runTest {
        queueSale(quantity = "1")
        val later = queueSale(quantity = "2")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val report = replayer.replayAll()

        // If the link is down the second entry fails identically. Hammering the
        // whole queue against a dead network burns battery and inflates every
        // entry's attempt count for no information.
        assertEquals(1, report.deferred.size)
        assertEquals(1, server.requestCount)

        // Both still queued, and the untried one is untouched — its attempt
        // count must not record a failure it never had.
        assertEquals(2, outbox.size())
        assertEquals(0, outbox.pending().last { it.clientRequestId == later }.attempts)
    }

    @Test
    fun `a rejection does not stop the drain`() = runTest {
        queueSale(quantity = "5")
        queueSale(quantity = "1")
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(
                    """
                    {"type":"https://api.example.com/problems/insufficient-stock",
                     "title":"Insufficient stock","status":409,
                     "detail":"The requested quantity is not available",
                     "productId":"$productId","requested":5.000,"available":0.000}
                    """.trimIndent()
                )
        )
        server.enqueue(MockResponse().setResponseCode(201).setBody(saleBody("S-0002")))

        val report = replayer.replayAll()

        // A rejection is specific to its entry and says nothing about the next
        // one. One oversold product must not strand every other sale in the
        // queue behind it.
        assertEquals(1, report.rejected.size)
        assertEquals(1, report.recorded.size)
        assertEquals(0, outbox.size())
    }

    @Test
    fun `replaying an empty queue makes no requests at all`() = runTest {
        val report = replayer.replayAll()

        assertEquals(0, server.requestCount)
        assertEquals(0, report.syncedCount)
        assertTrue(!report.needsAttention)
    }
}
