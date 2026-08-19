package com.example.inventory.mobile.ui

import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.Product
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The two properties of a sale that are easy to get wrong and invisible when
 * they are: the idempotency key must survive a retry, and the business time must
 * carry the device's offset.
 *
 * Driven through a real Retrofit client against MockWebServer so the assertions
 * are on the JSON actually sent. Asserting on the ViewModel's own state would
 * only prove it holds the values it decided to hold; the question is what
 * reaches the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordSaleViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var viewModel: RecordSaleViewModel

    // Every field is now required by the contract, so the fixture has to state
    // them. That is the tightening working: a Product that could be built from
    // three fields was a Product the server was never allowed to send.
    private val product = Product(
        id = UUID.randomUUID(),
        sku = "SKU-1",
        name = "Bread",
        unitOfMeasure = "each",
        costPrice = BigDecimal("7.25"),
        sellingPrice = BigDecimal("12.50"),
        taxRate = BigDecimal("0.15"),
        quantityOnHand = BigDecimal("3"),
        quantityAvailable = BigDecimal("3"),
        stockState = Product.StockState.OK,
        isActive = true,
        updatedAt = OffsetDateTime.parse("2026-08-18T09:00:00+02:00"),
    )

    @Before
    fun setUp() {
        // Unconfined, plus real waiting below. A StandardTestDispatcher with
        // advanceUntilIdle() does NOT work here: the HTTP call runs on OkHttp's
        // own threads, so the test scheduler drains and returns while the request
        // is still in flight, and every assertion reads a state that has not
        // happened yet. That produced three confusing failures before this was
        // spotted.
        Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        server = MockWebServer()
        server.start()

        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()
            .create(SalesApi::class.java)

        viewModel = RecordSaleViewModel(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun bodyOf(request: RecordedRequest): String = request.body.readUtf8()

    /**
     * Waits for the in-flight request to resolve, on the real clock.
     *
     * The ViewModel sets submitting=true synchronously and clears it when the
     * call returns, so that flag is the settle signal. A fixed sleep would be
     * either flaky or slow; this is neither.
     */
    private fun awaitSettled(timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!viewModel.state.value.submitting) {
                // One short pause so the state write that follows the network
                // call is visible before assertions read it.
                Thread.sleep(20)
                if (!viewModel.state.value.submitting) return
            }
            Thread.sleep(10)
        }
        throw AssertionError("request did not settle within ${timeoutMillis}ms")
    }

    /**
     * A complete SaleDetail.
     *
     * The earlier version of this fixture stopped at soldAt, omitting
     * locationId, createdBy and lines. Once the contract marked those required
     * the generated model stopped accepting it — which is the point: the server
     * always sends them, so a fixture without them was testing against a
     * response the API cannot produce, and any bug in reading those fields would
     * have been invisible here.
     */
    private fun saleResponse(number: String) = MockResponse().setResponseCode(201).setBody(
        """{"id":"${UUID.randomUUID()}","saleNumber":"$number","status":"completed",
            "itemCount":1,"subtotalAmount":10.00,"discountAmount":0,"taxAmount":1.50,
            "totalAmount":11.50,"soldAt":"2026-08-18T10:00:00+02:00",
            "locationId":"${UUID.randomUUID()}","createdBy":"${UUID.randomUUID()}",
            "lines":[{"productId":"${product.id}","sku":"SKU-1","name":"Bread",
                      "quantity":1,"unitPrice":10.00,"discountAmount":0,"lineTotal":10.00}]}"""
    )

    // ------------------------------------------------------------------

    @Test
    fun `clientRequestId survives a retry, so a resend cannot record the sale twice`() {
        // First attempt fails at the network — the case the key exists for. The
        // request may well have reached the server and been recorded; the client
        // cannot tell.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.setQuantity("2")
        viewModel.save()
        awaitSettled()

        assertNotNull("the failure must be reported", viewModel.state.value.error)
        assertNotNull(
            "and the key must be KEPT — dropping it is what would double-record",
            viewModel.state.value.pendingRequestId,
        )

        viewModel.retry()
        awaitSettled()

        val first = server.takeRequest()
        val second = server.takeRequest()
        val firstId = Regex(""""clientRequestId":"([^"]+)"""").find(bodyOf(first))?.groupValues?.get(1)
        val secondId = Regex(""""clientRequestId":"([^"]+)"""").find(bodyOf(second))?.groupValues?.get(1)

        assertNotNull("the first request must carry a clientRequestId", firstId)
        assertEquals(
            "THE assertion: the retry must reuse the SAME key. A fresh id would be a " +
                "second sale to the server, taking the stock twice, and nobody would notice " +
                "until someone counted the shelf.",
            firstId,
            secondId,
        )

        assertNull("a recorded sale clears the key", viewModel.state.value.pendingRequestId)
        assertEquals("S-1", viewModel.state.value.recordedSaleNumber)
    }

    @Test
    fun `correcting the quantity after a refusal keeps the same key`() {
        // The path this covers is easy to miss. setQuantity() clears the error,
        // which flips the dialog's button from "Retry" back to "Save" — so a
        // cashier who fixes an oversold quantity re-enters through save(), NOT
        // retry(). If save() minted a fresh key there, the correction would be a
        // second sale to the server.
        //
        // That matters most in the case the client cannot see: if the first
        // attempt actually reached the server and was recorded, reusing the key
        // makes the server answer with the sale it already has. A fresh key
        // would take the stock a second time.
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                .setBody(
                    """{"type":"https://api.example.com/problems/insufficient-stock",
                        "title":"Insufficient stock","status":409,
                        "productId":"${product.id}","requested":5,"available":3}"""
                )
        )
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.setQuantity("5")
        viewModel.save()
        awaitSettled()

        val keyAfterRefusal = viewModel.state.value.pendingRequestId
        assertNotNull("a refused sale keeps its key", keyAfterRefusal)

        // The correction, through the same button the UI actually shows.
        viewModel.setQuantity("3")
        viewModel.save()
        awaitSettled()

        val firstId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)
        val secondId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        assertNotNull(firstId)
        assertEquals(
            "a corrected quantity is the same sale, so it must carry the same key",
            firstId,
            secondId,
        )
        assertEquals("S-1", viewModel.state.value.recordedSaleNumber)
    }

    @Test
    fun `a new sale after a successful one gets a fresh key`() {
        server.enqueue(saleResponse("S-1"))
        server.enqueue(saleResponse("S-2"))

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        val firstId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)
        val secondId = Regex(""""clientRequestId":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        // The other half of idempotency: two genuinely different sales must not
        // share a key, or the second would be swallowed as a replay of the first.
        assertNotEquals("two separate sales are two keys", firstId, secondId)
    }

    @Test
    fun `soldAt is RFC 3339 and carries an explicit UTC offset`() {
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        val soldAt = Regex(""""soldAt":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        assertNotNull("soldAt must be sent, not left for the server to invent", soldAt)
        assertTrue(
            "must be RFC 3339 with an explicit offset (or Z) — a naive local time would " +
                "let the server guess the zone, and it would guess its own: was <$soldAt>",
            soldAt!!.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")),
        )
    }

    @Test
    fun `soldAt is captured at the tap, not at the send`() {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(saleResponse("S-1"))

        viewModel.startSale(product)
        viewModel.save()
        awaitSettled()

        viewModel.retry()
        awaitSettled()

        val first = Regex(""""soldAt":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)
        val second = Regex(""""soldAt":"([^"]+)"""")
            .find(bodyOf(server.takeRequest()))?.groupValues?.get(1)

        // A retry must report when the sale HAPPENED, not when the network came
        // back. The tenant's daily rollup buckets on this value, so a sale tapped
        // at 23:59 and retried at 00:02 belongs to the day it was made.
        assertEquals("the retry must resend the original business time", first, second)
    }

    @Test
    fun `an oversell 409 shows the requested and available numbers`() {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/problem+json")
                // Captured from the running server (OversellBodyProbeTest), with
                // only the productId swapped for this fixture's. The previous
                // version of this body was invented, and an invented oversell
                // body cannot distinguish "parsed the numbers" from "fell back
                // to a message that happens to read sensibly" — it asserts
                // whatever it was told to return. Note `available` is 0 and
                // `requested` is 6: a zero-stock refusal is the DEFAULT case for
                // a new catalogue, and the earlier fixture's non-zero 3 quietly
                // avoided it.
                .setBody(
                    """{"detail":"The requested quantity is not available",
                        "instance":"/api/v1/sales","status":409,
                        "title":"Insufficient stock",
                        "type":"https://api.example.com/problems/insufficient-stock",
                        "productId":"${product.id}","requested":6,"available":0}"""
                )
        )

        viewModel.startSale(product)
        viewModel.setQuantity("6")
        viewModel.save()
        awaitSettled()

        val error = viewModel.state.value.error
        assertNotNull(error)
        assertEquals("Not enough stock to complete this sale.", error!!.message)
        assertTrue(
            "the shortfall must name what was asked for: was <${error.detail}>",
            error.detail!!.contains("6"),
        )
        assertTrue(
            "and what is actually there, including when that is zero: was <${error.detail}>",
            error.detail.contains("0"),
        )
        // The failure the emulator actually showed: the server's own detail
        // echoed back, naming no numbers at all.
        assertTrue(
            "must not fall back to the server's detail: was <${error.message}>",
            !error.message.contains("The requested quantity is not available"),
        )
        assertNotNull(
            "a refused sale keeps its key: the cashier may fix the quantity and retry, and " +
                "that is still the same sale",
            viewModel.state.value.pendingRequestId,
        )
    }
}
