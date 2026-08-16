package com.example.inventory.catalog;

import com.example.inventory.web.PreconditionFailedException;

/**
 * ETags for product reads and {@code If-Match} on product writes.
 *
 * <h2>The version, not the timestamp</h2>
 *
 * <p>Derived from {@code products.version}, the counter V4 adds and a trigger
 * increments on every UPDATE.
 *
 * <p>It was {@code updated_at} in the first cut, which worked for a single
 * endpoint and would have broken quietly as soon as anything wrote several rows
 * at once: PostgreSQL's {@code now()} is the <em>transaction</em> timestamp, so
 * a batch touching ten products stamps all ten identically. Two different rows
 * would then carry the same ETag, and a client holding one could satisfy
 * If-Match for another. M4's sales and M7's forecast jobs both write across the
 * catalogue in one transaction, so that was a matter of time rather than a
 * hypothetical.
 *
 * <p>A counter has none of that ambiguity: it is per row, it advances on every
 * change, and it says nothing about when the change happened.
 *
 * <h2>Strong, not weak</h2>
 *
 * <p>{@code W/} would claim semantic equivalence. The claim being made is
 * narrower and exact — this is one specific version of one row, used to decide
 * whether a write is based on a current read.
 */
final class ETags {

    private ETags() {
    }

    static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Reads an {@code If-Match} header back into the version it encodes, or null
     * for {@code *}.
     *
     * <p>A malformed value is rejected with 412 rather than ignored. Ignoring it
     * would silently downgrade a conditional write to an unconditional one — the
     * caller asked for a guarded write and would be told it succeeded, with no
     * guard applied.
     */
    static Long parse(String ifMatch) {
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();

        // "*" means "any current representation": a conditional write that
        // always passes, which is a legitimate thing to ask for.
        if ("*".equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new PreconditionFailedException("If-Match is not an ETag this server issued");
        }
    }
}
