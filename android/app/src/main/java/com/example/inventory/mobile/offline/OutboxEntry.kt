package com.example.inventory.mobile.offline

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/** What kind of ledger write is queued. Both are append-only and idempotent. */
enum class OutboxOperation { SALE, ADJUSTMENT }

/**
 * One stock-moving request captured on the device, waiting to reach the server.
 *
 * @param clientRequestId **the idempotency key, minted when the user tapped
 *   save — never when the request is sent.** This is the whole reason a replay
 *   is safe: the first attempt may already have been recorded before the network
 *   dropped, and the server recognises the second attempt as the same operation
 *   (`sales_idempotency_uq`, `stock_movements_idempotency_uq`). A key generated
 *   at send time would record the sale twice and take the stock twice, and
 *   nobody would notice until someone counted the shelf.
 *
 *   M3's `RecordSaleViewModel` already minted it at the tap for the online
 *   retry path; this is the same field surviving a process death.
 *
 * @param capturedAt business time, also from the tap. A sale queued at 23:59 and
 *   replayed at 00:02 still belongs to the day it was made — the tenant's daily
 *   rollup buckets on this value, so sending "now" at replay time would move a
 *   sale between business days.
 *
 * @param quantity for a SALE, the units sold (positive). For an ADJUSTMENT, the
 *   signed delta — negative for damage or theft.
 *
 * @param attempts how many times replay has been tried. Recorded for the user's
 *   benefit and for diagnosis; it is deliberately NOT a give-up counter. An
 *   entry leaves this queue because the server accepted it or because the server
 *   refused it for a reason retrying cannot fix — never because it has been
 *   tried enough times. Dropping a real sale after N attempts loses money
 *   silently, which is the one outcome worse than a queue that will not drain.
 *
 * @param lastError the most recent failure, for display next to a stuck entry.
 */
data class OutboxEntry(
    val clientRequestId: UUID,
    val operation: OutboxOperation,
    val productId: UUID,
    val quantity: BigDecimal,
    val capturedAt: OffsetDateTime,
    val reason: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/**
 * What happened to one entry on one replay attempt.
 *
 * The distinction that matters is [Rejected] versus [Deferred]: a rejection is
 * the server saying "this can never work", and the user has to be told. A
 * deferral is "not now", and the entry stays queued. Collapsing the two is how
 * a client either retries a doomed request forever or silently discards a real
 * sale.
 */
sealed interface ReplayOutcome {

    /** The server recorded it on this attempt. */
    data class Recorded(val entry: OutboxEntry) : ReplayOutcome

    /**
     * The server had already recorded it — a 200 rather than a 201.
     *
     * This is not a failure and not a duplicate; it is the idempotency key
     * doing exactly its job. It means a previous attempt reached the server and
     * the response was lost on the way back.
     */
    data class AlreadyRecorded(val entry: OutboxEntry) : ReplayOutcome

    /**
     * The server refused it for a reason no amount of retrying will change —
     * the product was deleted while the device was offline, or the stock is no
     * longer there.
     *
     * The entry is removed from the queue and the user is told. It must not be
     * dropped silently: a sale that will never be recorded is money the shop
     * took and has no record of.
     */
    data class Rejected(val entry: OutboxEntry, val message: String, val detail: String? = null)
        : ReplayOutcome

    /** Not now — no network, a 5xx, or an expired session. Stays queued. */
    data class Deferred(val entry: OutboxEntry, val reason: String) : ReplayOutcome
}

/** The result of draining the queue once. */
data class ReplayReport(
    val recorded: List<ReplayOutcome.Recorded> = emptyList(),
    val alreadyRecorded: List<ReplayOutcome.AlreadyRecorded> = emptyList(),
    val rejected: List<ReplayOutcome.Rejected> = emptyList(),
    val deferred: List<ReplayOutcome.Deferred> = emptyList(),
) {
    val syncedCount: Int get() = recorded.size + alreadyRecorded.size

    /** True when something needs the user's attention rather than more patience. */
    val needsAttention: Boolean get() = rejected.isNotEmpty()
}
