package com.example.inventory.mobile.offline

import java.io.File
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The durable queue of stock-moving requests captured while offline.
 *
 * ## Why a file and not Room
 *
 * This is one ordered list that is appended to, read in order, and removed from
 * the front — not a relational cache. Room would bring a compiler plugin and a
 * schema-migration story to express a list, and CLAUDE.md §6 says not to add
 * dependencies without asking.
 *
 * The cached catalogue that `GET /sync/changes` feeds is a different problem
 * with a different shape. If that lands in a local database later, this queue
 * can move into it — nothing in the replay path depends on the storage.
 *
 * ## Durability
 *
 * One record per line, rewritten whole on every mutation. At the size this queue
 * ever reaches — a shift's sales on a phone with no signal — a full rewrite
 * costs nothing and removes a class of bug: there is no partial update, so a
 * process killed mid-write leaves either the old file or the new one.
 *
 * The write goes to a temporary file that is then renamed over the target,
 * because a rename is atomic and truncate-then-write is not. A process death in
 * the middle of the latter leaves an EMPTY queue — which loses a shift's sales
 * with no error anywhere, the exact outcome this feature exists to prevent.
 *
 * ## Synchronised on purpose
 *
 * The replayer and the UI both touch this. An interleaved read-modify-write on a
 * whole-file representation drops entries: the same lost update the atomic
 * rename prevents across processes, happening inside one.
 */
class Outbox(private val file: File) {

    /** Appends a captured request. Called the moment the user taps save. */
    @Synchronized
    fun enqueue(entry: OutboxEntry) {
        val entries = readAll().toMutableList()
        // Re-queuing the same key is a no-op rather than a second row. The key
        // identifies the operation, so two entries carrying it are one operation
        // the server would dedupe anyway — and a queue that can hold duplicates
        // makes every count shown to the user wrong.
        if (entries.none { it.clientRequestId == entry.clientRequestId }) {
            entries.add(entry)
            writeAll(entries)
        }
    }

    /** Everything still waiting, oldest first. */
    @Synchronized
    fun pending(): List<OutboxEntry> = readAll()

    @Synchronized
    fun size(): Int = readAll().size

    /** Removes a resolved entry — recorded, already recorded, or rejected. */
    @Synchronized
    fun remove(clientRequestId: UUID) {
        writeAll(readAll().filterNot { it.clientRequestId == clientRequestId })
    }

    /** Records a deferral against an entry that stays queued. */
    @Synchronized
    fun recordFailure(clientRequestId: UUID, error: String) {
        writeAll(
            readAll().map {
                if (it.clientRequestId == clientRequestId) {
                    it.copy(attempts = it.attempts + 1, lastError = error)
                } else {
                    it
                }
            }
        )
    }

    @Synchronized
    fun clear() {
        writeAll(emptyList())
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private fun readAll(): List<OutboxEntry> {
        if (!file.exists()) {
            return emptyList()
        }
        return file.readLines()
            .filter { it.isNotBlank() }
            // A line that cannot be parsed is skipped rather than throwing. One
            // corrupt line — a half-written file from an older build, a format
            // change — must not make the whole queue unreadable and strand every
            // other sale in it.
            .mapNotNull { runCatching { decode(it) }.getOrNull() }
    }

    private fun writeAll(entries: List<OutboxEntry>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(entries.joinToString("\n") { encode(it) })
        if (!temp.renameTo(file)) {
            // Rename can fail across some filesystems. Falling back to a direct
            // write is worse but still better than losing the queue, and it is
            // the only remaining option at this point.
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    /**
     * Positional encoding with escaping, rather than a JSON library.
     *
     * Every value here already has a stable, unambiguous string form — UUID,
     * BigDecimal, OffsetDateTime (ISO-8601). The only free text is
     * [OutboxEntry.reason] and [OutboxEntry.lastError], and both are escaped.
     *
     * BigDecimal goes through `toPlainString`: `toString` can emit `1E+2`, and a
     * quantity that round-trips as scientific notation is a quantity the server
     * may read differently from what the cashier typed.
     */
    private fun encode(entry: OutboxEntry): String = listOf(
        FORMAT_VERSION,
        entry.clientRequestId.toString(),
        entry.operation.name,
        entry.productId.toString(),
        entry.quantity.toPlainString(),
        entry.capturedAt.toString(),
        escape(entry.reason),
        entry.attempts.toString(),
        escape(entry.lastError),
    ).joinToString(FIELD_SEPARATOR)

    private fun decode(line: String): OutboxEntry {
        val parts = line.split(FIELD_SEPARATOR)
        require(parts.size == FIELD_COUNT) { "expected $FIELD_COUNT fields, got ${parts.size}" }
        require(parts[0] == FORMAT_VERSION) { "unknown format ${parts[0]}" }
        return OutboxEntry(
            clientRequestId = UUID.fromString(parts[1]),
            operation = OutboxOperation.valueOf(parts[2]),
            productId = UUID.fromString(parts[3]),
            quantity = BigDecimal(parts[4]),
            capturedAt = OffsetDateTime.parse(parts[5]),
            reason = unescape(parts[6]),
            attempts = parts[7].toInt(),
            lastError = unescape(parts[8]),
        )
    }

    private fun escape(value: String?): String = when (value) {
        null -> NULL_MARKER
        else -> value
            .replace("\\", "\\\\")
            .replace(FIELD_SEPARATOR, "\\u001F")
            .replace("\n", "\\n")
    }

    private fun unescape(value: String): String? = when (value) {
        NULL_MARKER -> null
        else -> value
            .replace("\\n", "\n")
            .replace("\\u001F", FIELD_SEPARATOR)
            .replace("\\\\", "\\")
    }

    private companion object {
        /** Bumped if the layout changes; an old line is then skipped, not misread. */
        const val FORMAT_VERSION = "v1"

        const val FIELD_COUNT = 9

        /**
         * ASCII unit separator, built from its code point rather than typed as
         * a literal.
         *
         * A literal control character in source is invisible in a diff, survives
         * copy-paste badly, and is one editor's "strip non-printing characters"
         * away from silently changing the file format — which here would mean
         * every queued sale becoming unreadable at once.
         */
        val FIELD_SEPARATOR = 0x1F.toChar().toString()

        /** ASCII record separator — distinguishes a null field from an empty one. */
        val NULL_MARKER = 0x1E.toChar().toString()
    }
}
