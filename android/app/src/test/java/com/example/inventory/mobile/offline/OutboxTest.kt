package com.example.inventory.mobile.offline

import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The queue itself: does a captured sale survive, in order, with its key intact?
 *
 * Every assertion here is about durability, because that is the only thing this
 * class exists to provide. A queue that loses an entry loses money and says
 * nothing — the failure mode has no error, no crash and no log line, exactly
 * like the tenant-isolation failures the backend guards on row counts.
 */
class OutboxTest {

    private lateinit var directory: File
    private lateinit var outbox: Outbox

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("outbox-test").toFile()
        outbox = Outbox(File(directory, "pending.log"))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun entry(
        key: UUID = UUID.randomUUID(),
        operation: OutboxOperation = OutboxOperation.SALE,
        quantity: String = "2",
        reason: String? = null,
    ) = OutboxEntry(
        clientRequestId = key,
        operation = operation,
        productId = UUID.randomUUID(),
        quantity = BigDecimal(quantity),
        capturedAt = OffsetDateTime.parse("2026-08-26T23:59:00+02:00"),
        reason = reason,
    )

    @Test
    fun `an empty outbox reads as empty rather than throwing`() {
        // The first launch on a fresh install. A queue that threw here would
        // take the whole sell screen down before anyone had sold anything.
        assertEquals(0, outbox.size())
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun `a queued entry survives a new Outbox over the same file`() {
        val key = UUID.randomUUID()
        outbox.enqueue(entry(key = key, quantity = "3"))

        // A NEW instance over the same file — the closest a JVM test gets to the
        // process being killed and the app relaunched, which is the case the
        // whole class exists for.
        val reopened = Outbox(File(directory, "pending.log"))
        val restored = reopened.pending().single()

        assertEquals(key, restored.clientRequestId)
        assertEquals(0, BigDecimal("3").compareTo(restored.quantity))
        assertEquals(
            OffsetDateTime.parse("2026-08-26T23:59:00+02:00"),
            restored.capturedAt,
        )
    }

    @Test
    fun `capturedAt keeps its offset, so a sale does not change business day`() {
        outbox.enqueue(entry())

        // 23:59+02:00 is 21:59Z. If the offset were dropped on the way through
        // the file, a sale made just before midnight would be replayed as a
        // different instant and the tenant's daily rollup would bucket it into
        // the wrong day. Nothing would report that.
        val restored = Outbox(File(directory, "pending.log")).pending().single()
        assertEquals(
            OffsetDateTime.parse("2026-08-26T23:59:00+02:00").toInstant(),
            restored.capturedAt.toInstant(),
        )
        assertEquals("+02:00", restored.capturedAt.offset.id)
    }

    @Test
    fun `order is preserved`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val third = UUID.randomUUID()
        outbox.enqueue(entry(key = first))
        outbox.enqueue(entry(key = second))
        outbox.enqueue(entry(key = third))

        // Two adjustments to one product must replay in the order the person
        // made them, and a sale queued before a stock-in should be attempted
        // first so its refusal is honest about the shelf at that moment.
        assertEquals(
            listOf(first, second, third),
            outbox.pending().map { it.clientRequestId },
        )
    }

    @Test
    fun `enqueuing the same key twice keeps one entry`() {
        val key = UUID.randomUUID()
        outbox.enqueue(entry(key = key))
        outbox.enqueue(entry(key = key))

        // The key identifies the operation. Two rows carrying it are one
        // operation the server would dedupe anyway, and a queue that can hold
        // duplicates makes every count shown to the user wrong.
        assertEquals(1, outbox.size())
    }

    @Test
    fun `remove takes out exactly one entry`() {
        val keep = UUID.randomUUID()
        val drop = UUID.randomUUID()
        outbox.enqueue(entry(key = keep))
        outbox.enqueue(entry(key = drop))

        outbox.remove(drop)

        assertEquals(listOf(keep), outbox.pending().map { it.clientRequestId })
    }

    @Test
    fun `recordFailure counts attempts without discarding the entry`() {
        val key = UUID.randomUUID()
        outbox.enqueue(entry(key = key))

        outbox.recordFailure(key, "Can't reach the server.")
        outbox.recordFailure(key, "Can't reach the server.")

        val stuck = outbox.pending().single()
        assertEquals(2, stuck.attempts)
        assertEquals("Can't reach the server.", stuck.lastError)

        // The entry is STILL THERE. Attempts are counted for display and
        // diagnosis, never as a give-up counter: discarding a real sale after N
        // tries loses money silently, which is worse than a queue that will not
        // drain — a queue that will not drain is at least visible.
        assertEquals(1, outbox.size())
    }

    @Test
    fun `free text in a reason survives, separators and newlines included`() {
        val awkward = "Damaged\nin transit  and then some \\ backslash"
        outbox.enqueue(entry(operation = OutboxOperation.ADJUSTMENT, reason = awkward))

        // The reason is the only free text a person types into this record. If
        // escaping were wrong the line would split into the wrong number of
        // fields and the entry would be silently skipped as corrupt — losing the
        // adjustment rather than mangling it, which is harder to notice.
        assertEquals(awkward, outbox.pending().single().reason)
    }

    @Test
    fun `a null reason stays null rather than becoming an empty string`() {
        outbox.enqueue(entry(reason = null))
        assertNull(outbox.pending().single().reason)

        outbox.clear()
        outbox.enqueue(entry(operation = OutboxOperation.ADJUSTMENT, reason = ""))
        assertEquals("", outbox.pending().single().reason)
    }

    @Test
    fun `a corrupt line is skipped without stranding the rest of the queue`() {
        val good = UUID.randomUUID()
        outbox.enqueue(entry(key = good))

        val file = File(directory, "pending.log")
        file.writeText("this is not a record\n" + file.readText())

        // One unreadable line — an older format, a half-written file — must not
        // make every other queued sale unreachable.
        val survivors = Outbox(file).pending()
        assertEquals(1, survivors.size)
        assertEquals(good, survivors.single().clientRequestId)
    }

    @Test
    fun `a negative adjustment delta round-trips with its sign`() {
        outbox.enqueue(entry(operation = OutboxOperation.ADJUSTMENT, quantity = "-4"))

        // Direction is the whole meaning of an adjustment. A sign lost in
        // storage turns "four units were damaged" into "four units arrived".
        val restored = outbox.pending().single()
        assertEquals(0, BigDecimal("-4").compareTo(restored.quantity))
        assertEquals(OutboxOperation.ADJUSTMENT, restored.operation)
    }

    @Test
    fun `a large quantity is stored plainly, never in scientific notation`() {
        outbox.enqueue(entry(quantity = "100000000"))

        val file = File(directory, "pending.log")
        assertTrue(
            "stored line must not contain an exponent: ${file.readText()}",
            !file.readText().contains("E+"),
        )
        assertNotNull(outbox.pending().single())
        assertEquals(0, BigDecimal("100000000").compareTo(outbox.pending().single().quantity))
    }
}
