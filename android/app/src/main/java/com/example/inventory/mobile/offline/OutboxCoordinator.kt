package com.example.inventory.mobile.offline

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The thing that actually makes the outbox drain.
 *
 * ## Why this class exists
 *
 * It exists because it was missing, and the way it was missing is the exact
 * failure CLAUDE.md §5 already records once:
 *
 * > A pipeline whose stages are each tested with the previous stage's output
 * > already provided has no test of the wiring between them. Ask of any
 * > multi-stage job: *does anything exercise stage N without stage N−1 being
 * > done by hand first?*
 *
 * [OutboxReplayer] was written, wired into Hilt, and covered by twenty-four
 * tests — every one of which calls `replayAll()` **itself**. Nothing in the app
 * ever called it. A sale captured with no signal would have been queued
 * durably, correctly, with the right key… and sat there forever. The tests were
 * all green and the feature did not work, which is precisely what happened to
 * M7's `recomputeAll()` never running `DemandRollupJob`.
 *
 * So the rule from that milestone applies here too: the test for this class
 * must never call [OutboxReplayer.replayAll] in its own fixture, or it restores
 * the blind spot it exists to close.
 *
 * ## When a drain is attempted
 *
 * Two triggers, both cheap and both meaning "the network is plausibly up":
 *
 * 1. **Entering the signed-in app** — covers the reconnect-after-a-shift case,
 *    including a cold start after the process was killed.
 * 2. **After a sale records successfully** — the strongest possible evidence
 *    that connectivity is back, and it costs nothing: the queue is almost
 *    always empty, in which case [OutboxReplayer.replayAll] makes no requests
 *    at all.
 *
 * Deliberately **not** a `ConnectivityManager` callback or a `WorkManager` job.
 * The first fires on transitions that are frequently wrong (a captive portal is
 * "connected"), and the second is a dependency decision nobody has taken. Both
 * are reasonable later; neither is needed to make a queued sale reach the
 * server, and adding either without asking would be the change this codebase
 * keeps warning about.
 */
@Singleton
class OutboxCoordinator @Inject constructor(
    private val outbox: Outbox,
    private val replayer: OutboxReplayer,
) {

    private val _pending = MutableStateFlow(0)

    /** How many captured operations are still waiting. Drives the UI's badge. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _lastReport = MutableStateFlow<ReplayReport?>(null)

    /**
     * The most recent drain, or null if none has run.
     *
     * Held so a rejection can be shown to the person who took the money: a sale
     * refused because the stock went while they were offline is the one outcome
     * that must reach a human, and the drain that discovers it may happen while
     * they are looking at another screen.
     */
    val lastReport: StateFlow<ReplayReport?> = _lastReport.asStateFlow()

    /**
     * One drain at a time.
     *
     * Both triggers can fire together — a sale recorded moments after the app
     * opened — and two concurrent drains would send the same entry twice. The
     * server would dedupe on the idempotency key, so this is not a correctness
     * backstop; it is what keeps the pending count and the report from being
     * computed from a queue that is being emptied underneath them.
     */
    private val draining = Mutex()

    /** Refreshes [pending] without sending anything. */
    fun refreshPendingCount() {
        _pending.value = outbox.size()
    }

    /**
     * Attempts every queued entry once.
     *
     * Safe to call when there is nothing queued and safe to call when offline:
     * an empty queue makes no requests, and a failed one defers rather than
     * discarding. So callers do not have to know whether the network is up —
     * which is the point, because they cannot reliably find out.
     */
    suspend fun syncNow(): ReplayReport = draining.withLock {
        val report = replayer.replayAll()
        _pending.value = outbox.size()
        if (report.syncedCount > 0 || report.needsAttention) {
            _lastReport.value = report
        }
        report
    }

    /** Clears a report once the user has seen it. */
    fun acknowledgeReport() {
        _lastReport.value = null
    }
}
