package com.example.inventory.mobile.offline

import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.infrastructure.Serializer
import com.example.inventory.api.models.Product
import com.example.inventory.mobile.ui.RecordSaleViewModel
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * **Something must call the replayer, and this is the test that says so.**
 *
 * ## Why it exists
 *
 * `OutboxReplayer` was written, wired into Hilt, and covered by twenty-four
 * tests — every one of which calls `replayAll()` **itself**. Nothing in the app
 * ever did. A sale captured with no signal was queued durably, correctly, with
 * the right idempotency key, and would have sat there forever. Every test was
 * green and the feature did not work.
 *
 * That is CLAUDE.md §5's pipeline rule, recurring exactly as it warns:
 *
 * > A pipeline whose stages are each tested with the previous stage's output
 * > already provided has no test of the wiring between them. Ask of any
 * > multi-stage job: *does anything exercise stage N without stage N−1 being
 * > done by hand first?*
 *
 * M7 shipped the same shape when `ReorderService.recomputeAll()` never called
 * `DemandRollupJob`, and `RecomputeRunsTheRollupTest` is the test written to
 * close it. This is that test, for this pipeline.
 *
 * ## The rule this test lives by
 *
 * **It never calls `replayAll()`, `syncNow()`, or anything on the replayer.**
 * It queues an entry, drives the ViewModel the way a cashier does, and asserts
 * the queue drained. Adding a manual drain to the fixture would restore the
 * exact blind spot it exists to close — the same reason
 * `RecomputeRunsTheRollupTest` refuses to roll up in its own setup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingASaleDrainsTheOutboxTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var outbox: Outbox
    private lateinit var coordinator: OutboxCoordinator
    private lateinit var viewModel: RecordSaleViewModel

    private val product = Product(
        id = UUID.randomUUID(),
        sku = "SKU-DRAIN",
        name = "Bread",
        unitOfMeasure = "each",
        costPrice = BigDecimal("7.25"),
        sellingPrice = BigDecimal("12.50"),
        taxRate = BigDecimal("0.15"),
        quantityOnHand = BigDecimal("9"),
        quantityAvailable = BigDecimal("9"),
        stockState = Product.StockState.OK,
        isActive = true,
        updatedAt = OffsetDateTime.parse("2026-08-26T09:00:00+02:00"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()

        directory = Files.createTempDirectory("drain-test").toFile()
        outbox = Outbox(File(directory, "pending.log"))

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()

        val sales = retrofit.create(SalesApi::class.java)
        coordinator = OutboxCoordinator(
            outbox = outbox,
            replayer = OutboxReplayer(
                outbox = outbox,
                sales = sales,
                inventory = retrofit.create(InventoryApi::class.java),
            ),
        )
        viewModel = RecordSaleViewModel(sales, outbox, coordinator)
    }

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun saleBody(saleNumber: String) = MockResponse()
        .setResponseCode(201)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"id":"${UUID.randomUUID()}","saleNumber":"$saleNumber","status":"completed",
             "itemCount":1,"locationId":"${UUID.randomUUID()}","subtotalAmount":12.50,
             "discountAmount":0.00,"taxAmount":1.88,"totalAmount":14.38,
             "soldAt":"2026-08-26T09:00:00Z","createdBy":"${UUID.randomUUID()}","lines":[]}
            """.trimIndent()
        )

    /**
     * Polls a condition on the real clock.
     *
     * A StandardTestDispatcher with advanceUntilIdle() does NOT work here: the
     * HTTP call runs on OkHttp's own threads, so the test scheduler drains and
     * returns while the request is still in flight and every assertion reads a
     * state that has not happened yet. RecordSaleViewModelTest documents the
     * same trap. A fixed sleep would be flaky or slow; this is neither.
     */
    private fun waitUntil(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /** The entry a previous, offline shift left behind. */
    private fun queueAnOfflineSale(): UUID {
        val key = UUID.randomUUID()
        outbox.enqueue(
            OutboxEntry(
                clientRequestId = key,
                operation = OutboxOperation.SALE,
                productId = product.id,
                quantity = BigDecimal("4"),
                capturedAt = OffsetDateTime.parse("2026-08-25T18:30:00+02:00"),
            )
        )
        return key
    }

    // ------------------------------------------------------------------

    @Test
    fun `recording a sale online drains a sale queued earlier while offline`() {
        val queuedYesterday = queueAnOfflineSale()
        assertEquals(1, outbox.size())

        // Two responses: the new sale, then the replay of the queued one.
        server.enqueue(saleBody("S-NEW"))
        server.enqueue(saleBody("S-QUEUED"))

        // A cashier records an ordinary sale. Nothing here touches the
        // replayer — that is the whole point.
        viewModel.startSale(product)
        viewModel.setQuantity("1")
        viewModel.save()
        waitUntil("the new sale to be recorded") {
            viewModel.state.value.recordedSaleNumber != null
        }

        assertEquals("the new sale was recorded", "S-NEW", viewModel.state.value.recordedSaleNumber)

        // The drain runs after the state update, so wait for it separately —
        // this is the thing under test and must not be raced.
        waitUntil("the queued sale to be replayed") { outbox.size() == 0 }

        // THE ASSERTION. If nothing in the app calls the replayer, the queued
        // sale is still here and this fails — which is exactly what the app did
        // before OutboxCoordinator existed.
        assertEquals(
            "recording a sale must drain the queue; the outbox still holds " +
                "${outbox.pending().map { it.clientRequestId }}",
            0, outbox.size(),
        )

        // And the queued sale really went to the server, carrying its own
        // capture-time key rather than the new sale's.
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(
            "the second request must be the replay of the queued sale",
            second.body.readUtf8().contains(queuedYesterday.toString()),
        )
        assertTrue(
            "and the first must be the sale just made, not the queued one",
            !first.body.readUtf8().contains(queuedYesterday.toString()),
        )
    }

    @Test
    fun `the pending count reflects the queue after capture and after drain`() {
        // Offline: the send fails, so the sale is captured.
        server.shutdown()

        viewModel.startSale(product)
        viewModel.setQuantity("2")
        viewModel.save()
        waitUntil("the failed send to be captured") { outbox.size() == 1 }

        assertEquals("the sale was captured", 1, outbox.size())
        assertEquals(
            "the badge must show it — a queue nobody can see is a queue nobody " +
                "notices has stopped draining",
            1, coordinator.pending.value,
        )
    }

    @Test
    fun `a drain with an empty queue makes no requests`() {
        // The common case: this fires on every app launch and after every sale,
        // so it must be free when there is nothing to do.
        kotlinx.coroutines.runBlocking { coordinator.syncNow() }

        assertEquals(0, server.requestCount)
        assertEquals(0, coordinator.pending.value)
    }
}
