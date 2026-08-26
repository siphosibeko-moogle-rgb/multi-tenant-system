package com.example.inventory.sync;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.example.inventory.web.BadRequestException;

/**
 * The watermark a client carries between syncs: "I have everything up to and
 * including this point."
 *
 * <p>A position in {@code change_log}'s logical clock, as the pair
 * {@code (xactId, seq)} — never a timestamp. {@code V12__change_log.sql} has the
 * argument for why in full; the short version is that a wall-clock watermark
 * ties (one transaction stamps every row it touches identically), can loop
 * forever (a dense enough tenant fills a page with one timestamp and the
 * watermark never advances), and can move backwards (NTP).
 *
 * <h2>Two fields, not one</h2>
 *
 * <p>{@code xactId} alone is not a position. One transaction changing 500
 * products writes 500 rows that all share it, so a page ending in the middle of
 * that transaction could not be resumed — the next request would either repeat
 * the whole transaction or skip the rest of it. {@code seq} orders within a
 * transaction, and the pair is unique. Exactly the reason {@code /users} pages
 * on {@code (created_at, id)} and not on {@code created_at} (CLAUDE.md §13).
 *
 * <h2>Opaque on purpose</h2>
 *
 * <p>Base64, like every other cursor here. The contract calls it "opaque token
 * from the previous sync", and a client that parses one is broken by any change
 * to the ordering. The version prefix means a future change to the encoding can
 * reject an old token loudly instead of misreading it as a position.
 *
 * <p><strong>It is not a security boundary.</strong> A caller can decode it and
 * forge one; that is harmless, because the feed is tenant-scoped by RLS on the
 * connection and a forged token can only make a client miss its own rows or
 * re-read them. It carries no tenant id — nothing here reads identity from the
 * request (T1).
 */
record SyncToken(long xactId, long seq) {

    /** Before everything. What an omitted {@code since} means: a full snapshot. */
    static final SyncToken START = new SyncToken(0L, 0L);

    private static final String VERSION = "v1";

    String encode() {
        String raw = VERSION + ":" + xactId + ":" + seq;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param token the caller's {@code since}; null or blank means a full snapshot
     * @throws BadRequestException on anything unparseable — 400, never 500.
     *         A client holding a corrupted token must be told to start over; a
     *         500 tells it to retry, and it would retry the same bad token
     *         forever. Same reasoning as the typed-parameter handler in
     *         {@code GlobalExceptionHandler}.
     */
    static SyncToken decode(String token) {
        if (token == null || token.isBlank()) {
            return START;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("unexpected token layout");
            }
            long xactId = Long.parseLong(parts[1]);
            long seq = Long.parseLong(parts[2]);
            if (xactId < 0 || seq < 0) {
                throw new IllegalArgumentException("negative position");
            }
            return new SyncToken(xactId, seq);
        } catch (RuntimeException e) {
            throw new BadRequestException(
                    "The `since` token could not be read. Omit it to resynchronise "
                            + "from scratch.");
        }
    }
}
