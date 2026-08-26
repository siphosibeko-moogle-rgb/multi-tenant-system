package com.example.inventory.mobile.offline

import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.api.models.AdjustmentRequest
import com.example.inventory.api.models.SaleWriteRequest
import com.example.inventory.api.models.SaleWriteRequestLinesInner
import com.example.inventory.mobile.net.toApiError
import retrofit2.Response

/**
 * Drains the [Outbox] through the real endpoints when the network comes back.
 *
 * ## There is no second idempotency mechanism here
 *
 * Every entry already carries the `clientRequestId` minted when the user tapped
 * save. Sales send it in the body, adjustments in the `Idempotency-Key` header,
 * and the server deduplicates on it (`sales_idempotency_uq`,
 * `stock_movements_idempotency_uq`). This class does no deduplication of its
 * own, keeps no "already sent" set, and does not try to guess whether a request
 * arrived. It sends, and reads the answer.
 *
 * That is the whole point of the key: a request whose response was lost is
 * indistinguishable, from here, from one that never arrived. Only the server can
 * tell, and it does — with a 200 instead of a 201.
 *
 * ## The classification is the hard part
 *
 * Every failure lands in one of two buckets, and collapsing them is how clients
 * either retry a doomed request forever or silently discard a real sale:
 *
 * | Response | Bucket | Why |
 * |---|---|---|
 * | 200 / 201 | resolved | recorded; 200 means a previous attempt got through |
 * | no response (IO, timeout, DNS) | deferred | still offline; the request may or may not have landed |
 * | 401 | deferred | the session expired. The sale is real and must survive a re-login |
 * | 408, 429, 5xx | deferred | the server's problem, not the request's |
 * | any other 4xx | **rejected** | the request itself is wrong and will never work |
 *
 * A 409 on a queued sale is the interesting rejection: the product was oversold
 * through another till while this phone was offline. The queued sale genuinely
 * cannot be recorded — the stock is not there — and the cashier has to be told,
 * because they have already handed over goods. Retrying it forever would hide
 * that; dropping it silently would lose the record entirely.
 *
 * ## Attempts are counted but never capped
 *
 * An entry leaves the queue because the server accepted it, or because the
 * server refused it for a reason retrying cannot fix. Never because it has been
 * tried enough times. Discarding a real sale after N attempts loses money
 * silently, which is worse than a queue that will not drain — a queue that will
 * not drain is at least visible.
 */
class OutboxReplayer(
    private val outbox: Outbox,
    private val sales: SalesApi,
    private val inventory: InventoryApi,
) {

    /**
     * Attempts every queued entry once, oldest first.
     *
     * Order matters and is preserved: two adjustments to the same product must
     * land in the order the person made them, and a sale queued before a
     * stock-in should be attempted before it — the refusal is then honest about
     * what was on the shelf at that moment.
     *
     * Stops early on the first deferral. If the network is still down, the
     * second entry will fail identically, and hammering the whole queue against
     * a dead link burns battery and inflates every entry's attempt count for no
     * information. A rejection does not stop the drain: it is specific to that
     * entry and says nothing about the next one.
     */
    suspend fun replayAll(): ReplayReport {
        var report = ReplayReport()

        for (entry in outbox.pending()) {
            when (val outcome = replay(entry)) {
                is ReplayOutcome.Recorded -> {
                    outbox.remove(entry.clientRequestId)
                    report = report.copy(recorded = report.recorded + outcome)
                }

                is ReplayOutcome.AlreadyRecorded -> {
                    outbox.remove(entry.clientRequestId)
                    report = report.copy(alreadyRecorded = report.alreadyRecorded + outcome)
                }

                is ReplayOutcome.Rejected -> {
                    // Removed, because it can never succeed — but surfaced in
                    // the report, because the person who took the money has to
                    // find out. This is the one path where losing the entry
                    // quietly would be indistinguishable from success.
                    outbox.remove(entry.clientRequestId)
                    report = report.copy(rejected = report.rejected + outcome)
                }

                is ReplayOutcome.Deferred -> {
                    outbox.recordFailure(entry.clientRequestId, outcome.reason)
                    report = report.copy(deferred = report.deferred + outcome)
                    return report
                }
            }
        }
        return report
    }

    private suspend fun replay(entry: OutboxEntry): ReplayOutcome = try {
        when (entry.operation) {
            OutboxOperation.SALE -> classify(entry, sendSale(entry))
            OutboxOperation.ADJUSTMENT -> classify(entry, sendAdjustment(entry))
        }
    } catch (e: Exception) {
        // No HTTP response at all — still offline, or the connection died
        // mid-flight. The request may well have been recorded; the key is what
        // makes finding out safe next time.
        ReplayOutcome.Deferred(entry, describe(e))
    }

    private suspend fun sendSale(entry: OutboxEntry): Response<*> = sales.salesPost(
        SaleWriteRequest(
            lines = listOf(
                SaleWriteRequestLinesInner(
                    productId = entry.productId,
                    quantity = entry.quantity,
                )
            ),
            // The key, from capture time. Not generated here.
            clientRequestId = entry.clientRequestId,
            // Business time, from capture time. Sending "now" would move a sale
            // made at 23:59 into the next business day's rollup.
            soldAt = entry.capturedAt,
        )
    )

    private suspend fun sendAdjustment(entry: OutboxEntry): Response<*> =
        inventory.inventoryAdjustmentsPost(
            AdjustmentRequest(
                productId = entry.productId,
                quantityDelta = entry.quantity,
                reason = entry.reason ?: "Recorded offline",
                occurredAt = entry.capturedAt,
            ),
            // PASSED EXPLICITLY. The generated signature defaults this to null,
            // and taking that default would send no key at all — every replay
            // would post a second movement into an append-only ledger. CLAUDE.md
            // §5 records two Android bugs from trusting a generator's default;
            // this is the third place it would have happened.
            idempotencyKey = entry.clientRequestId,
        )

    private fun classify(entry: OutboxEntry, response: Response<*>): ReplayOutcome = when {
        response.code() == 200 -> ReplayOutcome.AlreadyRecorded(entry)
        response.isSuccessful -> ReplayOutcome.Recorded(entry)

        // The session expired while the queue was waiting. TokenAuthenticator
        // has already tried a refresh by the time this is seen, so a 401 here
        // means the refresh token is gone too. The sale is real and must outlive
        // a re-login — deferring keeps it; rejecting would destroy a record of
        // money taken because somebody's password changed.
        response.code() == 401 -> ReplayOutcome.Deferred(entry, "Sign in again to sync")

        response.code() == 408 || response.code() == 429 || response.code() >= 500 ->
            ReplayOutcome.Deferred(entry, "The server is busy. Will retry.")

        else -> {
            // Any other 4xx: the request itself is the problem. A deleted
            // product (404), an oversell (409), a validation failure (422) — all
            // permanent for this payload. Reuse the app's existing problem
            // parsing so the wording is the same one the online path shows.
            val apiError = response.toApiError()
            ReplayOutcome.Rejected(entry, apiError.message, apiError.detail)
        }
    }

    private fun describe(e: Exception): String =
        com.example.inventory.mobile.net.ApiError.fromNetworkFailure(e).message
}
